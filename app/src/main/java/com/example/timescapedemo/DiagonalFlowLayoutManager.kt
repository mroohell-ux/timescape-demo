//package com.example.timescapedemo
//
//import android.graphics.PointF
//import android.util.TypedValue
//import android.view.View
//import android.widget.TextView
//import androidx.recyclerview.widget.RecyclerView
//import kotlin.math.abs
//import kotlin.math.exp
//import kotlin.math.max
//import kotlin.math.min
//import kotlin.math.roundToInt
//
///**
// * Timescape-like diagonal flow (VERTICAL scroll):
// *  - New cards appear near the TOP-RIGHT (fade-in),
// *  - Cross the center (flat/bright),
// *  - Exit toward BOTTOM-LEFT (fade-out).
// * Tap -> focused main card (bigger, flat, centered). First drag clears focus.
// */
//class DiagonalFlowLayoutManager(
//    private val cardWpx: Int,              // normal card size (px) - should match item_card.xml
//    private val cardHpx: Int,
//    private val focusedWpx: Int,           // focused/main card size (px)
//    private val focusedHpx: Int,
//    private val stepsPerScreen: Float = 4.8f, // lower=faster travel per swipe
//    private val diagTiltDeg: Float = -12f, // tilt for non-focused cards
//    private val depthScaleDrop: Float = 0.10f,
//    private val edgeAlphaMin: Float = 0.25f,
//    // Path shaping:
//    private val rightInsetPx: Int = 12,    // keep right-side cards slightly inside edge
//    private val leftClampFactor: Float = 0.70f, // 0..1: how far LEFT side can spread from center (symmetry control)
//    private val topInsetPx: Int = 24,      // keep top path inside edge
//    private val bottomInsetPx: Int = 24,
//    private val sigmoidTension: Float = 1.6f // curve softness; lower = smoother
//) : RecyclerView.LayoutManager(), RecyclerView.SmoothScroller.ScrollVectorProvider {
//
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
//    /** Public focus API */
//    fun focus(index: Int) {
//        focusedIndex = index
//        scrollYPx = index * stepPx() // center that item
//        requestLayout()
//    }
//    fun clearFocus() { focusedIndex = null; requestLayout() }
//    fun isFocused(index: Int) = focusedIndex == index
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
//    private fun setSizeAndMeasure(child: View, w: Int, h: Int) {
//        val lp = child.layoutParams
//        if (lp.width != w || lp.height != h) {
//            lp.width = w; lp.height = h
//            child.layoutParams = lp
//        }
//        measureChildWithMargins(child, 0, 0)
//    }
//
//    private fun applyTextFocus(child: View, focused: Boolean) {
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
//    private fun layoutAll(recycler: RecyclerView.Recycler) {
//        val cx = width / 2f
//        val cy = height / 2f
//
//        // Path endpoints (top-right -> center -> bottom-left(clamped))
//        val xRight = width - paddingRight - rightInsetPx - cardWpx / 2f
//        val spreadRight = xRight - cx
//        val xLeft = cx - spreadRight * leftClampFactor
//
//        val yTop = paddingTop + topInsetPx + cardHpx / 2f
//        val yBottom = height - paddingBottom - bottomInsetPx - cardHpx / 2f
//
//        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
//
//        val step = stepPx()
//        val centerIndex = (scrollYPx / step)
//        val window = 3 + (stepsPerScreen * 1.5f).toInt()
//        val first = max(0, (centerIndex - window).toInt())
//        val last  = min(itemCount - 1, (centerIndex + window).toInt())
//
//        for (i in first..last) {
//            val child = recycler.getViewForPosition(i)
//            addView(child)
//
//            val isFocused = (focusedIndex == i)
//            val rel = i - (focusedIndex?.toFloat() ?: centerIndex) // distance from "center" item
//
//            if (isFocused) {
//                setSizeAndMeasure(child, focusedWpx, focusedHpx)
//                applyTextFocus(child, true)
//                val w = getDecoratedMeasuredWidth(child)
//                val h = getDecoratedMeasuredHeight(child)
//                val l = (cx - w / 2f).toInt()
//                val t = (cy - h / 2f).toInt()
//                layoutDecoratedWithMargins(child, l, t, l + w, t + h)
//
//                child.scaleX = 1f; child.scaleY = 1f
//                child.rotation = 0f
//                child.alpha = 1f
//                child.translationZ = 6000f; child.elevation = 6000f
//                continue
//            }
//
//            // Smooth 0..1 mapping: rel<0 (above) -> near 0; rel=0 -> .5; rel>0 (below) -> near 1
//            val u = 1f / (1f + exp(-(-rel * (sigmoidTension / stepsPerScreen))))
//
//            // Diagonal path coordinates
//            val px = if (u <= 0.5f) lerp(xRight, cx, u / 0.5f) else lerp(cx, xLeft, (u - 0.5f) / 0.5f)
//            val py = lerp(yTop, yBottom, u)
//
//            setSizeAndMeasure(child, cardWpx, cardHpx)
//            applyTextFocus(child, false)
//
//            val w = getDecoratedMeasuredWidth(child)
//            val h = getDecoratedMeasuredHeight(child)
//            val l = (px - w / 2f).toInt()
//            val t = (py - h / 2f).toInt()
//            layoutDecoratedWithMargins(child, l, t, l + w, t + h)
//
//            // Fade strongest at center, weaker near ends
//            val centerFactor = 1f - abs(2f * u - 1f)
//            val alpha = edgeAlphaMin + (1f - edgeAlphaMin) * centerFactor
//
//            val d = abs(rel)
//            val s = max(0.82f, 1f - depthScaleDrop * d)
//
//            child.scaleX = s
//            child.scaleY = s
//            child.rotation = diagTiltDeg
//            child.alpha = alpha.coerceIn(0.15f, 1f)
//
//            val z = 1000f - d * 10f
//            child.translationZ = z; child.elevation = z
//        }
//    }
//
//    override fun scrollVerticallyBy(
//        dy: Int,
//        recycler: RecyclerView.Recycler,
//        state: RecyclerView.State
//    ): Int {
//        if (itemCount == 0 || height == 0) return 0
//        // allow immediate scroll after focusing
//        if (dy != 0 && focusedIndex != null) focusedIndex = null
//
//        val old = scrollYPx
//        val newY = (old + dy).coerceIn(0f, maxScroll())
//        val consumed = (newY - old).toInt()
//        scrollYPx = newY
//
//        detachAndScrapAttachedViews(recycler)
//        layoutAll(recycler)
//        return consumed
//    }
//
//    fun nearestIndex(): Int {
//        val step = stepPx()
//        return (scrollYPx / step).roundToInt().coerceIn(0, max(0, itemCount - 1))
//    }
//    fun offsetTo(index: Int): Int {
//        val step = stepPx()
//        return (index * step - scrollYPx).roundToInt()
//    }
//
//    override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
//        val dir = if (targetPosition * stepPx() > scrollYPx) 1f else -1f
//        return PointF(0f, dir) // vertical
//    }
//}
