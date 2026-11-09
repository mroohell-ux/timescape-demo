package com.example.timescapedemo

import android.os.SystemClock
import android.view.DragEvent
import android.view.View
import androidx.viewpager2.widget.ViewPager2

internal const val CARD_MOVE_DRAG_LABEL = "card_move_drag"

internal fun cardMoveEdgeDirection(x: Float, width: Int, rtl: Boolean): Int {
    if (width <= 0 || !x.isFinite() || x < 0f || x > width) return 0
    val edge = width * .12f
    val physical = when {
        x < edge -> -1
        x > width - edge -> 1
        else -> 0
    }
    return if (rtl) -physical else physical
}

internal class CardMoveEdgeDwell {
    private var direction = 0
    private var enteredAt: Long? = null

    fun ready(nextDirection: Int, busy: Boolean, now: Long): Boolean {
        if (direction != nextDirection || busy || nextDirection == 0) enteredAt = null
        direction = nextDirection
        if (busy || direction == 0) return false
        val start = enteredAt ?: now.also { enteredAt = it }
        if (now - start < DWELL_MS) return false
        enteredAt = null
        return true
    }

    fun remaining(now: Long): Long =
        enteredAt?.let { (DWELL_MS - (now - it)).coerceAtLeast(1L) } ?: DWELL_MS

    fun reset() {
        direction = 0
        enteredAt = null
    }

    companion object {
        const val DWELL_MS = 650L
    }
}

internal class CardMovePagerDragController(
    private val pager: ViewPager2,
    private val currentIndex: () -> Int,
    private val flowCount: () -> Int,
    private val requestedTarget: () -> Int?,
    private val navigate: (Int) -> Unit,
    private val onDrop: (DragEvent, Int) -> Boolean
) {
    var isDragging = false
        private set
    private var inside = false
    private var x = 0f
    private val dwell = CardMoveEdgeDwell()
    private val tick = Runnable { checkEdge() }

    init {
        pager.setOnDragListener { _, event -> handleDrag(event) }
    }

    private fun handleDrag(event: DragEvent): Boolean {
        // Unsuccessful drag ends can omit clip metadata.
        if (event.action == DragEvent.ACTION_DRAG_ENDED) {
            val accepted = isDragging
            cancel()
            return accepted
        }
        if (event.clipDescription?.label?.toString() != CARD_MOVE_DRAG_LABEL) return false
        if (event.action == DragEvent.ACTION_DRAG_STARTED) {
            start()
            return true
        }
        if (!isDragging) return false
        when (event.action) {
            DragEvent.ACTION_DRAG_LOCATION -> hover(event.x)
            DragEvent.ACTION_DRAG_EXITED -> leave()
            DragEvent.ACTION_DROP -> {
                val target = requestedTarget() ?: currentIndex()
                cancel()
                return onDrop(event, target)
            }
        }
        return true
    }

    fun start() {
        cancel()
        isDragging = true
    }

    fun hover(positionX: Float) {
        if (!isDragging) return
        inside = true
        x = positionX
        checkEdge()
    }

    fun leave() {
        inside = false
        pager.removeCallbacks(tick)
        dwell.reset()
    }

    fun cancel() {
        isDragging = false
        leave()
    }

    fun dispose() {
        cancel()
        pager.setOnDragListener(null)
    }

    private fun isSwitching() =
        requestedTarget() != null || pager.scrollState != ViewPager2.SCROLL_STATE_IDLE

    private fun checkEdge() {
        pager.removeCallbacks(tick)
        if (!isDragging || !inside) return
        val direction = cardMoveEdgeDirection(x, pager.width, pager.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        val target = currentIndex() + direction
        if (direction == 0 || target !in 0 until flowCount()) {
            dwell.reset()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (dwell.ready(direction, isSwitching(), now)) navigate(target)
        if (isDragging && inside) {
            pager.postDelayed(tick, if (isSwitching()) 50L else dwell.remaining(now))
        }
    }
}
