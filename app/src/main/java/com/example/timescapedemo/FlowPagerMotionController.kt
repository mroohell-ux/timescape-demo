package com.example.timescapedemo

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.roundToInt

internal class FlowPagerMotionController(
    private val pager: ViewPager2,
    private val enabled: () -> Boolean = { true },
    private val pageReady: (View) -> Boolean = ::isPageReady,
    private val onRequestChanged: (Int?) -> Unit = {}
) {
    private val recycler = pager.getChildAt(0) as RecyclerView
    private val snapHelper = recycler.onFlingListener as PagerSnapHelper
    private val easing = PathInterpolator(.25f, .1f, .25f, 1f)
    private val releaseEasing = PathInterpolator(.16f, 1f, .3f, 1f)
    private var position = pager.currentItem.toFloat()
    private var manualDrag = false
    private var pendingTarget: Int? = null
    private var observer: ViewTreeObserver? = null
    private var readyFrames = 0
    private var generation = 0
    private var ownedFakeDrag = false
    private var endingFakeDrag = false
    private var frameObserver: ViewTreeObserver? = null
    private var frameCommit: Runnable? = null
    private var motionTarget: Int? = null
    private var motionStartPosition = 0f
    private var requestedPosition = 0f
    private var motionStarted = 0L
    private var motionDuration = CLICK_DURATION_MS
    private var motionEasing: Interpolator = easing
    private var contentWaitStarted = 0L
    private var allowedUnreadyPage: Int? = null
    private val motionStep = Runnable { advanceMotion() }
    val requestedTarget: Int? get() = motionTarget ?: pendingTarget

    private val preparation = ViewTreeObserver.OnPreDrawListener {
        val target = pendingTarget
        val page = target?.let {
            recycler.layoutManager?.findViewByPosition(
                incomingPage(position + (it - position).coerceIn(-MAX_PAGE_STEP, MAX_PAGE_STEP))
            )
        }
        if (target != null && page != null && !pager.isFakeDragging && !recycler.isLayoutRequested &&
            !page.isLayoutRequested && pageReady(page)
        ) {
            if (++readyFrames >= 2) {
                primeFirstFrame(target)
            } else {
                pager.postInvalidateOnAnimation()
            }
        } else {
            readyFrames = 0
        }
        true
    }

    private val preparationTimeout = Runnable {
        pendingTarget?.let { target ->
            Log.w("FlowSwitch", "Target $target is still preparing; continuing with available content")
            clearFrameCallback()
            stopPreparing()
            startMotion(target, CLICK_DURATION_MS)
        }
    }

    private val flingListener = object : RecyclerView.OnFlingListener() {
        override fun onFling(velocityX: Int, velocityY: Int): Boolean {
            if (endingFakeDrag) return true
            if (!enabled() || !manualDrag || pager.isFakeDragging) {
                return snapHelper.onFling(velocityX, velocityY)
            }
            val manager = recycler.layoutManager ?: return false
            val target = snapHelper.findTargetSnapPosition(manager, velocityX, velocityY)
            if (target == RecyclerView.NO_POSITION) return false
            manualDrag = false
            val duration = releaseDuration(target - position, velocityX)
            val version = generation
            val handled = snapHelper.onFling(velocityX, velocityY)
            if (handled) {
                recycler.post {
                    if (version == generation && enabled() &&
                        pager.scrollState == ViewPager2.SCROLL_STATE_SETTLING
                    ) startMotion(target, duration)
                }
            }
            return handled
        }
    }

    private val callbacks = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(index: Int, offset: Float, offsetPixels: Int) {
            position = index + offset
        }

        override fun onPageScrollStateChanged(state: Int) {
            when (state) {
                ViewPager2.SCROLL_STATE_DRAGGING -> {
                    if (!pager.isFakeDragging) {
                        cancelPreparation()
                        manualDrag = true
                    }
                }
                ViewPager2.SCROLL_STATE_SETTLING -> {
                    if (enabled() && manualDrag) {
                        manualDrag = false
                        val version = generation
                        recycler.post {
                            if (version == generation && enabled() &&
                                pager.scrollState == ViewPager2.SCROLL_STATE_SETTLING
                            ) {
                                val count = pager.adapter?.itemCount ?: 0
                                if (count > 0) {
                                    val target = position.roundToInt().coerceIn(0, count - 1)
                                    startMotion(target, releaseDuration(target - position, 0))
                                }
                            }
                        }
                    }
                }
                ViewPager2.SCROLL_STATE_IDLE -> {
                    manualDrag = false
                }
            }
        }
    }

    private val touches = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelPreparation()
            return false
        }
    }

    private val dataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = cancelPreparation()
        override fun onItemRangeInserted(start: Int, count: Int) = cancelPreparation()
        override fun onItemRangeRemoved(start: Int, count: Int) = cancelPreparation()
        override fun onItemRangeMoved(from: Int, to: Int, count: Int) = cancelPreparation()
    }
    private val adapter = pager.adapter

    init {
        // A focus highlight on this full-screen viewport becomes a persistent keyboard veil.
        pager.defaultFocusHighlightEnabled = false
        recycler.defaultFocusHighlightEnabled = false
        pager.registerOnPageChangeCallback(callbacks)
        recycler.addOnItemTouchListener(touches)
        recycler.onFlingListener = flingListener
        adapter?.registerAdapterDataObserver(dataObserver)
    }

    fun navigateTo(target: Int, smoothScroll: Boolean = true) {
        if (smoothScroll && enabled() && requestedTarget == target) return
        cancelPreparation()
        val count = pager.adapter?.itemCount ?: 0
        require(target in 0 until count) { "Flow index $target is outside $count pages" }
        manualDrag = false
        if (!smoothScroll || !enabled() || !pager.isLaidOut) {
            pager.setCurrentItem(target, smoothScroll)
            return
        }
        if (abs(target - position) < .0001f &&
            pager.scrollState == ViewPager2.SCROLL_STATE_IDLE
        ) return
        pendingTarget = target
        onRequestChanged(target)
        observer = pager.viewTreeObserver.also { it.addOnPreDrawListener(preparation) }
        pager.postDelayed(preparationTimeout, PREPARATION_TIMEOUT_MS)
        pager.postInvalidateOnAnimation()
    }

    fun finish() {
        val target = motionTarget ?: pendingTarget ?: pager.currentItem
        cancelPreparation()
        manualDrag = false
        recycler.stopScroll()
        if (!pager.isFakeDragging && (pager.adapter?.itemCount ?: 0) > target) {
            pager.setCurrentItem(target, false)
        }
    }

    fun dispose() {
        cancelPreparation()
        pager.unregisterOnPageChangeCallback(callbacks)
        recycler.removeOnItemTouchListener(touches)
        if (recycler.onFlingListener === flingListener) recycler.onFlingListener = snapHelper
        adapter?.unregisterAdapterDataObserver(dataObserver)
    }

    private fun primeFirstFrame(target: Int) {
        observer?.let { if (it.isAlive) it.removeOnPreDrawListener(preparation) }
        observer = null
        if (abs(target - position) * pageWidth() < .5f) {
            stopPreparing()
            recycler.stopScroll()
            pager.setCurrentItem(target, false)
            onRequestChanged(null)
            return
        }
        if (!pager.beginFakeDrag()) {
            Log.w("FlowSwitch", "A real drag took over destination preparation")
            cancelPreparation()
            return
        }
        ownedFakeDrag = true
        requestedPosition = position
        val direction = if (target > position) -1f else 1f
        if (!moveToPosition(position - direction / pageWidth())) {
            cancelPreparation()
            return
        }
        val version = generation
        val commit = Runnable {
            if (version == generation && pendingTarget == target) {
                clearFrameCallback()
                stopPreparing()
                startMotion(target, CLICK_DURATION_MS)
            }
        }
        frameCommit = commit
        if (pager.isHardwareAccelerated) {
            frameObserver = pager.viewTreeObserver.also { it.registerFrameCommitCallback(commit) }
        } else {
            pager.postOnAnimation(commit)
        }
        pager.postInvalidateOnAnimation()
    }

    private fun clearFrameCallback() {
        frameCommit?.let { callback ->
            frameObserver?.let { if (it.isAlive) it.unregisterFrameCommitCallback(callback) }
            pager.removeCallbacks(callback)
        }
        frameCommit = null
        frameObserver = null
    }

    private fun startMotion(target: Int, duration: Int) {
        if (!ownedFakeDrag) {
            if (!pager.beginFakeDrag()) {
                Log.i("FlowSwitch", "A real drag took over paced settling")
                onRequestChanged(null)
                return
            }
            ownedFakeDrag = true
            requestedPosition = position
        }
        motionTarget = target
        motionStartPosition = requestedPosition
        motionStarted = SystemClock.uptimeMillis()
        motionDuration = duration
        motionEasing = if (duration == CLICK_DURATION_MS) easing else releaseEasing
        contentWaitStarted = 0L
        allowedUnreadyPage = null
        onRequestChanged(target)
        pager.postOnAnimation(motionStep)
    }

    private fun advanceMotion() {
        val target = motionTarget ?: return
        if (!pager.isFakeDragging) {
            cancelPreparation()
            return
        }
        val elapsed = SystemClock.uptimeMillis() - motionStarted
        val fraction = (elapsed.toFloat() / motionDuration).coerceIn(0f, 1f)
        val desired = motionStartPosition +
            (target - motionStartPosition) * motionEasing.getInterpolation(fraction)
        val next = requestedPosition +
            (desired - requestedPosition).coerceIn(-MAX_PAGE_STEP, MAX_PAGE_STEP)
        val incoming = incomingPage(next)
        val page = recycler.layoutManager?.findViewByPosition(incoming)
        if (page != null && allowedUnreadyPage != incoming && !pageReady(page)) {
            if (contentWaitStarted == 0L) contentWaitStarted = SystemClock.uptimeMillis()
            if (SystemClock.uptimeMillis() - contentWaitStarted < PREPARATION_TIMEOUT_MS) {
                pager.postOnAnimation(motionStep)
                return
            }
            Log.w("FlowSwitch", "Incoming page $incoming is still loading; displaying available content")
            allowedUnreadyPage = incoming
        } else {
            contentWaitStarted = 0L
        }
        val finished = fraction == 1f && abs(target - next) * pageWidth() < .5f
        if (!finished && abs(next - requestedPosition) * pageWidth() < .5f) {
            pager.postOnAnimation(motionStep)
            return
        }
        if (finished) {
            val version = generation
            val commit = Runnable {
                if (version == generation && motionTarget == target) {
                    clearFrameCallback()
                    motionTarget = null
                    endOwnedDrag(finishAligned = true)
                    onRequestChanged(null)
                }
            }
            frameCommit = commit
            if (pager.isHardwareAccelerated) {
                frameObserver = pager.viewTreeObserver.also { it.registerFrameCommitCallback(commit) }
            } else {
                pager.postOnAnimation(commit)
            }
        }
        if (!moveToPosition(if (finished) target.toFloat() else next)) {
            cancelPreparation()
        } else {
            if (!finished) pager.postOnAnimation(motionStep)
            pager.invalidate()
        }
    }

    private fun moveToPosition(value: Float): Boolean {
        val rtl = if (pager.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
        if (!pager.fakeDragBy((requestedPosition - value) * pageWidth() * rtl)) return false
        requestedPosition = value
        return true
    }

    private fun endOwnedDrag(finishAligned: Boolean = false) {
        if (!ownedFakeDrag) return
        ownedFakeDrag = false
        if (pager.isFakeDragging) {
            endingFakeDrag = finishAligned || abs(position - position.roundToInt()) * pageWidth() < .5f
            try {
                pager.endFakeDrag()
            } finally {
                endingFakeDrag = false
            }
        }
    }

    private fun pageWidth(): Int =
        (recycler.width - recycler.paddingLeft - recycler.paddingRight).coerceAtLeast(1)

    private fun incomingPage(destination: Float): Int {
        val candidate = if (destination > position) {
            kotlin.math.ceil(destination).toInt()
        } else if (destination < position) {
            kotlin.math.floor(destination).toInt()
        } else {
            position.roundToInt()
        }
        return candidate.coerceIn(0, ((pager.adapter?.itemCount ?: 1) - 1).coerceAtLeast(0))
    }

    private fun releaseDuration(remaining: Float, velocity: Int): Int {
        val speed = abs(velocity.toFloat()) / pageWidth()
        return ((200f + 150f * abs(remaining).coerceAtMost(1f)) /
            (1f + speed.coerceAtMost(5f) * .1f)).roundToInt().coerceIn(200, 350)
    }

    private fun stopPreparing() {
        observer?.let { if (it.isAlive) it.removeOnPreDrawListener(preparation) }
        observer = null
        pager.removeCallbacks(preparationTimeout)
        pendingTarget = null
        readyFrames = 0
    }

    private fun cancelPreparation() {
        val hadRequest = pendingTarget != null || motionTarget != null
        generation++
        clearFrameCallback()
        pager.removeCallbacks(motionStep)
        motionTarget = null
        endOwnedDrag()
        stopPreparing()
        if (hadRequest) onRequestChanged(null)
    }

    companion object {
        private const val CLICK_DURATION_MS = 500
        private const val PREPARATION_TIMEOUT_MS = 1500L
        private const val MAX_PAGE_STEP = .12f

        internal fun isPageReady(page: View): Boolean {
            val cards = page.findViewById<RecyclerView>(R.id.recyclerFlowCards) ?: return true
            if (cards.isLayoutRequested || cards.hasPendingAdapterUpdates()) return false
            if ((cards.adapter?.itemCount ?: 0) > 0 && cards.childCount == 0) return false
            for (index in 0 until cards.childCount) {
                val card = cards.getChildAt(index)
                if (card.isLayoutRequested) return false
                val holder = cards.getChildViewHolder(card) as? CardsAdapter.VH ?: continue
                if (!holder.isFaceReady) return false
            }
            return true
        }
    }
}
