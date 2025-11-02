//package com.example.timescapedemo
//
//import android.graphics.PointF
//import android.util.TypedValue
//import android.widget.TextView
//import androidx.recyclerview.widget.RecyclerView
//import kotlin.math.abs
//import kotlin.math.exp
//import kotlin.math.max
//import kotlin.math.min
//import kotlin.math.roundToInt
//
///**
// * Timescape-like diagonal stack with pixel-based scrolling and a BIG centered "main card".
// * Fix: when user starts dragging, we auto-clear focus so scrolling works immediately.
// */
//class SplineStackLayoutManager(
//    private val cardWpx: Int,              // normal card size (px) - should match item_card.xml
//    private val cardHpx: Int,
//    private val focusedWpx: Int,           // focused "main card" size (px) - larger
//    private val focusedHpx: Int,
//    private val diagTiltDeg: Float = -12f,
//    private val depthScaleDrop: Float = 0.12f,
//    private val depthAlphaDrop: Float = 0.18f,
//    private val stepsPerScreen: Float = 5f
//) : RecyclerView.LayoutManager(), RecyclerView.SmoothScroller.ScrollVectorProvider {
//
//    // Scrolling expressed in *pixels*, not fractional items
//    private var scrollYPx = 0f
//    private var focusedIndex: Int? = null
//
//    override fun canScrollVertically() = true
//    override fun canScrollHorizontally() = false
//    override fun isAutoMeasureEnabled() = true
//
//    override fun generateDefaultLayoutParams() =
//        RecyclerView.LayoutParams(cardWpx, cardHpx)
//
//    /** Public API */
//    fun focus(index: Int) {
//        focusedIndex = index
//        // snap internal scroll to that index so it's centered
//        scrollYPx = index * stepPx()
//        requestLayout()
//    }
//    fun clearFocus() { focusedIndex = null; requestLayout() }
//    fun isFocused(index: Int) = focusedIndex == index
//    fun hasMainCardFocused() = focusedIndex != null   // renamed to avoid clashing with LayoutManager.hasFocus()
//
//    private fun stepPx(): Float = (height / stepsPerScreen).coerceAtLeast(1f)
//    private fun maxScroll(): Float = ((itemCount - 1).coerceAtLeast(0)) * stepPx()
//
//    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
//        detachAndScrapAttachedViews(recycler)
//        if (itemCount == 0 || width == 0 || height == 0) return
//        layoutAll(recycler)
//    }
//
//    private fun applyTextFocus(child: android.view.View, focused: Boolean) {
//        val title = child.findViewById<TextView>(R.id.title)
//        val snippet = child.findViewById<TextView>(R.id.snippet)
//        if (focused) {
//            title?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
//            snippet?.maxLines = 4
//        } else {
//            title?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
//            snippet?.maxLines = 2
//        }
//    }
//
//    private fun setSizeAndMeasure(child: android.view.View, w: Int, h: Int) {
//        val lp = child.layoutParams
//        if (lp.width != w || lp.height != h) {
//            lp.width = w
//            lp.height = h
//            child.layoutParams = lp
//        }
//        measureChildWithMargins(child, 0, 0)
//    }
//
//    private fun layoutAll(recycler: RecyclerView.Recycler) {
//        val screenCx = width / 2f
//        val screenCy = height / 2f
//
//        val leftX  = paddingLeft + cardWpx / 2f
//        val rightX = width - paddingRight - cardWpx / 2f
//        val botY   = height - paddingBottom - cardHpx / 2f
//        val topY   = paddingTop + cardHpx / 2f
//
//        val step = stepPx()
//        // If focused, we still compute positions relative to current scroll Y;
//        // but since we auto-clear focus on drag, this won't block scrolling.
//        val centerIndex = (scrollYPx / step)
//        val window = 3 + (stepsPerScreen * 1.2f).toInt()
//        val first = max(0,   (centerIndex - window).toInt())
//        val last  = min(itemCount - 1, (centerIndex + window).toInt())
//
//        for (i in first..last) {
//            val child = recycler.getViewForPosition(i)
//            addView(child)
//
//            val isFocused = (focusedIndex == i)
//            val rel = i - (focusedIndex?.toFloat() ?: centerIndex)
//
//            if (isFocused) {
//                // Bigger, flat, centered, more lines
//                setSizeAndMeasure(child, focusedWpx, focusedHpx)
//                applyTextFocus(child, true)
//
//                val w = getDecoratedMeasuredWidth(child)
//                val h = getDecoratedMeasuredHeight(child)
//                val l = (screenCx - w / 2f).toInt()
//                val t = (screenCy - h / 2f).toInt()
//                layoutDecoratedWithMargins(child, l, t, l + w, t + h)
//
//                child.scaleX = 1f
//                child.scaleY = 1f
//                child.rotation = 0f
//                child.alpha = 1f
//                child.translationZ = 6000f
//                child.elevation = 6000f
//                child.cameraDistance = 8000f
//                continue
//            }
//
//            // Non-focused: diagonal spline, tilt, depth falloff
//            setSizeAndMeasure(child, cardWpx, cardHpx)
//            applyTextFocus(child, false)
//
//            val u = 1f / (1f + exp(-(-rel * (2f / stepsPerScreen))))
//            val cx = leftX + (rightX - leftX) * u
//            val cy = botY  + (topY   - botY ) * u
//
//            val w = getDecoratedMeasuredWidth(child)
//            val h = getDecoratedMeasuredHeight(child)
//            val l = (cx - w / 2f).toInt()
//            val t = (cy - h / 2f).toInt()
//            layoutDecoratedWithMargins(child, l, t, l + w, t + h)
//
//            val d = abs(rel)
//            val s = max(0.82f, 1f - depthScaleDrop * d)
//            child.scaleX = s
//            child.scaleY = s
//            child.rotation = diagTiltDeg
//            child.alpha = (1f - depthAlphaDrop * d).coerceIn(0.25f, 1f)
//            val z = 1000f - d * 10f
//            child.translationZ = z
//            child.elevation = z
//            child.cameraDistance = 8000f
//        }
//    }
//
//    override fun scrollVerticallyBy(
//        dy: Int,
//        recycler: RecyclerView.Recycler,
//        state: RecyclerView.State
//    ): Int {
//        if (itemCount == 0 || height == 0) return 0
//
//        // If user starts dragging while a card is focused, unfocus so scrolling works.
//        if (dy != 0 && focusedIndex != null) {
//            focusedIndex = null
//        }
//
//        val old = scrollYPx
//        val newY = (old + dy).coerceIn(0f, maxScroll())
//        val consumed = (newY - old).toInt()
//        scrollYPx = newY
//
//        detachAndScrapAttachedViews(recycler)
//        layoutAll(recycler)
//
//        return if (consumed != 0) consumed else if (dy > 0) 1 else if (dy < 0) -1 else 0
//    }
//
//    fun nearestIndex(): Int {
//        val step = stepPx()
//        return (scrollYPx / step).roundToInt().coerceIn(0, max(0, itemCount - 1))
//    }
//
//    fun offsetTo(index: Int): Int {
//        val step = stepPx()
//        val deltaPx = index * step - scrollYPx
//        return deltaPx.roundToInt()
//    }
//
//    override fun computeScrollVectorForPosition(targetPosition: Int) =
//        PointF(0f, if (targetPosition * stepPx() > scrollYPx) 1f else -1f)
//}
