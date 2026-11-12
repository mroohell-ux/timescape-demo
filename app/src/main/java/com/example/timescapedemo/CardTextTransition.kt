package com.example.timescapedemo

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.Log
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Matching glyph strips share one moving plane; unmatched reading text fades away. */
internal class CardTextTransition(private val root: TimescapeCardLayout) {
    private val reading = listOf(R.id.time, R.id.title, R.id.snippet)
        .map { root.findViewById<TimescapeTextView>(it) }
    private val preview = listOf(R.id.previewTime, R.id.previewTitle, R.id.previewSnippet)
        .map { root.findViewById<TimescapeTextView>(it) }
    private val textViews = reading + preview
    private val faces = textViews.associateWith { Face(it) }
    private val usable = ArrayList<Face>(textViews.size)
    private val runs = ArrayList<Run>()
    private val matches = HashMap<TimescapeTextView, TextMatches>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glyphHalo = 3f * root.resources.displayMetrics.density
    private val occlusionFeather = 2f * root.resources.displayMetrics.density
    private val retreatDistance = 8f * root.resources.displayMetrics.density
    private val occlusionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.DST_OUT }
    private val inverse = Matrix()
    private val sourceCorners = FloatArray(8)
    private val targetCorners = FloatArray(8)
    private val bottomPoint = FloatArray(2)
    private val layerBounds = RectF()
    private var ready = false
    internal var snapshotCount = 0
        private set
    internal var preparationCount = 0
        private set
    internal var inkScanCount = 0
        private set
    internal var sharedRunCount = 0
        private set
    internal val readingSnapshotBounds: RectF get() = RectF(faces.getValue(reading[2]).snapshotBounds)
    internal val readingViewportBounds: RectF get() = RectF(faces.getValue(reading[2]).bounds)
    internal val readingSnapshotSize: Pair<Int, Int>?
        get() = faces.getValue(reading[2]).bitmap?.let { it.width to it.height }

    fun clear(releaseSnapshots: Boolean = true) {
        faces.values.forEach {
            if (releaseSnapshots) it.release() else it.clearTransition()
        }
        if (releaseSnapshots) matches.clear()
        textViews.forEach { it.alpha = 1f }
        usable.clear()
        runs.clear()
        sharedRunCount = 0
        ready = false
    }

    fun trimInactiveSnapshots() {
        if (!ready && faces.values.any { it.bitmap != null }) clear()
    }

    fun prepare(progress: Float, media: Boolean): Boolean {
        if (media || progress <= 0f || progress >= 1f) {
            if (ready || (media && faces.values.any { it.bitmap != null })) {
                clear(releaseSnapshots = media)
            }
            return false
        }
        var changed = !ready || faces.values.any {
            (it.view.visibility != View.GONE && it.view.text.isNotEmpty()) != usable.contains(it)
        }
        usable.clear()
        faces.values.forEach {
            if (it.view.visibility != View.GONE && it.view.text.isNotEmpty()) usable.add(it)
        }
        if (usable.any {
                it.view.layout == null || it.view.width == 0 || it.view.height == 0 ||
                    (it.view.parent as? CardContentScrollView)?.height == 0
            }
        ) {
            if (ready) clear()
            return false
        }
        for (face in usable) {
            if (face.ensureSnapshot()) {
                snapshotCount++
                changed = true
            }
            if (face.viewportChanged) changed = true
        }
        if (changed) {
            preparationCount++
            runs.clear()
            faces.values.forEach {
                it.shared.reset()
                it.sharedRanges.clear()
                it.extras.clear()
            }
            connect(reading[1], preview[1])
            connect(reading[2], preview[2])
            usable.forEach { it.prepareExtras() }
            sharedRunCount = runs.size
        }
        textViews.forEach { it.alpha = 0f }
        ready = true
        return true
    }

    private fun connect(source: TimescapeTextView, target: TimescapeTextView) {
        val from = faces.getValue(source)
        val to = faces.getValue(target)
        if (source.visibility == View.GONE || target.visibility == View.GONE ||
            from.bitmap == null || to.bitmap == null
        ) return
        val sourceLayout = source.layout ?: return
        val targetLayout = target.layout ?: return
        val lastLine = targetLayout.lineCount - 1
        val visibleEnd = if (targetLayout.getEllipsisCount(lastLine) > 0) {
            targetLayout.getLineStart(lastLine) + targetLayout.getEllipsisStart(lastLine)
        } else min(target.text.length, targetLayout.getLineEnd(lastLine))
        val cached = matches[source]
        val offsets = if (cached != null && cached.sourceLayout === sourceLayout &&
            cached.targetLayout === targetLayout && cached.sourceRevision == source.contentRevision &&
            cached.targetRevision == target.contentRevision && cached.visibleEnd == visibleEnd
        ) cached.offsets else {
            val result = matchedCardTextOffsets(source.text, target.text, visibleEnd)
            matches[source] = TextMatches(sourceLayout, targetLayout, source.contentRevision,
                target.contentRevision, visibleEnd, result)
            if (result.isEmpty() && normalizeCardText(source.text).text.isNotEmpty() &&
                !normalizeCardText(target.text).text.contains(normalizeCardText(source.text).text)
            ) {
                Log.w("TimescapeTextTransition", "Reading/preview text changed before their layouts matched")
            }
            result
        }
        var cursor = 0
        while (cursor < offsets.size) {
            val sourceStart = offsets[cursor]
            val targetStart = offsets[cursor + 1]
            var sourceEnd = sourceStart + 1
            var targetEnd = targetStart + 1
            cursor += 2
            while (cursor < offsets.size && offsets[cursor] == sourceEnd &&
                offsets[cursor + 1] == targetEnd &&
                sourceLayout.getLineForOffset(sourceEnd) == sourceLayout.getLineForOffset(sourceStart) &&
                targetLayout.getLineForOffset(targetEnd) == targetLayout.getLineForOffset(targetStart) &&
                sourceLayout.isRtlCharAt(sourceEnd) == sourceLayout.isRtlCharAt(sourceStart) &&
                targetLayout.isRtlCharAt(targetEnd) == targetLayout.isRtlCharAt(targetStart)
            ) {
                sourceEnd++
                targetEnd++
                cursor += 2
            }
            val sourceStrip = strip(from, sourceStart, sourceEnd) ?: continue
            val targetStrip = strip(to, targetStart, targetEnd) ?: continue
            from.addMask(sourceStrip.corners, sourceStrip.line)
            to.addMask(targetStrip.corners, targetStrip.line)
            from.sharedRanges += sourceStart until sourceEnd
            to.sharedRanges += targetStart until targetEnd
            runs += Run(from, to, sourceStrip, targetStrip)
        }
    }

    fun draw(canvas: Canvas, progress: Float) {
        if (!ready) return
        usable.forEach { it.updateMatrix() }
        val collapse = 1f - progress
        val material = smoothstep(collapse)
        for (face in usable) for (extra in face.extras) {
            face.matrix.mapRect(extra.inkBounds, extra.ink)
        }
        for (run in runs) {
            run.source.matrix.mapPoints(sourceCorners, run.sourceStrip.corners)
            run.target.matrix.mapPoints(targetCorners, run.targetStrip.corners)
            for (coordinate in run.corners.indices) {
                run.corners[coordinate] = sourceCorners[coordinate] +
                    (targetCorners[coordinate] - sourceCorners[coordinate]) * collapse
            }
            check(run.sourceProjection.setPolyToPoly(run.sourceStrip.corners, 0, run.corners, 0, 4)) {
                "Invalid shared source projection"
            }
            check(run.targetProjection.setPolyToPoly(run.targetStrip.corners, 0, run.corners, 0, 4)) {
                "Invalid shared target projection"
            }
            run.source.matrix.mapRect(run.sourceBounds, run.sourceStrip.ink)
            run.target.matrix.mapRect(run.targetBounds, run.targetStrip.ink)
            run.sourceProjection.mapRect(run.inkBounds, run.sourceStrip.ink)
            run.targetProjection.mapRect(run.targetStrip.inkBounds, run.targetStrip.ink)
            run.inkBounds.union(run.targetStrip.inkBounds)
            run.visibility = 1f
        }
        for (index in runs.indices) {
            val run = runs[index]
            for (frontIndex in 0 until index) {
                val front = runs[frontIndex]
                val sourceGap = gap(run.sourceBounds, front.sourceBounds)
                val targetGap = gap(run.targetBounds, front.targetBounds)
                run.visibility = min(run.visibility, retreat(gap(run.inkBounds, front.inkBounds),
                    sourceGap + (targetGap - sourceGap) * collapse, front.visibility))
            }
        }
        // Occlusion clears only this text layer, never the glass or photograph below.
        layerBounds.set(0f, 0f, root.width.toFloat(), root.height.toFloat())
        runs.forEach { layerBounds.union(it.inkBounds) }
        preview.forEach { view ->
            faces.getValue(view).extras.forEach { layerBounds.union(it.inkBounds) }
        }
        val layer = canvas.saveLayer(layerBounds, null)
        try {
            val saved = canvas.save()
            try {
                canvas.clipRect(0f, 0f, root.width.toFloat(), root.height.toFloat())
                reading.forEach {
                    val face = faces.getValue(it)
                    val alpha = if (it === reading[0]) metadataAlpha(progress) else smoothstep(progress)
                    drawExtras(canvas, face, alpha, collapse)
                }
            } finally {
                canvas.restoreToCount(saved)
            }
            preview.forEach {
                val alpha = if (it === preview[0]) metadataAlpha(collapse) else material
                drawExtras(canvas, faces.getValue(it), alpha, 0f, foreground = true)
            }
            // Earlier text stays in front when different line-wrap paths cross.
            for (index in runs.indices.reversed()) {
                val run = runs[index]
                val opacity = reveal(run.visibility)
                occludeRun(canvas, run.sourceProjection, run.sourceStrip.mask, opacity)
                occludeRun(canvas, run.targetProjection, run.targetStrip.mask, opacity)
                paint.shader = null
                paint.blendMode = BlendMode.PLUS
                drawRun(canvas, run.source, run.sourceStrip, run.sourceProjection, (1f - material) * run.visibility)
                drawRun(canvas, run.target, run.targetStrip, run.targetProjection, material * run.visibility)
            }
        } finally {
            paint.blendMode = null
            paint.shader = null
            canvas.restoreToCount(layer)
        }
    }

    private fun glyphStrip(view: TimescapeTextView, start: Int, end: Int, rect: RectF) = RectF(rect).apply {
        inset(-glyphHalo, 0f)
        val layout = requireNotNull(view.layout)
        val line = layout.getLineForOffset(start)
        val rtl = layout.isRtlCharAt(start)
        if (start > 0 && !view.text[start - 1].isWhitespace() &&
            layout.getLineForOffset(start - 1) == line
        ) {
            if (rtl) right = rect.right else left = rect.left
        }
        if (end < view.text.length && !view.text[end].isWhitespace() &&
            layout.getLineForOffset(end) == line
        ) {
            if (rtl) left = rect.left else right = rect.right
        }
    }

    private fun strip(face: Face, start: Int, end: Int): Strip? {
        val cached = face.cachedStrip(start, end) ?: return null
        val bounds = RectF(cached.core)
        if (!bounds.intersect(face.bounds)) return null
        if (bounds == cached.core) return cached
        val ink = face.inkBounds(bounds) ?: return null
        return Strip(cached.clip, cached.line, cached.corners, ink,
            Occlusion(bounds, occlusionFeather), bounds)
    }

    private fun occludeRun(canvas: Canvas, transform: Matrix, mask: Occlusion, alpha: Int) {
        if (alpha == 0) return
        val saved = canvas.save()
        canvas.concat(transform)
        drawOcclusion(canvas, mask, alpha)
        canvas.restoreToCount(saved)
    }

    private fun drawOcclusion(canvas: Canvas, mask: Occlusion, alpha: Int) {
        occlusionPaint.alpha = alpha
        occlusionPaint.shader = mask.shader
        canvas.drawRect(mask.bounds, occlusionPaint)
    }

    private fun drawRun(canvas: Canvas, face: Face, strip: Strip, transform: Matrix, alpha: Float) {
        val opacity = (alpha * 255f).roundToInt()
        if (opacity <= 0) return
        val bitmap = face.bitmap ?: return
        paint.alpha = opacity
        val saved = canvas.save()
        canvas.concat(transform)
        // Merging these clips changes edge rasterization under perspective.
        canvas.clipRect(strip.clip)
        canvas.clipRect(strip.line)
        if (face.bounds != face.snapshotBounds) canvas.clipRect(face.bounds)
        canvas.drawBitmap(bitmap, null, face.snapshotBounds, paint)
        canvas.restoreToCount(saved)
    }

    private fun drawExtras(canvas: Canvas, face: Face, alpha: Float, collapse: Float,
        foreground: Boolean = false
    ) {
        val opacity = (alpha * 255f).roundToInt()
        if (opacity <= 0 || face.bitmap == null || face.view.visibility == View.GONE) return
        paint.alpha = opacity
        paint.blendMode = null
        val shader = requireNotNull(face.shader)
        val plane = face.matrix
        if (collapse > 0f) {
            check(plane.invert(inverse)) { "Text plane is not invertible" }
            bottomPoint[0] = root.width / 2f
            bottomPoint[1] = root.height.toFloat()
            inverse.mapPoints(bottomPoint)
            val end = bottomPoint[1]
            val margin = max(1f, 32f * root.resources.displayMetrics.density * collapse)
            paint.shader = ComposeShader(shader, LinearGradient(0f, end - margin, 0f, end,
                -1, 0x00FFFFFF, Shader.TileMode.CLAMP), PorterDuff.Mode.DST_IN)
        } else paint.shader = shader
        for (extra in face.extras) {
            var visibility = 1f
            for (front in runs) {
                val anchor = if (foreground) front.targetBounds else front.sourceBounds
                visibility = min(visibility, retreat(gap(extra.inkBounds, front.inkBounds),
                    gap(extra.inkBounds, anchor), front.visibility))
            }
            if (!foreground) {
                for (view in preview) {
                    val front = faces.getValue(view)
                    val frontAlpha = if (view === preview[0]) metadataAlpha(collapse) else smoothstep(collapse)
                    for (strip in front.extras) {
                        visibility = min(visibility, 1f - revealFraction(frontAlpha) *
                            (1f - smoothstep((gap(extra.inkBounds, strip.inkBounds) /
                                retreatDistance).coerceIn(0f, 1f))))
                    }
                }
            }
            paint.alpha = (alpha * visibility * 255f).roundToInt()
            if (paint.alpha == 0) continue
            val saved = canvas.save()
            canvas.concat(plane)
            canvas.clipOutPath(face.shared)
            canvas.clipRect(extra.clip)
            canvas.clipRect(extra.line)
            if (foreground) drawOcclusion(canvas, extra.mask, reveal(alpha * visibility))
            canvas.drawRect(face.bounds, paint)
            canvas.restoreToCount(saved)
        }
        paint.shader = null
    }

    private fun gap(a: RectF, b: RectF): Float =
        max(max(a.left - b.right, b.left - a.right), max(a.top - b.bottom, b.top - a.bottom))

    private fun retreat(distance: Float, original: Float, foreground: Float): Float {
        val visibility = if (original > 0f) {
            smoothstep((distance / min(original, retreatDistance)).coerceIn(0f, 1f))
        } else {
            1f - smoothstep(((original - distance) /
                retreatDistance).coerceIn(0f, 1f))
        }
        return 1f - revealFraction(foreground) * (1f - visibility)
    }

    private fun revealFraction(alpha: Float) = smoothstep((alpha / .08f).coerceIn(0f, 1f))

    private fun reveal(alpha: Float) = (revealFraction(alpha) * 255f).roundToInt()

    private fun corners(rect: RectF, output: FloatArray) {
        output[0] = rect.left
        output[1] = rect.top
        output[2] = rect.right
        output[3] = rect.top
        output[4] = rect.right
        output[5] = rect.bottom
        output[6] = rect.left
        output[7] = rect.bottom
    }

    private fun smoothstep(value: Float) = value * value * (3f - 2f * value)

    private fun metadataAlpha(progress: Float) = smoothstep(((progress - .5f) * 2f).coerceIn(0f, 1f))

    private inner class Face(val view: TimescapeTextView) {
        var bitmap: Bitmap? = null
        var shader: BitmapShader? = null
        val shared = Path()
        val matrix = Matrix()
        val bounds = RectF()
        val snapshotBounds = RectF()
        val sharedRanges = ArrayList<IntRange>()
        val extras = ArrayList<Strip>()
        private var layout: Layout? = null
        private var color = 0
        private var depth = 0f
        private var revision = -1
        private var renderRevision = -1
        private var viewHeight = 0
        private var baseline = 0
        private var paddingLeft = 0
        private var scrollX = 0
        private var scrollY = 0
        private val visibleBounds = RectF()
        private val strips = HashMap<Long, Strip?>()
        var viewportChanged = false
            private set

        fun ensureSnapshot(): Boolean {
            val reader = view.parent as? CardContentScrollView
            val height = min(view.height, reader?.height ?: view.height)
            val top = reader?.scrollY?.coerceIn(0, (view.height - height).coerceAtLeast(0)) ?: 0
            visibleBounds.set(0f, top.toFloat(), view.width.toFloat(), (top + height).toFloat())
            viewportChanged = bounds != visibleBounds
            bounds.set(visibleBounds)
            val scale = min(1f, min(1536f / max(visibleBounds.width(), visibleBounds.height()),
                sqrt(1_048_576f / (visibleBounds.width() * visibleBounds.height()))))
            val pixelWidth = max(1, (visibleBounds.width() * scale).roundToInt())
            val pixelHeight = max(1, (visibleBounds.height() * scale).roundToInt())
            val image = bitmap
            // Reuse coverage only at the original resolution and raster sample origin.
            if (image != null && layout === view.layout && color == view.currentTextColor &&
                depth == view.depthAmount && revision == view.contentRevision &&
                renderRevision == view.renderRevision &&
                viewHeight == view.height && baseline == view.baseline &&
                paddingLeft == view.compoundPaddingLeft && scrollX == view.scrollX && scrollY == view.scrollY &&
                snapshotBounds.contains(visibleBounds) &&
                image.width / snapshotBounds.width() == pixelWidth / visibleBounds.width() &&
                image.height / snapshotBounds.height() == pixelHeight / visibleBounds.height() &&
                ((visibleBounds.top - snapshotBounds.top) * image.height / snapshotBounds.height())
                    .let { kotlin.math.abs(it - it.roundToInt()) < .0001f }
            ) return false
            release()
            val capture = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
            val recording = Canvas(capture)
            recording.scale(capture.width.toFloat() / visibleBounds.width(),
                capture.height.toFloat() / visibleBounds.height())
            recording.translate(-visibleBounds.left, -visibleBounds.top)
            view.draw(recording)
            bitmap = capture
            shader = BitmapShader(capture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                val pixelsToView = Matrix()
                pixelsToView.setScale(visibleBounds.width() / capture.width, visibleBounds.height() / capture.height)
                pixelsToView.postTranslate(visibleBounds.left, visibleBounds.top)
                setLocalMatrix(pixelsToView)
            }
            snapshotBounds.set(visibleBounds)
            layout = view.layout
            color = view.currentTextColor
            depth = view.depthAmount
            revision = view.contentRevision
            renderRevision = view.renderRevision
            viewHeight = view.height
            baseline = view.baseline
            paddingLeft = view.compoundPaddingLeft
            scrollX = view.scrollX
            scrollY = view.scrollY
            return true
        }

        fun cachedStrip(start: Int, end: Int): Strip? {
            val key = (start.toLong() shl 32) or end.toLong()
            if (strips.containsKey(key)) return strips[key]
            val rect = textRect(start, end)
            val clip = glyphStrip(view, start, end, rect)
            val line = lineRect(start)
            val core = RectF(clip)
            val ink = if (core.intersect(line) && core.intersect(snapshotBounds)) inkBounds(core) else null
            val strip = ink?.let {
                Strip(clip, line, FloatArray(8).also { points -> corners(rect, points) },
                    it, Occlusion(core, occlusionFeather), core)
            }
            strips[key] = strip
            return strip
        }

        fun prepareExtras() {
            val textLayout = requireNotNull(view.layout)
            val y = view.baseline - textLayout.getLineBaseline(0) - view.scrollY
            val first = textLayout.getLineForVertical((bounds.top - y).toInt())
            val last = textLayout.getLineForVertical((bounds.bottom - y).toInt())
            for (line in first..last) {
                var cursor = textLayout.getLineStart(line)
                val end = textLayout.getLineEnd(line)
                for (range in sharedRanges) {
                    if (range.last < cursor || range.first >= end) continue
                    addExtra(cursor, range.first)
                    cursor = range.last + 1
                }
                addExtra(cursor, end)
            }
        }

        private fun addExtra(start: Int, end: Int) {
            var first = start
            var last = end
            while (first < last && view.text[first].isWhitespace()) first++
            while (last > first && view.text[last - 1].isWhitespace()) last--
            if (first < last) strip(this, first, last)?.let { extras += it }
        }

        fun inkBounds(clip: RectF): RectF? {
            inkScanCount++
            val image = requireNotNull(bitmap)
            val scaleX = image.width / snapshotBounds.width()
            val scaleY = image.height / snapshotBounds.height()
            val x = floor((clip.left - snapshotBounds.left) * scaleX).toInt().coerceIn(0, image.width)
            val y = floor((clip.top - snapshotBounds.top) * scaleY).toInt().coerceIn(0, image.height)
            val right = ceil((clip.right - snapshotBounds.left) * scaleX).toInt().coerceIn(x, image.width)
            val bottom = ceil((clip.bottom - snapshotBounds.top) * scaleY).toInt().coerceIn(y, image.height)
            if (right == x || bottom == y) return null
            val width = right - x
            val pixels = IntArray(width * (bottom - y))
            image.getPixels(pixels, 0, width, x, y, width, bottom - y)
            val peak = pixels.maxOf { it ushr 24 }
            if (peak == 0) return null
            val threshold = max(1, (peak * .75f).toInt())
            val result = RectF()
            for (index in pixels.indices) if (pixels[index] ushr 24 >= threshold) {
                val px = (x + index % width).toFloat()
                val py = (y + index / width).toFloat()
                result.union(px, py, px + 1f, py + 1f)
            }
            result.set(result.left / scaleX + snapshotBounds.left, result.top / scaleY + snapshotBounds.top,
                result.right / scaleX + snapshotBounds.left, result.bottom / scaleY + snapshotBounds.top)
            return result
        }

        fun updateMatrix() {
            view.renderedMatrixToAncestor(root, matrix)
        }

        fun textRect(start: Int, end: Int): RectF {
            val textLayout = requireNotNull(view.layout)
            val selection = Path()
            textLayout.getSelectionPath(start, end, selection)
            val rect = RectF()
            selection.computeBounds(rect, true)
            val textPaint = TextPaint(view.paint)
            (view.text as? Spanned)?.getSpans(start, end, MetricAffectingSpan::class.java)
                ?.forEach { it.updateMeasureState(textPaint) }
            val line = textLayout.getLineForOffset(start)
            val baseline = view.baseline - textLayout.getLineBaseline(0) +
                textLayout.getLineBaseline(line) - view.scrollY
            rect.offset(view.compoundPaddingLeft.toFloat() - view.scrollX, 0f)
            // Equal font-relative bounds align both materials' baselines, not just their boxes.
            rect.top = baseline - textPaint.textSize * 1.4f
            rect.bottom = baseline + textPaint.textSize * .5f
            return rect
        }

        fun lineRect(offset: Int): RectF {
            val textLayout = requireNotNull(view.layout)
            val line = textLayout.getLineForOffset(offset)
            val y = view.baseline - textLayout.getLineBaseline(0) - view.scrollY
            return RectF(0f, (textLayout.getLineTop(line) + y).toFloat(),
                view.width.toFloat(), (textLayout.getLineBottom(line) + y).toFloat())
        }

        fun addMask(points: FloatArray, line: RectF) {
            val mask = RectF(points[0], points[1], points[4], points[5])
            mask.inset(-3f * view.resources.displayMetrics.density, 0f)
            if (mask.intersect(line)) shared.addRect(mask, Path.Direction.CW)
        }

        fun release() {
            bitmap?.recycle()
            bitmap = null
            shader = null
            layout = null
            strips.clear()
            snapshotBounds.setEmpty()
            clearTransition()
        }

        fun clearTransition() {
            shared.reset()
            sharedRanges.clear()
            extras.clear()
        }
    }

    private class TextMatches(
        val sourceLayout: Layout,
        val targetLayout: Layout,
        val sourceRevision: Int,
        val targetRevision: Int,
        val visibleEnd: Int,
        val offsets: IntArray
    )

    private class Occlusion(core: RectF, feather: Float) {
        val bounds = RectF(core).apply { inset(-feather, -feather) }
        private val colors = intArrayOf(0x00FFFFFF, -1, -1, 0x00FFFFFF)
        val shader = ComposeShader(
            LinearGradient(bounds.left, 0f, bounds.right, 0f, colors,
                floatArrayOf(0f, feather / bounds.width(), 1f - feather / bounds.width(), 1f),
                Shader.TileMode.CLAMP),
            LinearGradient(0f, bounds.top, 0f, bounds.bottom, colors,
                floatArrayOf(0f, feather / bounds.height(), 1f - feather / bounds.height(), 1f),
                Shader.TileMode.CLAMP),
            PorterDuff.Mode.DST_IN)
    }

    private class Strip(
        val clip: RectF,
        val line: RectF,
        val corners: FloatArray,
        val ink: RectF,
        val mask: Occlusion,
        val core: RectF,
        val inkBounds: RectF = RectF()
    )

    private class Run(
        val source: Face,
        val target: Face,
        val sourceStrip: Strip,
        val targetStrip: Strip,
        val corners: FloatArray = FloatArray(8),
        val sourceProjection: Matrix = Matrix(),
        val targetProjection: Matrix = Matrix(),
        val sourceBounds: RectF = RectF(),
        val targetBounds: RectF = RectF(),
        val inkBounds: RectF = RectF(),
        var visibility: Float = 1f
    )
}
