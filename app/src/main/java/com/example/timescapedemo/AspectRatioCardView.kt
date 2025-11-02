package com.example.timescapedemo

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

/**
 * CardView that can enforce a width:height ratio *only if explicitly set* via app:ratio="W:H".
 * If no ratio is provided, it behaves like a normal CardView (wraps content).
 */
class AspectRatioCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {

    private var ratio: Float = 1f // width / height
    private var useRatio: Boolean = false

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.AspectRatioCardView)
            val ratioStr = a.getString(R.styleable.AspectRatioCardView_ratio)?.trim()
            parseRatio(ratioStr)?.let { ratio = it; useRatio = true }
            a.recycle()
        }
        isClickable = true
        isFocusable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)

        // If no ratio OR the parent is giving us an AT_MOST cap, let content decide height.
        if (!useRatio || hMode == MeasureSpec.AT_MOST) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        when {
            // Prefer computing height from a known width
            wMode != MeasureSpec.UNSPECIFIED -> {
                val desiredH = (wSize / ratio).roundToInt().coerceAtLeast(0)
                val exactH = MeasureSpec.makeMeasureSpec(desiredH, MeasureSpec.EXACTLY)
                super.onMeasure(widthMeasureSpec, exactH)
            }
            // Or compute width from a known height
            hMode != MeasureSpec.UNSPECIFIED -> {
                val desiredW = (hSize * ratio).roundToInt().coerceAtLeast(0)
                val exactW = MeasureSpec.makeMeasureSpec(desiredW, MeasureSpec.EXACTLY)
                super.onMeasure(exactW, heightMeasureSpec)
            }
            else -> super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun parseRatio(s: String?): Float? {
        if (s.isNullOrEmpty()) return null
        val parts = s.split(':', '×', 'x').map { it.trim() }.filter { it.isNotEmpty() }
        return when (parts.size) {
            1 -> parts[0].toFloatOrNull()
            2 -> {
                val w = parts[0].toFloatOrNull()
                val h = parts[1].toFloatOrNull()
                if (w != null && h != null && h != 0f) w / h else null
            }
            else -> null
        }
    }

    /** Public setter if you want to turn the ratio behavior on programmatically. */
    fun setRatioString(r: String) {
        parseRatio(r)?.let { ratio = it; useRatio = true; requestLayout() }
    }
}
