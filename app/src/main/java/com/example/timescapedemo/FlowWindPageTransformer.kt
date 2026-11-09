package com.example.timescapedemo

import android.graphics.Camera
import android.graphics.Matrix
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.max

internal class FlowWindPageTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        val rtl = page.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val visible = abs(position) < 1f
        // Translating hidden pages into the viewport prevents RecyclerView from recycling them.
        page.translationX = if (visible) -position * page.width * (if (rtl) -1f else 1f) else 0f
        page.scaleX = 1f
        page.scaleY = 1f
        page.alpha = 1f
        page.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        val state = page.getTag(R.id.tag_flow_wind_page) as? PageState
            ?: PageState(page).also { page.setTag(R.id.tag_flow_wind_page, it) }
        state.update(position)
    }

    private class PageState(private val page: View) : ViewTreeObserver.OnPreDrawListener {
        private val recycler = requireNotNull(page.findViewById<RecyclerView>(R.id.recyclerFlowCards))
        private val count = page.findViewById<View>(R.id.cardCountIndicator)
        private val bookmark = page.findViewById<View>(R.id.buttonFlowBookmark)
        private val flip = page.findViewById<View>(R.id.buttonFlowFlip)
        private var observer: ViewTreeObserver? = null
        private var position = 0f

        init {
            page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) = reset()
            })
        }

        fun update(value: Float) {
            position = value
            val distance = abs(value).coerceIn(0f, 1f)
            val chrome = 1f - distance * distance * (3f - 2f * distance)
            count?.alpha = chrome
            bookmark?.alpha = chrome
            flip?.alpha = chrome
            if (value == 0f || distance >= 1f) {
                stopObserving()
                clearCards()
            } else {
                applyCards()
                val current = page.viewTreeObserver
                if (observer !== current) {
                    stopObserving()
                    current.addOnPreDrawListener(this)
                    observer = current
                }
            }
        }

        override fun onPreDraw(): Boolean {
            applyCards()
            return true
        }

        private fun applyCards() {
            val rtl = page.layoutDirection == View.LAYOUT_DIRECTION_RTL
            for (index in 0 until recycler.childCount) {
                val card = recycler.getChildAt(index) as? TimescapeCardLayout ?: continue
                if (card.width == 0 || card.height == 0 || recycler.height == 0) continue
                val holder = recycler.getChildViewHolder(card)
                if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION) continue
                val state = card.getTag(R.id.tag_flow_wind_card) as? CardState
                    ?: CardState().also { card.setTag(R.id.tag_flow_wind_card, it) }
                val row = (card.top + card.height / 2f) / recycler.height
                state.apply(card, position, holder.itemId, row, rtl, page.width, recycler.height)
            }
        }

        fun reset() {
            stopObserving()
            position = 0f
            count?.alpha = 1f
            bookmark?.alpha = 1f
            flip?.alpha = 1f
            clearCards()
        }

        private fun clearCards() {
            for (index in 0 until recycler.childCount) clearCard(recycler.getChildAt(index))
        }

        private fun stopObserving() {
            observer?.let { if (it.isAlive) it.removeOnPreDrawListener(this) }
            observer = null
        }
    }

    private class CardState {
        private val camera = Camera()
        private val matrix = Matrix()
        private val pose = FlowWindPose()
        private var active = false
        private var lastPosition = Float.NaN
        private var lastId = 0L
        private var lastRow = Float.NaN
        private var lastRtl = false
        private var lastWidth = 0
        private var lastHeight = 0
        private var lastViewportWidth = 0
        private var lastViewportHeight = 0

        fun apply(card: TimescapeCardLayout, position: Float, id: Long, row: Float, rtl: Boolean,
            viewportWidth: Int, viewportHeight: Int
        ) {
            if (active && lastPosition == position && lastId == id && lastRow == row &&
                lastRtl == rtl && lastWidth == card.width && lastHeight == card.height &&
                lastViewportWidth == viewportWidth && lastViewportHeight == viewportHeight
            ) return
            lastPosition = position
            lastId = id
            lastRow = row
            lastRtl = rtl
            lastWidth = card.width
            lastHeight = card.height
            lastViewportWidth = viewportWidth
            lastViewportHeight = viewportHeight
            if (!active) {
                card.setWindRenderCaching(true)
            }
            FlowWindMotion.sample(position, id, row, rtl, pose)
            val pivotX = card.width * pose.pivotX
            val pivotY = card.height * pose.pivotY
            camera.save()
            camera.setLocation(0f, 0f, -max(viewportWidth, card.height) * 2.5f / 72f)
            camera.rotateX(pose.pitch)
            camera.rotateY(pose.yaw)
            camera.rotateZ(pose.roll)
            camera.getMatrix(matrix)
            camera.restore()
            matrix.preTranslate(-pivotX, -pivotY)
            matrix.postScale(pose.scale, pose.scale)
            matrix.postTranslate(pivotX + pose.x * viewportWidth, pivotY + pose.y * viewportHeight)
            card.setAnimationMatrix(matrix)
            card.setWindAlpha(pose.alpha)
            card.updateLetteringProjection()
            active = true
        }

        fun clear(card: View) {
            if (!active) return
            card.setAnimationMatrix(null)
            (card as? TimescapeCardLayout)?.setWindAlpha(1f)
            (card as? TimescapeCardLayout)?.setWindRenderCaching(false)
            (card as? TimescapeCardLayout)?.updateLetteringProjection()
            active = false
        }
    }

    companion object {
        fun clearCard(card: View) {
            (card.getTag(R.id.tag_flow_wind_card) as? CardState)?.clear(card)
        }

        fun resetPage(page: View) {
            (page.getTag(R.id.tag_flow_wind_page) as? PageState)?.reset()
            page.translationX = 0f
            page.scaleX = 1f
            page.scaleY = 1f
            page.alpha = 1f
            page.visibility = View.VISIBLE
        }
    }
}
