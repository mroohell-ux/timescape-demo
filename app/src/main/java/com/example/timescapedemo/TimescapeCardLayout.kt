package com.example.timescapedemo

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
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
    private var mediaAspect = 1.5f
    private var viewportCap = Int.MAX_VALUE
    private val surfaceClip = Rect()
    private var compactHeight = 0
    private var readingHeaderHeight = 0
    private var previewFaceInset = 0
    val previewHeight: Int get() = compactHeight

    init {
        isChildrenDrawingOrderEnabled = true
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int =
        childCount - 1 - drawingPosition

    fun configurePreview(media: Boolean, aspect: Float = 1.5f) {
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
    }

    fun setDepth(signedDistance: Float) {
        val text = findViewById<View>(R.id.previewText)
        val lift = (1f - expansionProgress) * resources.displayMetrics.density
        // Text occupies a slightly raised plane, with parallax relative to the photograph.
        text.translationX = signedDistance.coerceIn(-2f, 2f) * 2.5f * lift
        text.translationY = -5f * lift
        text.cameraDistance = 1500f * resources.displayMetrics.density
        // Counter the card's backward tilt, hinging the lettering toward the viewer.
        text.pivotY = (text.height - text.paddingBottom).coerceAtLeast(0).toFloat()
        text.rotationX = -rotationX - 3f * (1f - expansionProgress)
        text.rotationY = 2f * (1f - expansionProgress)
        text.translationZ = 12f * lift
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        check(childCount == 2) { "TimescapeCardLayout needs reading and preview surfaces" }
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
        // Retire overview lettering before reading text appears, avoiding double text during drags.
        findViewById<View>(R.id.previewText).alpha = (1f - expansionProgress / 0.18f).coerceIn(0f, 1f)
        findViewById<View>(R.id.previewTypeIcon).alpha = (1f - expansionProgress / 0.18f).coerceIn(0f, 1f)
        findViewById<View>(R.id.buttonCollapseCard).alpha =
            ((expansionProgress - 0.85f) / 0.15f).coerceIn(0f, 1f)
        full.importantForAccessibility = if (expansionProgress == 1f) {
            IMPORTANT_FOR_ACCESSIBILITY_AUTO
        } else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        preview.importantForAccessibility = if (expansionProgress == 0f) {
            IMPORTANT_FOR_ACCESSIBILITY_AUTO
        } else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        ViewCompat.setStateDescription(this, context.getString(
            if (expansionProgress == 0f) R.string.card_state_collapsed else R.string.card_state_expanded
        ))
    }

    private fun updatePreviewInset() {
        previewFaceInset = if (isMediaPreview) 0 else {
            (18f * resources.displayMetrics.density * (1f - expansionProgress)).roundToInt()
        }
        val face = findViewById<View>(R.id.cardPreview)
        val margins = face.layoutParams as LayoutParams
        if (margins.marginStart != previewFaceInset) {
            margins.marginStart = previewFaceInset
            face.layoutParams = margins
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val preview = getChildAt(1)
        var previewWidth = width
        if (isMediaPreview) {
            val heightLimit = min(viewportCap * 0.62f, width * 0.72f)
            previewWidth = min(width.toFloat(), heightLimit * mediaAspect).roundToInt().coerceAtLeast(1)
            preview.measure(
                MeasureSpec.makeMeasureSpec(previewWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((previewWidth / mediaAspect).roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY)
            )
        } else {
            preview.measure(
                MeasureSpec.makeMeasureSpec(previewWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
        compactHeight = preview.measuredHeight
        val full = getChildAt(0)
        if (expansionProgress > 0f) {
            full.measure(
                MeasureSpec.makeMeasureSpec((width - previewFaceInset).coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val collapse = findViewById<View>(R.id.buttonCollapseCard)
            val margins = collapse.layoutParams as MarginLayoutParams
            readingHeaderHeight = collapse.measuredHeight + margins.topMargin + margins.bottomMargin
        }
        val target = if (expansionProgress > 0f) full.measuredHeight else compactHeight
        val height = (compactHeight + (target - compactHeight) * expansionProgress).roundToInt()
        if (expansionProgress > 0f && expansionProgress < 1f) {
            preview.measure(
                MeasureSpec.makeMeasureSpec(previewWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(
                    (height - (readingHeaderHeight * expansionProgress).roundToInt()).coerceAtLeast(1),
                    MeasureSpec.EXACTLY
                )
            )
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        surfaceClip.set(0, 0, measuredWidth, measuredHeight)
        clipBounds = surfaceClip
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val x = if (index == 0) {
                if (layoutDirection == LAYOUT_DIRECTION_RTL) 0 else previewFaceInset
            } else (measuredWidth - child.measuredWidth) / 2
            val y = if (index == 0) {
                -(readingHeaderHeight * (1f - expansionProgress)).roundToInt()
            } else {
                (readingHeaderHeight * expansionProgress).roundToInt()
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        expansionProgress in 0.001f..0.999f || super.onInterceptTouchEvent(event)
}
