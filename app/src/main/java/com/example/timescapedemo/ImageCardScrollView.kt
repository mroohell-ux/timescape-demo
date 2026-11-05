package com.example.timescapedemo

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import kotlin.math.min
import kotlin.math.roundToInt

/** Width-fit original media with the shared card-content gesture and face offsets. */
class ImageCardScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : CardContentScrollView(context, attrs) {
    internal var naturalHeight = 0
        private set
    internal val usesWidthFitViewport: Boolean get() {
        val image = (getChildAt(0) as? ImageView)?.drawable ?: return false
        return readerEnabled && hasBoundContent && image.intrinsicWidth > 0 &&
            (fitAtWidth || image.intrinsicHeight.toLong() > image.intrinsicWidth.toLong() * 2)
    }
    internal override val usesReadingViewport: Boolean get() = usesWidthFitViewport

    internal override fun setReadingViewport(cap: Int, progress: Float) {
        val changed = heightCap != cap.coerceAtLeast(1) || expansion != progress.coerceIn(0f, 1f)
        super.setReadingViewport(cap, progress)
        if (changed && usesWidthFitViewport) requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!usesWidthFitViewport) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            naturalHeight = measuredHeight
            return
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        naturalHeight = measuredHeight
        val target = (naturalHeight + (min(naturalHeight, heightCap) - naturalHeight) * expansion)
            .roundToInt().coerceAtLeast(1)
        if (target != measuredHeight) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY))
        }
    }
}
