package com.example.timescapedemo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/** Separate overview and reading surfaces; media previews keep their source proportions. */
class TimescapeCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var expansionProgress = 0f
        private set
    var isMediaPreview = false
        private set
    private var mediaAspect = 1.5f
    private var viewportCap = Int.MAX_VALUE
    val readingHeightCap: Int get() = viewportCap
    private val surfaceClip = Rect()
    private var compactHeight = 0
    private var intrinsicPreviewHeight = 0
    private var intrinsicPreviewWidth = -1
    private var readingHeaderHeight = 0
    private var readingWidth = 0
    private var readingScale = 1f
    private var previewScale = 1f
    private var previewFaceInset = 0
    private var flipLifted = false
    private var restingElevation = 0f
    private val density = resources.displayMetrics.density
    private lateinit var previewText: View
    private lateinit var previewLettering: Array<TimescapeTextView>
    private lateinit var previewFace: View
    private lateinit var previewIcon: View
    private lateinit var readingFace: AspectRatioCardView
    private lateinit var readingImage: View
    private lateinit var readingBackground: View
    private lateinit var imageReader: ImageCardScrollView
    private lateinit var handwritingReader: ImageCardScrollView
    private lateinit var textReader: CardContentScrollView
    private lateinit var previewImage: ImageView
    private val previewImageMatrix = Matrix()
    private val cornerMatrix = Matrix()
    private val inverseCornerMatrix = Matrix()
    private val cornerSource = FloatArray(8)
    private val cornerTarget = FloatArray(8)
    private val normalizedCorners = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    private val cornerHitPoint = FloatArray(2)
    private var letteringGeometry = CardFlowGeometry()
    private var letteringDepth = 0f
    private var letteringCameraDistance = Float.NaN
    private val letteringCamera = Camera()
    private val letteringCardWorld = Matrix()
    private val letteringParentWorld = Matrix()
    private val letteringNaturalWorld = Matrix()
    private val letteringTargetWorld = Matrix()
    private val letteringInverseParent = Matrix()
    private val letteringProjection = Matrix()
    private val appliedLetteringProjection = Matrix()
    private val letteringCardCorners = FloatArray(8)
    private val letteringInkCorners = FloatArray(8)
    private val letteringFootPoints = FloatArray(6)
    private val letteringInkBounds = RectF()
    private val letteringGlyphBounds = RectF()
    private var letteringProjectionApplied = false
    private var cornerWidth = -1
    private var cornerHeight = -1
    private lateinit var readingHandwriting: View
    private val activeMediaReader: ImageCardScrollView
        get() = if (readingHandwriting.visibility == VISIBLE) handwritingReader else imageReader
    private lateinit var cardFlip: GestureCardFlipLayout
    private lateinit var collapseButton: View
    private lateinit var textTransition: CardTextTransition
    private var accessibilityExpanded: Boolean? = null
    val previewHeight: Int get() = compactHeight
    internal val textTransitionSnapshotCount: Int get() = textTransition.snapshotCount
    internal val sharedTextRunCount: Int get() = textTransition.sharedRunCount
    internal var measurePassCount = 0
        private set
    internal var previewIntrinsicMeasureCount = 0
        private set
    private val recordDiagnostics = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    init {
        isChildrenDrawingOrderEnabled = true
        clipChildren = false
        clipToPadding = false
    }

    internal fun setFlipLifted(lifted: Boolean) {
        if (flipLifted == lifted) return
        if (lifted) restingElevation = elevation
        flipLifted = lifted
        elevation = if (lifted) 24f * resources.displayMetrics.density else restingElevation
        updateSurfaceClip()
    }

    private fun updateSurfaceClip() {
        clipBounds = if (flipLifted && expansionProgress == 1f) null else surfaceClip
    }
    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int =
        childCount - 1 - drawingPosition

    fun configurePreview(media: Boolean, aspect: Float = 1.5f) {
        if (isMediaPreview != media) textTransition.clear()
        if (isMediaPreview != media) intrinsicPreviewHeight = 0
        isMediaPreview = media
        mediaAspect = aspect.takeIf { it.isFinite() && it > 0f } ?: 1.5f
        updatePreviewInset()
        requestLayout()
    }

    fun setViewportCap(cap: Int) {
        if (viewportCap == cap) return
        viewportCap = cap.coerceAtLeast(1)
        requestLayout()
    }

    fun setExpansion(progress: Float) {
        val next = progress.coerceIn(0f, 1f)
        if (next == expansionProgress) return
        expansionProgress = next
        updateSurfaces()
        requestLayout()
        cardFlip.updateEligibility()
    }

    fun setDepth(signedDistance: Float, geometry: CardFlowGeometry) {
        letteringDepth = signedDistance
        letteringGeometry = geometry
        updateLetteringProjection()
    }

    internal fun updateLetteringProjection() {
        if (!::previewText.isInitialized) return
        val text = previewText
        if (isMediaPreview || expansionProgress == 1f || width == 0 || height == 0 ||
            text.width == 0 || text.height == 0
        ) {
            if (letteringProjectionApplied) {
                text.setAnimationMatrix(null)
                letteringProjectionApplied = false
            }
            return
        }
        val geometry = letteringGeometry
        val away = 1f - expansionProgress
        letteringInkBounds.setEmpty()
        for (glyphs in previewLettering) {
            if (glyphs.visibility == GONE || glyphs.text.isEmpty()) continue
            if (glyphs.layout == null || glyphs.width == 0 || glyphs.height == 0) return
            glyphs.renderedGlyphBounds(text, letteringGlyphBounds, letteringProjection)
            letteringInkBounds.union(letteringGlyphBounds)
        }
        if (letteringInkBounds.isEmpty) return
        val footX = text.width / 2f
        val footY = (text.height - text.paddingBottom).coerceAtLeast(0).toFloat()
        text.pivotX = footX
        text.pivotY = footY
        text.rotationX = 0f
        text.rotationY = 0f
        text.rotation = 0f
        text.scaleX = 1f
        text.scaleY = 1f
        text.translationX = 0f
        text.translationY = 0f
        val cameraDistance = geometry.cameraDistance(
            text.width, text.height, resources.displayMetrics.densityDpi, .5f, 1f
        )
        if (letteringCameraDistance != cameraDistance) {
            text.cameraDistance = cameraDistance
            letteringCameraDistance = cameraDistance
        }
        text.translationZ = geometry.letteringLiftDp * density * away

        renderedMatrixToAncestor(null, letteringCardWorld)
        letteringCardWorld.preConcat(cornerMatrix)
        val surface = getChildAt(1)
        letteringParentWorld.set(letteringCardWorld)
        letteringParentWorld.preTranslate((surface.left - scrollX).toFloat(),
            (surface.top - scrollY).toFloat())
        letteringParentWorld.preConcat(surface.matrix)
        letteringParentWorld.preTranslate((text.left - surface.scrollX).toFloat(),
            (text.top - surface.scrollY).toFloat())

        val fit = measuredWidth.toFloat() / text.width
        letteringNaturalWorld.set(letteringParentWorld)
        letteringNaturalWorld.preScale(fit, fit, 0f, footY)
        letteringFootPoints[0] = 0f
        letteringFootPoints[1] = footY
        letteringFootPoints[2] = text.width.toFloat()
        letteringFootPoints[3] = footY
        letteringFootPoints[4] = footX
        letteringFootPoints[5] = footY
        letteringNaturalWorld.mapPoints(letteringFootPoints)
        val scale = hypot(letteringFootPoints[2] - letteringFootPoints[0],
            letteringFootPoints[3] - letteringFootPoints[1]) / text.width
        val anchorX = letteringFootPoints[4] +
            letteringDepth.coerceIn(-2f, 2f) * 2.5f * density * away * scale
        val anchorY = letteringFootPoints[5] - geometry.letteringLiftDp * density * away * scale

        letteringTargetWorld.reset()
        val facingPitch = geometry.letteringFacingPitchDegrees * away
        if (facingPitch != 0f) {
            letteringCamera.save()
            letteringCamera.setLocation(0f, 0f,
                -cameraDistance / resources.displayMetrics.densityDpi)
            letteringCamera.rotateX(facingPitch)
            letteringCamera.getMatrix(letteringTargetWorld)
            letteringCamera.restore()
        }
        letteringTargetWorld.preTranslate(-footX, -footY)
        val halo = 3f * density
        letteringInkCorners[0] = letteringInkBounds.left - halo
        letteringInkCorners[1] = letteringInkBounds.top - halo
        letteringInkCorners[2] = letteringInkBounds.right + halo
        letteringInkCorners[3] = letteringInkCorners[1]
        letteringInkCorners[4] = letteringInkCorners[2]
        letteringInkCorners[5] = letteringInkBounds.bottom + 6f * density
        letteringInkCorners[6] = letteringInkCorners[0]
        letteringInkCorners[7] = letteringInkCorners[5]
        letteringTargetWorld.mapPoints(letteringInkCorners)
        val fittedScale = fitLetteringScale(anchorX, anchorY, scale)
        letteringTargetWorld.postScale(fittedScale, fittedScale)
        letteringTargetWorld.postTranslate(anchorX, anchorY)

        // Cancel the complete card/ancestor projection, not just its Euler angles.
        check(letteringParentWorld.invert(letteringInverseParent)) { "Lettering parent projection is not invertible" }
        letteringProjection.setConcat(letteringInverseParent, letteringTargetWorld)
        if (!letteringProjectionApplied || appliedLetteringProjection != letteringProjection) {
            appliedLetteringProjection.set(letteringProjection)
            text.setAnimationMatrix(letteringProjection)
            letteringProjectionApplied = true
            invalidate()
        }
    }

    private fun fitLetteringScale(anchorX: Float, anchorY: Float, scale: Float): Float {
        letteringCardCorners[0] = 0f
        letteringCardCorners[1] = 0f
        letteringCardCorners[2] = width.toFloat()
        letteringCardCorners[3] = 0f
        letteringCardCorners[4] = width.toFloat()
        letteringCardCorners[5] = height.toFloat()
        letteringCardCorners[6] = 0f
        letteringCardCorners[7] = height.toFloat()
        letteringCardWorld.mapPoints(letteringCardCorners)
        var area = 0f
        for (corner in 0..3) {
            val next = (corner + 1) % 4
            area += letteringCardCorners[corner * 2] * letteringCardCorners[next * 2 + 1] -
                letteringCardCorners[next * 2] * letteringCardCorners[corner * 2 + 1]
        }
        check(abs(area) > .01f) { "Card projection has no area for standing lettering" }
        val orientation = sign(area)
        var fitted = scale
        for (edge in 0..3) {
            val next = (edge + 1) % 4
            val x = letteringCardCorners[edge * 2]
            val y = letteringCardCorners[edge * 2 + 1]
            val dx = letteringCardCorners[next * 2] - x
            val dy = letteringCardCorners[next * 2 + 1] - y
            val margin = ((dx * (anchorY - y) - dy * (anchorX - x)) * orientation).coerceAtLeast(0f)
            for (corner in 0..3) {
                val slope = (dx * letteringInkCorners[corner * 2 + 1] -
                    dy * letteringInkCorners[corner * 2]) * orientation
                if (slope < 0f) fitted = min(fitted, margin / -slope)
            }
        }
        check(fitted.isFinite() && fitted > 0f) { "Standing lettering cannot fit inside the card projection" }
        return fitted
    }

    internal fun setReadingWidth(width: Int) {
        if (readingWidth == width) return
        readingWidth = width
        requestLayout()
    }

    internal fun setOverviewWidth(width: Int) {
        // Reserve projected trailing space without rewrapping the summary's original text width.
        val textWidth = width + (16f * density).roundToInt()
        val params = previewText.layoutParams
        if (params.width == textWidth) return
        params.width = textWidth
        previewText.layoutParams = params
    }

    internal fun setCornerMorph(corners: FloatArray) {
        require(corners.size == 8) { "Card morph needs four corner coordinates" }
        if (normalizedCorners.contentEquals(corners) && cornerWidth == width && cornerHeight == height) return
        corners.copyInto(normalizedCorners)
        updateCornerMatrix()
    }

    private fun updateCornerMatrix() {
        cornerWidth = width
        cornerHeight = height
        if (width == 0 || height == 0) {
            cornerMatrix.reset()
            inverseCornerMatrix.reset()
        } else {
            for (corner in 0..3) {
                cornerSource[corner * 2] = if (corner == 0 || corner == 3) 0f else width.toFloat()
                cornerSource[corner * 2 + 1] = if (corner < 2) 0f else height.toFloat()
                cornerTarget[corner * 2] = normalizedCorners[corner * 2] * width
                cornerTarget[corner * 2 + 1] = normalizedCorners[corner * 2 + 1] * height
            }
            check(cornerMatrix.setPolyToPoly(cornerSource, 0, cornerTarget, 0, 4)) {
                "Invalid card corner projection: ${normalizedCorners.toList()}"
            }
            check(cornerMatrix.invert(inverseCornerMatrix)) { "Card corner projection is not invertible" }
        }
        invalidate()
    }

    internal fun mapCornerMorph(points: FloatArray) = cornerMatrix.mapPoints(points)

    internal fun containsCardPoint(x: Float, y: Float): Boolean {
        cornerHitPoint[0] = x
        cornerHitPoint[1] = y
        inverseCornerMatrix.mapPoints(cornerHitPoint)
        val localX = cornerHitPoint[0]
        val localY = cornerHitPoint[1]
        if (!localX.isFinite() || !localY.isFinite() ||
            localX < 0f || localX >= width || localY < 0f || localY >= height
        ) return false
        if (!isMediaPreview) return true
        return (0 until childCount).any { index ->
            val surface = getChildAt(index)
            surface.visibility == VISIBLE && surface.alpha > 0f &&
                localX >= surface.left && localX < surface.left + surface.width * surface.scaleX &&
                localY >= surface.top && localY < surface.top + surface.height * surface.scaleY
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        updateLetteringProjection()
        val drawTextTransition = textTransition.prepare(expansionProgress, isMediaPreview)
        if (cornerMatrix.isIdentity) {
            super.dispatchDraw(canvas)
            if (drawTextTransition) textTransition.draw(canvas, expansionProgress)
            return
        }
        val saved = canvas.save()
        canvas.concat(cornerMatrix)
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        super.dispatchDraw(canvas)
        if (drawTextTransition) textTransition.draw(canvas, expansionProgress)
        canvas.restoreToCount(saved)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (cornerMatrix.isIdentity) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !containsCardPoint(event.x, event.y)) return false
        val localEvent = MotionEvent.obtain(event)
        localEvent.transform(inverseCornerMatrix)
        return try {
            super.dispatchTouchEvent(localEvent)
        } finally {
            localEvent.recycle()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateCornerMatrix()
    }

    internal fun setRenderCaching(enabled: Boolean) {
        val type = if (enabled && expansionProgress == 0f && !isMediaPreview) {
            View.LAYER_TYPE_HARDWARE
        } else View.LAYER_TYPE_NONE
        if (previewFace.layerType != type) previewFace.setLayerType(type, null)
        val textType = if (enabled && !isMediaPreview && expansionProgress < .18f) {
            View.LAYER_TYPE_HARDWARE
        } else View.LAYER_TYPE_NONE
        if (previewText.layerType != textType) previewText.setLayerType(textType, null)
        val readingType = if (enabled && !isMediaPreview &&
            expansionProgress > 0f && expansionProgress < 1f
        ) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (readingFace.layerType != readingType) readingFace.setLayerType(readingType, null)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        check(childCount == 2) { "TimescapeCardLayout needs reading and preview surfaces" }
        previewText = findViewById(R.id.previewText)
        previewLettering = arrayOf(findViewById(R.id.previewTitle), findViewById(R.id.previewSnippet))
        previewFace = findViewById(R.id.cardPreview)
        previewIcon = findViewById(R.id.previewTypeIcon)
        readingFace = findViewById(R.id.card)
        readingImage = findViewById(R.id.imageCard)
        readingBackground = findViewById(R.id.bgImage)
        imageReader = findViewById(R.id.imageCardScroll)
        handwritingReader = findViewById(R.id.handwritingScroll)
        textReader = findViewById(R.id.cardTextScroll)
        previewImage = findViewById(R.id.previewImage)
        readingHandwriting = findViewById(R.id.handwritingImage)
        cardFlip = findViewById(R.id.cardFlip)
        collapseButton = findViewById(R.id.buttonCollapseCard)
        textTransition = CardTextTransition(this)
        previewText.cameraDistance = 1500f * density
        updateSurfaces()
    }

    private fun updateSurfaces() {
        if (childCount != 2) return
        updatePreviewInset()
        val full = getChildAt(0)
        val preview = getChildAt(1)
        full.visibility = if (expansionProgress > 0f) VISIBLE else INVISIBLE
        preview.visibility = if (expansionProgress < 1f) VISIBLE else INVISIBLE
        full.alpha = ((expansionProgress - 0.18f) / 0.65f).coerceIn(0f, 1f)
        // Keep a physical card face underneath the reading surface throughout a slow drag.
        preview.alpha = 1f
        // Shared glyphs are drawn once on a continuous plane during the text transition.
        previewText.alpha = 1f
        if (expansionProgress == 0f || expansionProgress == 1f) textTransition.clear()
        previewIcon.alpha = (1f - expansionProgress / 0.18f).coerceIn(0f, 1f)
        updateBackgroundSurfaces()
        collapseButton.alpha =
            ((expansionProgress - 0.85f) / 0.15f).coerceIn(0f, 1f)
        full.importantForAccessibility = if (expansionProgress == 1f) {
            IMPORTANT_FOR_ACCESSIBILITY_AUTO
        } else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        preview.importantForAccessibility = if (expansionProgress == 0f) {
            IMPORTANT_FOR_ACCESSIBILITY_AUTO
        } else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val expanded = expansionProgress > 0f
        if (accessibilityExpanded != expanded) {
            accessibilityExpanded = expanded
            ViewCompat.setStateDescription(this, context.getString(
                if (expanded) R.string.card_state_expanded else R.string.card_state_collapsed
            ))
        }
    }

    private fun updateBackgroundSurfaces() {
        // Two translucent copies become more opaque during a crossfade.
        val readingOwnsBackground = isMediaPreview || expansionProgress == 1f
        readingBackground.alpha = if (readingOwnsBackground) 1f else 0f
        val edgeAlpha = if (readingOwnsBackground) 255 else 0
        readingFace.foreground?.let { if (it.alpha != edgeAlpha) it.alpha = edgeAlpha }
    }

    private fun updatePreviewInset() {
        previewFaceInset = if (isMediaPreview) 0 else {
            (18f * density * (1f - expansionProgress)).roundToInt()
        }
        val face = previewFace
        val margins = face.layoutParams as LayoutParams
        if (margins.marginStart != previewFaceInset) {
            margins.marginStart = previewFaceInset
            face.layoutParams = margins
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (recordDiagnostics) measurePassCount++
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val preview = getChildAt(1)
        val availableReadingWidth = (width - previewFaceInset).coerceAtLeast(1)
        val fullWidth = if (readingWidth > 0) readingWidth else availableReadingWidth
        var previewWidth = width
        if (isMediaPreview) {
            val heightLimit = min(viewportCap * 0.62f, width * 0.72f)
            previewWidth = fullWidth
            val previewHeight = (previewWidth / mediaAspect).roundToInt().coerceAtLeast(1)
            preview.measure(
                MeasureSpec.makeMeasureSpec(previewWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(previewHeight, MeasureSpec.EXACTLY)
            )
            previewScale = min(width.toFloat() / previewWidth, heightLimit / previewHeight)
        } else {
            val textWidth = previewText.layoutParams.width.takeIf { it > 0 } ?: previewWidth
            // The text plane has a fixed width even while the photographic face grows.
            if (intrinsicPreviewHeight == 0 || intrinsicPreviewWidth != textWidth ||
                previewText.isLayoutRequested
            ) {
                preview.measure(
                    MeasureSpec.makeMeasureSpec(previewWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                intrinsicPreviewHeight = preview.measuredHeight
                intrinsicPreviewWidth = textWidth
                if (recordDiagnostics) previewIntrinsicMeasureCount++
            }
        }
        compactHeight = if (isMediaPreview) {
            (preview.measuredHeight * previewScale).roundToInt().coerceAtLeast(1)
        } else intrinsicPreviewHeight
        val full = getChildAt(0)
        readingScale = availableReadingWidth.toFloat() / fullWidth
        var mediaBodyHeight = 0
        val reader = activeMediaReader
        val scrollMedia = isMediaPreview && reader.usesWidthFitViewport &&
            (readingImage.visibility == VISIBLE || readingHandwriting.visibility == VISIBLE)
        val nativeCap = (viewportCap / readingScale).roundToInt()
        imageReader.setReadingViewport(nativeCap, expansionProgress)
        handwritingReader.setReadingViewport(nativeCap, expansionProgress)
        textReader.setReadingViewport(nativeCap, expansionProgress)
        readingFace.measureMediaAtNaturalSize =
            readingImage.visibility == VISIBLE || readingHandwriting.visibility == VISIBLE
        if (expansionProgress > 0f) {
            full.measure(
                MeasureSpec.makeMeasureSpec(fullWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val collapse = collapseButton
            val margins = collapse.layoutParams as MarginLayoutParams
            val headerHeight = collapse.measuredHeight + margins.topMargin + margins.bottomMargin
            if (isMediaPreview) {
                // Keep one surface scale; scrollable media shortens the viewport, not the source.
                val bodyHeight = (full.measuredHeight - headerHeight).coerceAtLeast(1)
                if (!scrollMedia) readingScale *= min(1f, viewportCap.toFloat() / bodyHeight)
                val naturalBodyHeight = if (scrollMedia) {
                    bodyHeight + reader.naturalHeight - reader.measuredHeight
                } else bodyHeight
                val compactScale = compactHeight.toFloat() / naturalBodyHeight.coerceAtLeast(1)
                readingScale = compactScale + (readingScale - compactScale) * expansionProgress
                mediaBodyHeight = (bodyHeight * readingScale).roundToInt()
                if (scrollMedia) {
                    preview.measure(
                        MeasureSpec.makeMeasureSpec(previewWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(bodyHeight, MeasureSpec.EXACTLY)
                    )
                }
            }
            readingHeaderHeight = (headerHeight * readingScale).roundToInt()
        }
        val target = if (expansionProgress > 0f) (full.measuredHeight * readingScale).roundToInt() else compactHeight
        val height = if (isMediaPreview && expansionProgress > 0f) {
            mediaBodyHeight + (readingHeaderHeight * expansionProgress).roundToInt()
        } else (compactHeight + (target - compactHeight) * expansionProgress).roundToInt()
        if (expansionProgress < 1f && !isMediaPreview) {
            // Resolve the photograph's match-parent height after measuring the text plane.
            preview.measure(
                MeasureSpec.makeMeasureSpec(previewWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(
                    (height - (readingHeaderHeight * expansionProgress).roundToInt()).coerceAtLeast(1),
                    MeasureSpec.EXACTLY
                )
            )
        }
        previewScale = if (isMediaPreview) {
            val bodyHeight = (height - (readingHeaderHeight * expansionProgress).roundToInt()).coerceAtLeast(1)
            min(width.toFloat() / previewWidth, bodyHeight.toFloat() / preview.measuredHeight)
        } else 1f
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        surfaceClip.set(0, 0, measuredWidth, measuredHeight)
        updateSurfaceClip()
        for (index in 0 until childCount) {
            // The collapsed reader is unmeasured; layout would consume its pending remeasure.
            if (index == 0 && expansionProgress == 0f) continue
            val child = getChildAt(index)
            val scale = if (index == 0) readingScale else previewScale
            child.pivotX = 0f
            child.pivotY = 0f
            child.scaleX = scale
            child.scaleY = scale
            val x = if (index == 0) {
                if (isMediaPreview) ((measuredWidth - child.measuredWidth * scale) / 2f).roundToInt()
                else if (layoutDirection == LAYOUT_DIRECTION_RTL) 0 else previewFaceInset
            } else ((measuredWidth - child.measuredWidth * scale) / 2f).roundToInt()
            val y = if (index == 0) {
                -(readingHeaderHeight * (1f - expansionProgress)).roundToInt()
            } else {
                (readingHeaderHeight * expansionProgress).roundToInt()
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        previewText.pivotY = (previewText.height - previewText.paddingBottom).coerceAtLeast(0).toFloat()
        previewText.pivotX = previewText.width / 2f
        if (isMediaPreview) {
            if (activeMediaReader.usesWidthFitViewport && expansionProgress > 0f && previewImage.drawable != null) {
                previewImage.scaleType = ImageView.ScaleType.MATRIX
                val imageWidth = previewImage.drawable.intrinsicWidth.coerceAtLeast(1)
                val imageScale = previewImage.width.toFloat() / imageWidth
                previewImageMatrix.setScale(imageScale, imageScale)
                previewImageMatrix.postTranslate(0f, -activeMediaReader.scrollY.toFloat())
                previewImage.imageMatrix = previewImageMatrix
            } else previewImage.scaleType = ImageView.ScaleType.FIT_CENTER
        }
        previewText.scaleX = 1f
        previewText.scaleY = 1f
        updateLetteringProjection()
        updateBackgroundSurfaces()
        // Hide the original glyphs before child hardware layers record this frame.
        textTransition.prepare(expansionProgress, isMediaPreview)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        expansionProgress in 0.001f..0.999f || super.onInterceptTouchEvent(event)

    override fun onDetachedFromWindow() {
        FlowWindPageTransformer.clearCard(this)
        if (letteringProjectionApplied) {
            previewText.setAnimationMatrix(null)
            letteringProjectionApplied = false
        }
        textTransition.clear()
        setRenderCaching(false)
        super.onDetachedFromWindow()
    }
}
