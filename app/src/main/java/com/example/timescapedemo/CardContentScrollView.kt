package com.example.timescapedemo

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.widget.NestedScrollView
import kotlin.math.abs

/** A fresh outward drag at either edge returns to the card flow. */
open class CardContentScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : NestedScrollView(context, attrs) {
    internal var readerEnabled = false
        private set
    internal open val usesReadingViewport: Boolean
        get() = readerEnabled && hasBoundContent
    internal var onCardTouch: ((MotionEvent) -> Unit)? = null
    internal var onViewportChanged: (() -> Unit)? = null
    protected var fitAtWidth = false
        private set
    protected var hasBoundContent = false
        private set
    protected var heightCap = Int.MAX_VALUE
        private set
    protected var expansion = 0f
        private set
    private var cardId: Long? = null
    private var boundFace = HandwritingFace.FRONT
    private val faceOffsets = IntArray(2)
    private var downX = 0f
    private var downY = 0f
    private var decided = false
    private var delegated = false
    private var savedScrollY = 0
    private var restoreScroll = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        isNestedScrollingEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = true
    }

    internal fun bind(
        id: Long,
        enabled: Boolean,
        fitAtWidth: Boolean = false,
        face: HandwritingFace = HandwritingFace.FRONT
    ) {
        if (cardId != id) {
            cardId = id
            faceOffsets.fill(0)
        } else if (readerEnabled && hasBoundContent && !restoreScroll && expansion == 1f) {
            faceOffsets[boundFace.ordinal] = scrollY
        }
        boundFace = face
        savedScrollY = faceOffsets[face.ordinal]
        restoreScroll = true
        scrollTo(0, 0)
        restoreScroll = enabled
        hasBoundContent = false
        if (readerEnabled != enabled || this.fitAtWidth != fitAtWidth) {
            readerEnabled = enabled
            this.fitAtWidth = fitAtWidth
            requestLayout()
        }
    }

    internal fun markContentReady() {
        hasBoundContent = true
        requestLayout()
    }

    internal fun copyReadingOffsetsFrom(other: CardContentScrollView) {
        check(cardId == other.cardId)
        other.faceOffsets.copyInto(faceOffsets)
        savedScrollY = faceOffsets[boundFace.ordinal]
        restoreScroll = readerEnabled
        requestLayout()
    }

    internal open fun setReadingViewport(cap: Int, progress: Float) {
        val nextCap = cap.coerceAtLeast(1)
        val nextProgress = progress.coerceIn(0f, 1f)
        if (heightCap == nextCap && expansion == nextProgress) return
        if (expansion == 1f && nextProgress < 1f && readerEnabled && hasBoundContent && !restoreScroll) {
            savedScrollY = scrollY
            faceOffsets[boundFace.ordinal] = scrollY
        }
        if (nextProgress == 1f && expansion < 1f) restoreScroll = true
        heightCap = nextCap
        expansion = nextProgress
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (restoreScroll && usesReadingViewport && expansion == 1f) {
            restoreScroll = false
            scrollTo(0, savedScrollY)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!usesReadingViewport || expansion < 1f ||
            (!canScrollVertically(-1) && !canScrollVertically(1))
        ) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            decided = false
            delegated = false
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE && !decided) {
            val dx = event.x - downX
            val dy = event.y - downY
            if (maxOf(abs(dx), abs(dy)) > slop) {
                decided = true
                delegated = abs(dx) > abs(dy) * 1.2f || !canScrollVertically(if (dy < 0f) 1 else -1)
                if (delegated) parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        if (!delegated) onCardTouch?.invoke(event)
        val handled = if (delegated) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            try {
                onCardTouch?.invoke(cancel)
                super.dispatchTouchEvent(cancel)
            } finally {
                cancel.recycle()
            }
            true
        } else super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (readerEnabled && hasBoundContent && !restoreScroll && expansion == 1f) {
            savedScrollY = t
            faceOffsets[boundFace.ordinal] = t
        }
        if (t != oldt) onViewportChanged?.invoke()
    }
}
