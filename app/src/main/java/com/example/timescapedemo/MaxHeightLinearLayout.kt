package com.example.timescapedemo

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.view.View.MeasureSpec

class MaxHeightLinearLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var maxHeightPx: Int = Int.MAX_VALUE

    fun setMaxHeightPx(px: Int) {
        if (px != maxHeightPx) {
            maxHeightPx = px
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentMode = MeasureSpec.getMode(heightMeasureSpec)
        val parentSize = MeasureSpec.getSize(heightMeasureSpec)

        val cap = if (maxHeightPx <= 0) Int.MAX_VALUE else maxHeightPx
        val allowed = if (parentSize == 0) cap else minOf(parentSize, cap)

        val mode = if (parentMode == MeasureSpec.EXACTLY) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST
        val hSpec = MeasureSpec.makeMeasureSpec(allowed, mode)

        super.onMeasure(widthMeasureSpec, hSpec)
    }
}
