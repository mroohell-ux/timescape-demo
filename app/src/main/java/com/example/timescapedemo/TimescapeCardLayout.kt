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
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Separate overview and reading surfaces; media previews keep their source proportions. */
class TimescapeCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var expansionProgress = 0f
        private set
    var isMediaPreview = false
        private set
    private var isVideoPreview = false
    private var mediaAspect = 1.5f
    private var viewportCap = Int.MAX_VALUE
    val readingHeightCap: Int get() = viewportCap
    private val surfaceClip = Rect()
    internal var windAlpha = 1f
        private set
    private var compactHeight = 0
    private var intrinsicPreviewHeight = 0
    private var intrinsicPreviewWidth = -1
    private var readingHeaderHeight = 0
    private var readingHeaderWidth = 0
    private var readingMediaVerticalGap = 0f
    private var horizontalFlow = false
    private var cardTouchActive = false
    private var readingWidth = 0
    private var readingScale = 1f
    private var previewScale = 1f
    private var previewFaceInset = 0
    private var flipLifted = false
    private var restingElevation = 0f
    private val density = resources.displayMetrics.density
    private lateinit var previewText: View
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
    private val letteringFootPoints = FloatArray(6)
    private val letteringDrawBounds = RectF()
    private val letteringDrawMatrix = Matrix()
    private val windDrawBounds = RectF()
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
    internal val textTransitionPreparationCount: Int get() = textTransition.preparationCount
    internal val textTransitionInkScanCount: Int get() = textTransition.inkScanCount
    internal val sharedTextRunCount: Int get() = textTransition.sharedRunCount
    internal var measurePassCount = 0
        private set
    internal var previewIntrinsicMeasureCount = 0
        private set
    private val recordDiagnostics = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    init {
        // Hardware display lists must call draw() for whole-card wind opacity.
        setWillNotDraw(false)
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
        if (windAlpha != 1f) applyWindOpacity()
        if (windRenderCaching) setRenderCaching(renderCachingEnabled)
    }

    private fun updateSurfaceClip() {
        clipBounds = if ((!isMediaPreview && expansionProgress < 1f) ||
            (flipLifted && expansionProgress == 1f)
        ) null else surfaceClip
    }
    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int =
        childCount - 1 - drawingPosition

    fun configurePreview(media: Boolean, aspect: Float = 1.5f, video: Boolean = false) {
        if (isMediaPreview != media) textTransition.clear()
        if (isMediaPreview != media) intrinsicPreviewHeight = 0
        isMediaPreview = media
        isVideoPreview = video
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
        if (abs(signedDistance) > 2.25f) textTransition.trimInactiveSnapshots()
    }

    internal fun setHorizontalFlow(horizontal: Boolean) {
        if (horizontalFlow == horizontal) return
        horizontalFlow = horizontal
        cardFlip.gestureEnabled = !horizontal
        cardFlip.cancelInteraction()
        val full = getChildAt(0) as LinearLayout
        full.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        full.gravity = if (horizontal) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        val margins = collapseButton.layoutParams as LinearLayout.LayoutParams
        margins.gravity = if (horizontal) Gravity.CENTER_VERTICAL else Gravity.END
        margins.marginEnd = if (horizontal) (4f * density).roundToInt() else 0
        margins.bottomMargin = if (horizontal) 0 else (4f * density).roundToInt()
        collapseButton.layoutParams = margins
        updateCollapseDirection()
        textTransition.clear()
        requestLayout()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        if (::collapseButton.isInitialized) updateCollapseDirection()
    }

    private fun updateCollapseDirection() {
        collapseButton.rotation = if (horizontalFlow) {
            if (layoutDirection == LAYOUT_DIRECTION_RTL) 90f else -90f
        } else 0f
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
        val footX = if (horizontalFlow) {
            text.width * (.5f - letteringDepth.coerceIn(-1f, 1f) * .5f)
        } else text.width / 2f
        val footY = if (horizontalFlow) text.height / 2f else {
            (text.height - text.paddingBottom).coerceAtLeast(0).toFloat()
        }
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
            text.width, text.height, resources.displayMetrics.densityDpi,
            if (horizontalFlow) footX / text.width else .5f, if (horizontalFlow) .5f else 1f
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
        val parallax = letteringDepth.coerceIn(-2f, 2f) * 2.5f * density * away * scale
        val anchorX = letteringFootPoints[4] + if (horizontalFlow) 0f else parallax
        val anchorY = letteringFootPoints[5] - geometry.letteringLiftDp * density * away * scale +
            if (horizontalFlow) parallax else 0f

        letteringTargetWorld.reset()
        val facingPitch = geometry.letteringFacingPitchDegrees * away
        if (facingPitch != 0f) {
            letteringCamera.save()
            letteringCamera.setLocation(0f, 0f,
                -cameraDistance / resources.displayMetrics.densityDpi)
            if (horizontalFlow) letteringCamera.rotateY(-facingPitch)
            else letteringCamera.rotateX(facingPitch)
            letteringCamera.getMatrix(letteringTargetWorld)
            letteringCamera.restore()
        }
        letteringTargetWorld.preTranslate(-footX, -footY)
        letteringTargetWorld.postScale(scale, scale)
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

    internal fun setWindAlpha(value: Float) {
        require(value.isFinite() && value in 0f..1f)
        if (windAlpha == value) return
        windAlpha = value
        applyWindOpacity()
    }

    private fun applyWindOpacity() {
        setTransitionAlpha(if (isMediaPreview || flipLifted) windAlpha else 1f)
        if (!isMediaPreview && !flipLifted) invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (windAlpha == 0f && !isMediaPreview && !flipLifted) return
        if (windAlpha == 1f || isMediaPreview || flipLifted) {
            super.draw(canvas)
            return
        }
        // Native transition alpha rebuilds the whole perspective layer as lettering moves.
        windDrawBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        if (expansionProgress < 1f) {
            previewText.renderedMatrixToAncestor(this, letteringDrawMatrix)
            letteringDrawBounds.set(0f, 0f, previewText.width.toFloat(), previewText.height.toFloat())
            letteringDrawMatrix.mapRect(letteringDrawBounds)
            windDrawBounds.union(letteringDrawBounds)
        }
        cornerMatrix.mapRect(windDrawBounds)
        val saved = canvas.saveLayerAlpha(windDrawBounds, (windAlpha * 255f).roundToInt())
        try {
            super.draw(canvas)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child !== getChildAt(0) || isMediaPreview || expansionProgress == 1f) {
            return super.drawChild(canvas, child, drawingTime)
        }
        // Only the raised preview may leave the card; the growing reader keeps its viewport.
        val saved = canvas.save()
        try {
            canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
            return super.drawChild(canvas, child, drawingTime)
        } finally {
            canvas.restoreToCount(saved)
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
        if (isMediaPreview || expansionProgress == 1f) {
            canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        }
        super.dispatchDraw(canvas)
        if (drawTextTransition) textTransition.draw(canvas, expansionProgress)
        canvas.restoreToCount(saved)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && (horizontalFlow || !cornerMatrix.isIdentity) &&
            !containsCardPoint(event.x, event.y)
        ) return false
        if (horizontalFlow && event.actionMasked == MotionEvent.ACTION_DOWN) {
            cardTouchActive = true
            blockOuterPaging()
        }
        val localEvent = if (cornerMatrix.isIdentity) event else MotionEvent.obtain(event).apply {
            transform(inverseCornerMatrix)
        }
        return try {
            super.dispatchTouchEvent(localEvent)
        } finally {
            if (localEvent !== event) localEvent.recycle()
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                cardTouchActive = false
                if (horizontalFlow && event.actionMasked == MotionEvent.ACTION_UP) {
                    (parent as? RecyclerView)?.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
        if (horizontalFlow && cardTouchActive && !disallowIntercept) blockOuterPaging()
    }

    private fun blockOuterPaging() {
        // Readers may release the card RecyclerView, but must not release the outer Flow pager.
        (parent as? RecyclerView)?.parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateCornerMatrix()
    }

    private var renderCachingEnabled = false
    private var windRenderCaching = false

    internal fun setWindRenderCaching(enabled: Boolean) {
        if (windRenderCaching == enabled) return
        windRenderCaching = enabled
        setRenderCaching(renderCachingEnabled)
    }

    internal fun setRenderCaching(enabled: Boolean) {
        renderCachingEnabled = enabled
        val mediaType = if (enabled && windRenderCaching && isMediaPreview && !isVideoPreview && !flipLifted &&
            (expansionProgress == 0f || expansionProgress == 1f)
        ) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (layerType != mediaType) setLayerType(mediaType, null)
        val type = if (enabled && expansionProgress == 0f && !isMediaPreview) {
            View.LAYER_TYPE_HARDWARE
        } else View.LAYER_TYPE_NONE
        if (previewFace.layerType != type) previewFace.setLayerType(type, null)
        val textType = if (enabled && !isMediaPreview && expansionProgress < .18f) {
            View.LAYER_TYPE_HARDWARE
        } else View.LAYER_TYPE_NONE
        if (previewText.layerType != textType) previewText.setLayerType(textType, null)
        val readingType = if (enabled && !isMediaPreview &&
            expansionProgress > 0f && (expansionProgress < 1f || windRenderCaching)
        ) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (readingFace.layerType != readingType) readingFace.setLayerType(readingType, null)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        check(childCount == 2) { "TimescapeCardLayout needs reading and preview surfaces" }
        previewText = findViewById(R.id.previewText)
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
        if (expansionProgress == 0f || expansionProgress == 1f) {
            textTransition.clear(releaseSnapshots = false)
        }
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
        val margins = collapseButton.layoutParams as MarginLayoutParams
        val sidebarWidth = if (horizontalFlow) {
            collapseButton.layoutParams.width + margins.marginStart + margins.marginEnd
        } else 0
        readingScale = availableReadingWidth.toFloat() / fullWidth
        readingHeaderWidth = (sidebarWidth * readingScale * expansionProgress).roundToInt()
        readingMediaVerticalGap = 0f
        if (horizontalFlow) readingHeaderHeight = 0
        var availablePreviewWidth = (width - readingHeaderWidth).coerceAtLeast(1)
        var previewWidth = availablePreviewWidth
        if (isMediaPreview) {
            val heightLimit = min(viewportCap * 0.62f, width * 0.72f)
            previewWidth = (fullWidth - sidebarWidth).coerceAtLeast(1)
            val previewHeight = (previewWidth / mediaAspect).roundToInt().coerceAtLeast(1)
            preview.measure(
                MeasureSpec.makeMeasureSpec(previewWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(previewHeight, MeasureSpec.EXACTLY)
            )
            previewScale = min(availablePreviewWidth.toFloat() / previewWidth, heightLimit / previewHeight)
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
            val headerHeight = if (horizontalFlow) 0 else {
                collapse.measuredHeight + margins.topMargin + margins.bottomMargin
            }
            if (isMediaPreview) {
                // Keep one surface scale; scrollable media shortens the viewport, not the source.
                val bodyHeight = (if (horizontalFlow) cardFlip.measuredHeight
                    else full.measuredHeight - headerHeight).coerceAtLeast(1)
                if (!scrollMedia) readingScale *= min(1f, viewportCap.toFloat() / bodyHeight)
                val naturalBodyHeight = if (scrollMedia) {
                    bodyHeight + reader.naturalHeight - reader.measuredHeight
                } else bodyHeight
                val compactScale = compactHeight.toFloat() / naturalBodyHeight.coerceAtLeast(1)
                readingScale = compactScale + (readingScale - compactScale) * expansionProgress
                mediaBodyHeight = (bodyHeight * readingScale).roundToInt()
                if (horizontalFlow) {
                    readingMediaVerticalGap = (full.measuredHeight - bodyHeight).coerceAtLeast(0) * readingScale
                }
                if (scrollMedia || horizontalFlow) {
                    preview.measure(
                        MeasureSpec.makeMeasureSpec(previewWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(bodyHeight, MeasureSpec.EXACTLY)
                    )
                }
            }
            readingHeaderWidth = (sidebarWidth * readingScale * expansionProgress).roundToInt()
            availablePreviewWidth = (width - readingHeaderWidth).coerceAtLeast(1)
            readingHeaderHeight = (headerHeight * readingScale).roundToInt()
        }
        val target = if (expansionProgress > 0f) (full.measuredHeight * readingScale).roundToInt() else compactHeight
        val height = if (isMediaPreview && expansionProgress > 0f) {
            mediaBodyHeight + ((readingHeaderHeight + readingMediaVerticalGap) * expansionProgress).roundToInt()
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
        previewScale = if (isMediaPreview && horizontalFlow && expansionProgress > 0f) readingScale else if (isMediaPreview) {
            val bodyHeight = (height - (readingHeaderHeight * expansionProgress).roundToInt()).coerceAtLeast(1)
            min(availablePreviewWidth.toFloat() / previewWidth, bodyHeight.toFloat() / preview.measuredHeight)
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
                if (isMediaPreview) {
                    val margins = collapseButton.layoutParams as MarginLayoutParams
                    val sidebarShift = if (horizontalFlow) {
                        (collapseButton.measuredWidth + margins.marginStart + margins.marginEnd) *
                            scale * (1f - expansionProgress) / 2f
                    } else 0f
                    ((measuredWidth - child.measuredWidth * scale) / 2f +
                        if (layoutDirection == LAYOUT_DIRECTION_RTL) sidebarShift else -sidebarShift).roundToInt()
                }
                else if (layoutDirection == LAYOUT_DIRECTION_RTL) 0 else previewFaceInset
            } else {
                val sidebar = if (layoutDirection == LAYOUT_DIRECTION_RTL) 0 else readingHeaderWidth
                sidebar + ((measuredWidth - readingHeaderWidth - child.measuredWidth * scale) / 2f).roundToInt()
            }
            val y = if (index == 0) {
                -((readingHeaderHeight + readingMediaVerticalGap / 2f) * (1f - expansionProgress)).roundToInt()
            } else {
                ((readingHeaderHeight + readingMediaVerticalGap / 2f) * expansionProgress).roundToInt()
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
