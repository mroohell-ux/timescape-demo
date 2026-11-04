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
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glyphHalo = 3f * root.resources.displayMetrics.density
    private val projection = Matrix()
    private val inverse = Matrix()
    private val sourceCorners = FloatArray(8)
    private val targetCorners = FloatArray(8)
    private val movingCorners = FloatArray(8)
    private val bottomPoint = FloatArray(2)
    private val layerBounds = RectF()
    private var ready = false
    internal var snapshotCount = 0
        private set
    internal var sharedRunCount = 0
        private set

    fun clear() {
        faces.values.forEach { it.release() }
        textViews.forEach { it.alpha = 1f }
        usable.clear()
        runs.clear()
        sharedRunCount = 0
        ready = false
    }

    fun prepare(progress: Float, media: Boolean): Boolean {
        if (media || progress <= 0f || progress >= 1f) {
            if (ready) clear()
            return false
        }
        usable.clear()
        faces.values.forEach {
            if (it.view.visibility != View.GONE && it.view.text.isNotEmpty()) usable.add(it)
        }
        if (usable.any { it.view.layout == null || it.view.width == 0 || it.view.height == 0 }) {
            if (ready) clear()
            return false
        }
        var changed = !ready
        for (face in usable) {
            if (face.ensureSnapshot()) {
                snapshotCount++
                changed = true
            }
        }
        if (changed) {
            runs.clear()
            faces.values.forEach {
                it.shared.reset()
                it.extraTarget = null
            }
            connect(reading[2], preview[2])
            connect(reading[1], preview[1])
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
        val matches = matchedCardTextOffsets(source.text, target.text, visibleEnd)
        if (matches.isEmpty() && normalizeCardText(source.text).text.isNotEmpty() &&
            !normalizeCardText(target.text).text.contains(normalizeCardText(source.text).text)
        ) {
            Log.w("TimescapeTextTransition", "Reading/preview text changed before their layouts matched")
        }
        var cursor = 0
        while (cursor < matches.size) {
            val sourceStart = matches[cursor]
            val targetStart = matches[cursor + 1]
            var sourceEnd = sourceStart + 1
            var targetEnd = targetStart + 1
            cursor += 2
            while (cursor < matches.size && matches[cursor] == sourceEnd &&
                matches[cursor + 1] == targetEnd &&
                sourceLayout.getLineForOffset(sourceEnd) == sourceLayout.getLineForOffset(sourceStart) &&
                targetLayout.getLineForOffset(targetEnd) == targetLayout.getLineForOffset(targetStart) &&
                sourceLayout.isRtlCharAt(sourceEnd) == sourceLayout.isRtlCharAt(sourceStart) &&
                targetLayout.isRtlCharAt(targetEnd) == targetLayout.isRtlCharAt(targetStart)
            ) {
                sourceEnd++
                targetEnd++
                cursor += 2
            }
            val sourceRect = from.textRect(sourceStart, sourceEnd)
            val targetRect = to.textRect(targetStart, targetEnd)
            if (sourceRect.width() <= 0f || targetRect.width() <= 0f) continue
            val sourceLine = from.lineRect(sourceStart)
            val targetLine = to.lineRect(targetStart)
            from.addMask(sourceRect, sourceLine)
            to.addMask(targetRect, targetLine)
            if (from.extraTarget == null) {
                from.extraTarget = to
                from.extraScale = targetRect.height() / sourceRect.height()
                from.extraX = targetRect.left - sourceRect.left * from.extraScale
                from.extraY = targetRect.top - sourceRect.top * from.extraScale
            }
            var tailEnd = sourceEnd
            if (cursor == matches.size) {
                while (tailEnd < source.text.length && !source.text[tailEnd].isWhitespace() &&
                    sourceLayout.getLineForOffset(tailEnd) == sourceLayout.getLineForOffset(sourceStart)
                ) tailEnd++
            }
            val tail = if (tailEnd > sourceEnd) from.textRect(sourceEnd, tailEnd) else null
            if (tail != null) from.addMask(tail, sourceLine)
            runs += Run(from, to, sourceRect, tail, sourceLine, targetLine,
                expandedStrip(sourceRect), expandedStrip(targetRect),
                FloatArray(8).also { corners(sourceRect, it) },
                FloatArray(8).also { corners(targetRect, it) })
        }
    }

    fun draw(canvas: Canvas, progress: Float) {
        if (!ready) return
        usable.forEach { it.updateMatrix() }
        val collapse = 1f - progress
        val material = smoothstep(collapse)
        reading.forEach {
            val face = faces.getValue(it)
            face.updateExtraMatrix(collapse)
            val alpha = if (it === reading[0]) metadataAlpha(progress) else smoothstep(progress)
            drawExtras(canvas, face, alpha, collapse)
        }
        preview.forEach {
            val alpha = if (it === preview[0]) metadataAlpha(collapse) else material
            drawExtras(canvas, faces.getValue(it), alpha, 0f)
        }

        var firstPoint = true
        for (run in runs) {
            run.source.matrix.mapPoints(sourceCorners, run.sourceCorners)
            run.target.matrix.mapPoints(targetCorners, run.targetCorners)
            for (index in run.corners.indices) {
                run.corners[index] = sourceCorners[index] +
                    (targetCorners[index] - sourceCorners[index]) * collapse
            }
            for (point in 0..3) {
                val x = run.corners[point * 2]
                val y = run.corners[point * 2 + 1]
                if (firstPoint) {
                    layerBounds.set(x, y, x, y)
                    firstPoint = false
                } else layerBounds.union(x, y)
            }
        }
        if (runs.isEmpty()) return
        // A partly ellipsized word keeps its fading suffix attached to the moving prefix.
        for (run in runs) {
            val tail = run.sourceTail ?: continue
            corners(run.sourceRect, sourceCorners)
            check(projection.setPolyToPoly(sourceCorners, 0, run.corners, 0, 4))
            paint.alpha = ((1f - material) * 255f).roundToInt()
            val saved = canvas.save()
            canvas.concat(projection)
            canvas.clipRect(tail)
            canvas.clipRect(run.sourceLine)
            canvas.drawBitmap(requireNotNull(run.source.bitmap), null, run.source.bounds, paint)
            canvas.restoreToCount(saved)
        }
        val halo = 4f * root.resources.displayMetrics.density
        layerBounds.inset(-halo, -halo)
        // Add the two materials on a transparent plane, avoiding a mid-crossfade opacity dip.
        val layer = canvas.saveLayer(layerBounds, null)
        paint.shader = null
        paint.blendMode = BlendMode.PLUS
        for (run in runs) {
            drawRun(canvas, run.source, run.sourceCorners, run.sourceClip, run.sourceLine, run.corners, 1f - material)
            drawRun(canvas, run.target, run.targetCorners, run.targetClip, run.targetLine, run.corners, material)
        }
        paint.blendMode = null
        canvas.restoreToCount(layer)
    }

    private fun expandedStrip(rect: RectF) = RectF(rect).apply {
        inset(-glyphHalo, 0f)
    }

    private fun drawRun(canvas: Canvas, face: Face, corners: FloatArray, clip: RectF,
        line: RectF, quad: FloatArray, alpha: Float
    ) {
        val opacity = (alpha * 255f).roundToInt()
        if (opacity <= 0) return
        val bitmap = face.bitmap ?: return
        check(projection.setPolyToPoly(corners, 0, quad, 0, 4)) { "Invalid shared text projection" }
        paint.alpha = opacity
        val saved = canvas.save()
        canvas.concat(projection)
        // Merging these clips changes edge rasterization under perspective.
        canvas.clipRect(clip)
        canvas.clipRect(line)
        canvas.drawBitmap(bitmap, null, face.bounds, paint)
        canvas.restoreToCount(saved)
    }

    private fun drawExtras(canvas: Canvas, face: Face, alpha: Float, collapse: Float) {
        val opacity = (alpha * 255f).roundToInt()
        if (opacity <= 0 || face.bitmap == null || face.view.visibility == View.GONE) return
        paint.alpha = opacity
        paint.blendMode = null
        val shader = requireNotNull(face.shader)
        val plane = if (face.extraTarget != null) face.extraMatrix else face.matrix
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
        val saved = canvas.save()
        canvas.concat(plane)
        canvas.clipOutPath(face.shared)
        canvas.drawRect(face.bounds, paint)
        canvas.restoreToCount(saved)
        paint.shader = null
    }

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
        val extraMatrix = Matrix()
        var extraTarget: Face? = null
        var extraScale = 1f
        var extraX = 0f
        var extraY = 0f
        val bounds = RectF()
        private var layout: Layout? = null
        private var color = 0
        private var depth = 0f

        fun ensureSnapshot(): Boolean {
            if (bitmap != null && layout === view.layout && color == view.currentTextColor &&
                depth == view.depthAmount && bounds.width() == view.width.toFloat() &&
                bounds.height() == view.height.toFloat()
            ) return false
            release()
            val scale = min(1f, min(1536f / max(view.width, view.height),
                sqrt(1_048_576f / (view.width.toFloat() * view.height))))
            val image = Bitmap.createBitmap(max(1, (view.width * scale).roundToInt()),
                max(1, (view.height * scale).roundToInt()), Bitmap.Config.ARGB_8888)
            val recording = Canvas(image)
            recording.scale(image.width.toFloat() / view.width, image.height.toFloat() / view.height)
            view.draw(recording)
            bitmap = image
            shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                val pixelsToView = Matrix()
                pixelsToView.setScale(view.width.toFloat() / image.width, view.height.toFloat() / image.height)
                setLocalMatrix(pixelsToView)
            }
            bounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
            layout = view.layout
            color = view.currentTextColor
            depth = view.depthAmount
            return true
        }

        fun updateMatrix() {
            matrix.reset()
            var descendant: View = view
            while (descendant !== root) {
                matrix.postConcat(descendant.matrix)
                val parent = descendant.parent as View
                matrix.postTranslate((descendant.left - parent.scrollX).toFloat(),
                    (descendant.top - parent.scrollY).toFloat())
                descendant = parent
            }
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

        fun addMask(rect: RectF, line: RectF) {
            val mask = RectF(rect)
            mask.inset(-3f * view.resources.displayMetrics.density, 0f)
            if (mask.intersect(line)) shared.addRect(mask, Path.Direction.CW)
        }

        fun updateExtraMatrix(collapse: Float) {
            val target = extraTarget ?: return
            corners(bounds, sourceCorners)
            matrix.mapPoints(sourceCorners)
            corners(bounds, targetCorners)
            for (point in 0..3) {
                targetCorners[point * 2] = targetCorners[point * 2] * extraScale + extraX
                targetCorners[point * 2 + 1] = targetCorners[point * 2 + 1] * extraScale + extraY
            }
            target.matrix.mapPoints(targetCorners)
            for (index in movingCorners.indices) {
                movingCorners[index] = sourceCorners[index] +
                    (targetCorners[index] - sourceCorners[index]) * collapse
            }
            corners(bounds, sourceCorners)
            check(extraMatrix.setPolyToPoly(sourceCorners, 0, movingCorners, 0, 4)) {
                "Invalid fading text projection"
            }
        }

        fun release() {
            bitmap?.recycle()
            bitmap = null
            shader = null
            layout = null
            shared.reset()
            extraTarget = null
        }
    }

    private class Run(
        val source: Face,
        val target: Face,
        val sourceRect: RectF,
        val sourceTail: RectF?,
        val sourceLine: RectF,
        val targetLine: RectF,
        val sourceClip: RectF,
        val targetClip: RectF,
        val sourceCorners: FloatArray,
        val targetCorners: FloatArray,
        val corners: FloatArray = FloatArray(8)
    )
}
