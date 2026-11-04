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
        // Cancel the pager's rigid translation; only the individual cards travel.
        page.translationX = -position * page.width * if (rtl) -1f else 1f
        page.scaleX = 1f
        page.scaleY = 1f
        page.alpha = 1f
        page.visibility = if (abs(position) >= 1f) View.INVISIBLE else View.VISIBLE
        val state = page.getTag(R.id.tag_flow_wind_page) as? PageState
            ?: PageState(page).also { page.setTag(R.id.tag_flow_wind_page, it) }
        state.update(position)
    }

    private class PageState(private val page: View) : ViewTreeObserver.OnPreDrawListener {
        private val recycler = requireNotNull(page.findViewById<RecyclerView>(R.id.recyclerFlowCards))
        private val count = page.findViewById<View>(R.id.cardCountIndicator)
        private val bookmark = page.findViewById<View>(R.id.buttonFlowBookmark)
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
            if (value == 0f || distance >= 1f) {
                stopObserving()
                clearCards()
            } else {
                val current = page.viewTreeObserver
                if (observer !== current) {
                    stopObserving()
                    current.addOnPreDrawListener(this)
                    observer = current
                }
            }
        }

        override fun onPreDraw(): Boolean {
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
            return true
        }

        fun reset() {
            stopObserving()
            position = 0f
            count?.alpha = 1f
            bookmark?.alpha = 1f
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

        fun apply(card: View, position: Float, id: Long, row: Float, rtl: Boolean,
            viewportWidth: Int, viewportHeight: Int
        ) {
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
            card.setTransitionAlpha(pose.alpha)
            active = true
        }

        fun clear(card: View) {
            if (!active) return
            card.setAnimationMatrix(null)
            card.setTransitionAlpha(1f)
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
