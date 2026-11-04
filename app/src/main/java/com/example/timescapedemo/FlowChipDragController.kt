package com.example.timescapedemo

import android.content.ClipData
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.DragEvent
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class FlowChipBounds(
    val id: Long, val left: Float, val top: Float, val right: Float, val bottom: Float
)

internal sealed interface FlowDragHit {
    data object Outside : FlowDragHit
    data class Insert(val index: Int, val x: Float) : FlowDragHit
    data class Center(val id: Long) : FlowDragHit
}

internal fun flowDragHit(
    chips: List<FlowChipBounds>, sourceId: Long, x: Float, y: Float, rtl: Boolean
): FlowDragHit {
    if (chips.isEmpty() || y < chips.minOf { it.top } || y > chips.maxOf { it.bottom }) {
        return FlowDragHit.Outside
    }
    for (chip in chips) {
        val insetX = (chip.right - chip.left) * .25f
        val insetY = (chip.bottom - chip.top) * .15f
        if (x in chip.left + insetX..chip.right - insetX &&
            y in chip.top + insetY..chip.bottom - insetY
        ) return if (chip.id == sourceId) FlowDragHit.Outside else FlowDragHit.Center(chip.id)
    }
    val boundaries = (0..chips.size).map { index ->
        when (index) {
            0 -> if (rtl) chips.first().right else chips.first().left
            chips.size -> if (rtl) chips.last().left else chips.last().right
            else -> if (rtl) (chips[index - 1].left + chips[index].right) / 2f
                else (chips[index - 1].right + chips[index].left) / 2f
        }
    }
    val index = boundaries.indices.minBy { abs(x - boundaries[it]) }
    return FlowDragHit.Insert(index, boundaries[index])
}

internal class FlowDragSession(val sourceId: Long) {
    var hit: FlowDragHit = FlowDragHit.Outside
        private set
    private var enteredAt = 0L

    fun hover(next: FlowDragHit, now: Long) {
        if (hit != next) {
            hit = next
            enteredAt = now
        }
    }

    fun mergeReady(now: Long): Boolean =
        hit is FlowDragHit.Center && now - enteredAt >= MERGE_DWELL_MS

    fun remainingDwell(now: Long): Long = (MERGE_DWELL_MS - (now - enteredAt)).coerceAtLeast(0L)

    fun mergeProgress(now: Long): Float =
        if (hit is FlowDragHit.Center) ((now - enteredAt).toFloat() / MERGE_DWELL_MS).coerceIn(0f, 1f) else 0f

    companion object {
        const val MERGE_DWELL_MS = 600L
    }
}

internal class FlowChipDragController(
    private val group: FlowDockChipGroup,
    private val scroll: HorizontalScrollView,
    private val hint: TextView,
    private val onReorder: (Long, Int) -> Unit,
    private val onMerge: (Long, Long) -> Unit,
    private val onInteraction: (Boolean) -> Unit,
    private val onFailure: () -> Unit
) {
    val isDragging: Boolean get() = session != null
    private val density = group.resources.displayMetrics.density
    private val accent = group.accentColor
    private val marker = GradientDrawable().apply {
        setColor(accent)
        cornerRadius = 2f * density
    }
    private var session: FlowDragSession? = null
    private var source: Chip? = null
    private var sourceAlpha = 1f
    private var target: Chip? = null
    private var readyTarget: Long? = null
    private var pointerInside = false
    private var pointerScreenX = 0f
    private var pointerScreenY = 0f
    private val location = IntArray(2)
    private var edgeDirection = 0
    private var disposed = false
    private val dwell = Runnable { updateHover() }
    private val autoScroll = object : Runnable {
        override fun run() {
            if (!isDragging || !pointerInside) return
            scroll.scrollBy((edgeDirection * 8f * density).roundToInt(), 0)
            updateHover()
        }
    }

    init {
        group.setOnDragListener { _, event -> handleDrag(event) }
    }

    fun bind(chip: Chip, flowId: Long, onTap: () -> Unit) {
        chip.setOnClickListener { onTap() }
        chip.setOnLongClickListener {
            start(chip, flowId)
            true
        }
        val detector = GestureDetector(chip.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                chip.performClick()
                return true
            }
            override fun onLongPress(event: MotionEvent) {
                chip.performLongClick()
            }
        })
        // Flow options already handle double taps by stable flow ID.
        detector.setOnDoubleTapListener(null)
        chip.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                chip.isPressed = true
                onInteraction(true)
            }
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                chip.isPressed = false
                if (!isDragging) onInteraction(false)
            }
            true
        }
        chip.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                val now = SystemClock.uptimeMillis()
                val cancelEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                detector.onTouchEvent(cancelEvent)
                cancelEvent.recycle()
                if (source === chip) cancel() else if (!isDragging) onInteraction(false)
            }
        })
    }

    private fun start(chip: Chip, flowId: Long) {
        if (disposed) return
        cancel()
        if (group.childCount < 2) {
            onFailure()
            return
        }
        session = FlowDragSession(flowId)
        source = chip
        sourceAlpha = chip.alpha
        onInteraction(true)
        val started = chip.startDragAndDrop(
            ClipData.newPlainText(DRAG_LABEL, flowId.toString()),
            FlowDockDragShadow(chip), flowId, 0
        )
        if (!started) {
            reset()
            onFailure()
            return
        }
        chip.alpha = .55f
        marker.alpha = 0
        group.overlay.add(marker)
        setHint(group.context.getString(R.string.flow_drag_hint))
        hint.isVisible = true
    }

    private fun handleDrag(event: DragEvent): Boolean {
        val drag = session ?: return false
        // Android may omit clip metadata when an unsuccessful drag ends.
        if (event.action == DragEvent.ACTION_DRAG_ENDED) {
            reset()
            return true
        }
        if (event.clipDescription?.label?.toString() != DRAG_LABEL) return false
        if (event.localState != drag.sourceId) return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                pointerInside = true
                group.getLocationOnScreen(location)
                pointerScreenX = location[0] + event.x
                pointerScreenY = location[1] + event.y
                updateHover()
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                pointerInside = false
                group.removeCallbacks(autoScroll)
                group.removeCallbacks(dwell)
                drag.hover(FlowDragHit.Outside, SystemClock.uptimeMillis())
                showHit()
            }
            DragEvent.ACTION_DROP -> {
                group.getLocationOnScreen(location)
                pointerScreenX = location[0] + event.x
                pointerScreenY = location[1] + event.y
                pointerInside = true
                updateHover()
                val hit = drag.hit
                val mergeReady = drag.mergeReady(SystemClock.uptimeMillis())
                reset()
                when (hit) {
                    is FlowDragHit.Insert -> onReorder(drag.sourceId, hit.index)
                    is FlowDragHit.Center -> if (mergeReady) onMerge(drag.sourceId, hit.id) else return false
                    FlowDragHit.Outside -> return false
                }
            }
        }
        return true
    }

    private fun chips(): List<Chip> = (0 until group.childCount).mapNotNull { group.getChildAt(it) as? Chip }

    private fun updateHover() {
        val drag = session ?: return
        if (!pointerInside) return
        group.removeCallbacks(autoScroll)
        group.removeCallbacks(dwell)
        group.getLocationOnScreen(location)
        val x = pointerScreenX - location[0]
        val y = pointerScreenY - location[1]
        scroll.getLocationOnScreen(location)
        val viewportX = pointerScreenX - location[0]
        val viewportY = pointerScreenY - location[1]
        if (viewportX !in 0f..scroll.width.toFloat() || viewportY !in 0f..scroll.height.toFloat()) {
            pointerInside = false
            drag.hover(FlowDragHit.Outside, SystemClock.uptimeMillis())
            showHit()
            return
        }
        edgeDirection = when {
            viewportX < 24f * density && scroll.canScrollHorizontally(-1) -> -1
            viewportX > scroll.width - 24f * density && scroll.canScrollHorizontally(1) -> 1
            else -> 0
        }
        val bounds = chips().map {
            FlowChipBounds(it.tag as Long, it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        }
        var hit = flowDragHit(bounds, drag.sourceId, x, y, group.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        if (edgeDirection != 0 && hit is FlowDragHit.Center) hit = FlowDragHit.Outside
        val now = SystemClock.uptimeMillis()
        drag.hover(hit, now)
        showHit()
        if (hit is FlowDragHit.Center && !drag.mergeReady(now)) {
            group.postOnAnimation(dwell)
        }
        if (edgeDirection != 0) group.postOnAnimation(autoScroll)
    }

    private fun showHit() {
        val drag = session ?: return
        val hit = drag.hit
        val nextTarget = (hit as? FlowDragHit.Center)?.let { center -> chips().firstOrNull { it.tag == center.id } }
        val targetChanged = nextTarget !== target
        if (targetChanged) {
            restoreTarget()
            target = nextTarget
        }
        group.showInsertion((hit as? FlowDragHit.Insert)?.index)
        group.showMerge(nextTarget, drag.mergeProgress(SystemClock.uptimeMillis()))
        marker.alpha = 0
        when (hit) {
            is FlowDragHit.Insert -> {
                readyTarget = null
                val x = hit.x.coerceIn(2f * density, (group.width - 2f * density).coerceAtLeast(2f * density))
                marker.setBounds((x - 1.5f * density).roundToInt(), (4f * density).roundToInt(),
                    (x + 1.5f * density).roundToInt(), (group.height - 4f * density).roundToInt())
                marker.alpha = 255
                setHint(group.context.getString(R.string.flow_drag_reorder_hint))
            }
            is FlowDragHit.Center -> {
                val ready = drag.mergeReady(SystemClock.uptimeMillis())
                target?.let { chip ->
                    if (targetChanged || (readyTarget == hit.id) != ready) {
                        chip.animate().scaleX(if (ready) 1.08f else 1.03f)
                            .scaleY(if (ready) 1.08f else 1.03f).setDuration(100).start()
                    }
                    setHint(group.context.getString(
                        if (ready) R.string.flow_drag_merge_ready else R.string.flow_drag_merge_wait, chip.text
                    ))
                    if (ready && readyTarget != hit.id) chip.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
                readyTarget = if (ready) hit.id else null
            }
            FlowDragHit.Outside -> {
                readyTarget = null
                setHint(group.context.getString(if (pointerInside) R.string.flow_drag_hint else R.string.flow_drag_cancel_hint))
            }
        }
    }

    private fun setHint(text: CharSequence) {
        if (!TextUtils.equals(hint.text, text)) hint.text = text
    }

    private fun restoreTarget() {
        target?.let {
            it.animate().cancel()
            it.scaleX = 1f
            it.scaleY = 1f
            it.translationX = 0f
        }
        target = null
    }

    private fun reset() {
        group.removeCallbacks(dwell)
        group.removeCallbacks(autoScroll)
        group.overlay.remove(marker)
        restoreTarget()
        group.clearDragFeedback()
        source?.alpha = sourceAlpha
        source = null
        readyTarget = null
        pointerInside = false
        val wasDragging = session != null
        session = null
        hint.isVisible = false
        if (wasDragging) onInteraction(false)
    }

    fun cancel() {
        if (isDragging) group.cancelDragAndDrop()
        reset()
    }

    fun dispose() {
        disposed = true
        cancel()
        group.setOnDragListener(null)
    }

    companion object {
        const val DRAG_LABEL = "flow_chip_drag"
    }
}
