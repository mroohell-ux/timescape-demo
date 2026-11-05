package com.example.timescapedemo

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class FlowWindTransitionTest {
    @Test fun standingLettersFaceTheScreenThroughoutWindAndReversal() = scene { s ->
        for (fraction in listOf(.2f, .5f, .8f, .5f, .2f)) {
            s.moveTo(fraction)
            s.onMain {
                for (page in 0..1) {
                    val moving = cards(page)
                    assertTrue(moving.any { it.animationMatrix != null })
                    for (card in moving) {
                        if (!card.isMediaPreview && card.expansionProgress < 1f) {
                            assertScreenFacingCardLettering(card)
                        }
                    }
                }
            }
        }
        s.moveTo(0f)
        s.finish(0)
        s.onMain {
            for (card in cards(0)) {
                if (card.expansionProgress < 1f) assertScreenFacingCardLettering(card)
            }
        }
    }

    @Test fun cardsFollowDifferentPathsWithoutChangingTheirReadingLayout() = scene { s ->
        lateinit var card: TimescapeCardLayout
        var original = FloatArray(0)
        var measures = 0
        s.onMain {
            card = cards(0)[3]
            original = layoutPose(card)
            measures = card.measurePassCount
        }
        s.capture("rest")
        s.moveTo(.45f)
        s.onMain {
            val moving = cards(0)
            assertTrue(moving.size >= 4)
            assertTrue(moving.all { it.animationMatrix != null && it.transitionAlpha in 0f..1f })
            val matrices = moving.map { view ->
                FloatArray(9).also { requireNotNull(view.animationMatrix).getValues(it) }
            }
            assertTrue(matrices.map { it[2].roundToInt() to it[5].roundToInt() }.toSet().size >= 4)
            assertArrayEquals(original, layoutPose(card), .001f)
            assertEquals("Wind motion must not remeasure card contents", measures, card.measurePassCount)
            assertEquals(0f, page(0).translationY, 0f)
            assertEquals(1f, page(0).scaleX, 0f)
            assertEquals(1f, page(0).scaleY, 0f)
        }
        s.capture("middle")
        s.moveTo(0f)
        s.finish(0)
        s.onMain {
            assertTrue(cards(0).all { it.animationMatrix == null && it.transitionAlpha == 1f })
            assertArrayEquals(original, layoutPose(card), .001f)
            assertEquals(3, managers.getValue(0).currentSelectionIndex())
        }
    }

    @Test fun reversingARealPagerDragRetracesTheSamePerCardMatrices() = scene { s ->
        val fractions = listOf(.2f, .4f, .6f, .8f)
        val frames = fractions.map { fraction ->
            s.moveTo(fraction)
            s.matrices()
        }
        s.capture("far")
        for (index in fractions.indices.reversed()) {
            s.moveTo(fractions[index])
            val reversed = s.matrices()
            for ((id, matrix) in frames[index]) {
                assertArrayEquals("Card $id jumped on reversal", matrix, reversed.getValue(id), 1f)
            }
        }
        s.moveTo(0f)
        s.finish(0)
        s.capture("cancelled")
    }

    @Test fun completingTheSwipeLandsAtTheNewFlowsSavedPosition() = scene { s ->
        s.moveTo(.25f)
        s.capture("quarter")
        s.moveTo(.75f)
        s.capture("three-quarter")
        s.moveTo(1f)
        s.finish(1)
        s.onMain {
            assertTrue(cards(1).all { it.animationMatrix == null && it.transitionAlpha == 1f })
            assertEquals(3, managers.getValue(1).currentSelectionIndex())
            assertTrue(managers.getValue(1).isFocused(3))
            assertEquals(View.VISIBLE, page(1).visibility)
            assertEquals(View.INVISIBLE, page(0).visibility)
            assertEquals(0f, page(1).translationX, .01f)
        }
        s.capture("landed")
    }

    @Test fun rtlSwipeUsesTheSameWindAndReturnsToAnInteractivePage() = scene(rtl = true) { s ->
        s.moveTo(.5f)
        s.onMain {
            val outgoing = cards(0).first()
            assertNotNull(outgoing.animationMatrix)
            assertTrue(outgoing.transitionAlpha < 1f)
        }
        s.moveTo(1f)
        s.finish(1)
        s.onMain {
            assertEquals(View.VISIBLE, page(1).visibility)
            assertTrue(cards(1).all { it.animationMatrix == null && it.transitionAlpha == 1f })
        }
    }

    @Test fun pageChangesFromLabelsAlsoUseWindAndRestoreNativeCardTransforms() = scene { s ->
        s.onMain { pager.setCurrentItem(1, true) }
        s.await { s.cards(0).any { it.animationMatrix != null } }
        s.await { s.pager.currentItem == 1 && s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
        s.frames(3)
        s.onMain {
            assertTrue(cards(1).all { it.animationMatrix == null && it.transitionAlpha == 1f })
            assertTrue(managers.getValue(1).isFocused(3))
        }
    }

    @Test fun emptyAreaTouchSwipeDrivesWindThroughTheNormalPagerGesture() = scene { s ->
        val started = SystemClock.uptimeMillis()
        fun touch(action: Int, progress: Float) = s.onMain {
            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action,
                pager.width * (.97f - progress), pager.height * .86f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(root.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        s.onMain {
            assertFalse(managers.getValue(0).hasCardUnder(pager.width * .97f, pager.height * .86f))
        }
        touch(MotionEvent.ACTION_DOWN, 0f)
        touch(MotionEvent.ACTION_MOVE, .15f)
        s.frames(2)
        touch(MotionEvent.ACTION_MOVE, .4f)
        s.frames(2)
        s.onMain {
            assertFalse(pager.isFakeDragging)
            assertEquals(ViewPager2.SCROLL_STATE_DRAGGING, pager.scrollState)
            assertTrue(cards(0).any { it.animationMatrix != null && it.transitionAlpha < 1f })
        }
        touch(MotionEvent.ACTION_MOVE, .72f)
        s.frames(2)
        touch(MotionEvent.ACTION_UP, .72f)
        s.await { s.pager.currentItem == 1 && s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
        s.frames(3)
        s.onMain {
            assertTrue(cards(1).all { it.animationMatrix == null && it.transitionAlpha == 1f })
            assertTrue(managers.getValue(1).isFocused(3))
        }
    }

    @Test fun interruptedSmoothSwitchReturnsWithoutResidualWind() = scene { s ->
        s.onMain { pager.setCurrentItem(1, true) }
        s.await { s.cards(0).any { it.animationMatrix != null } }
        s.onMain { pager.setCurrentItem(0, true) }
        s.await { s.pager.currentItem == 0 && s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
        s.frames(3)
        s.onMain {
            assertEquals(View.VISIBLE, page(0).visibility)
            assertTrue(cards(0).all { it.animationMatrix == null && it.transitionAlpha == 1f })
            assertTrue(managers.getValue(0).isFocused(3))
        }
    }

    @Test fun resetAndRecyclingDiscardAllTemporaryWindState() = scene { s ->
        s.moveTo(.5f)
        s.onMain {
            val old = cards(0)
            FlowWindPageTransformer.resetPage(page(0))
            assertTrue(old.all { it.animationMatrix == null && it.transitionAlpha == 1f })
            val incoming = cards(1)
            recycler(1).adapter = null
            assertTrue(incoming.all { it.animationMatrix == null && it.transitionAlpha == 1f })
        }
        s.moveTo(0f)
        s.finish(0)
    }

    private fun scene(rtl: Boolean = false, test: (Scene) -> Unit) {
        ActivityScenario.launch(TimescapeFlowTestActivity::class.java).use { scenario ->
            lateinit var scene: Scene
            scenario.onActivity {
                scene = Scene(it, rtl)
                it.setContentView(scene.root)
            }
            try {
                scene.await {
                    scene.recyclers.size >= 2 && scene.cards(0).size >= 4 &&
                        scene.cards(1).size >= 4 && scene.managers.getValue(0).isFocused(3)
                }
                scene.frames(8)
                test(scene)
            } finally {
                scenario.onActivity {
                    if (scene.pager.isFakeDragging) scene.pager.endFakeDrag()
                    scene.pager.adapter = null
                }
            }
        }
    }

    private class Scene(val activity: TimescapeFlowTestActivity, private val rtl: Boolean) {
        val root = FrameLayout(activity).apply {
            setBackgroundResource(R.drawable.bg_app_cosmic_flow)
            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        val pager = ViewPager2(activity)
        val recyclers = mutableMapOf<Int, RecyclerView>()
        val managers = mutableMapOf<Int, RightRailFlowLayoutManager>()
        private var fraction = 0f

        init {
            root.addView(pager, FrameLayout.LayoutParams(-1, -1))
            pager.offscreenPageLimit = 1
            pager.setPageTransformer(FlowWindPageTransformer())
            pager.adapter = object : RecyclerView.Adapter<Page>() {
                override fun onCreateViewHolder(parent: ViewGroup, type: Int): Page {
                    val view = activity.layoutInflater.inflate(R.layout.page_card_flow, parent, false)
                    return Page(view)
                }

                override fun getItemCount() = 3

                override fun onBindViewHolder(holder: Page, position: Int) {
                    val recycler = holder.itemView.findViewById<RecyclerView>(R.id.recyclerFlowCards)
                    val side = activity.resources.displayMetrics.widthPixels - dp(32)
                    val manager = RightRailFlowLayoutManager(side, side, dp(124), dp(8))
                    val adapter = CardsAdapter(TintStyle.None, onItemClick = { manager.focus(it) },
                        onItemDoubleClick = { _, _ -> }, onItemLongPress = { _, _ -> false },
                        onStickyNotesClick = {})
                    recycler.layoutManager = manager
                    recycler.itemAnimator = null
                    recycler.adapter = adapter
                    recyclers[position] = recycler
                    managers[position] = manager
                    adapter.submitList((0 until 12).map { index ->
                        CardItem(position * 100L + index, "Flow ${position + 1} · Card $index",
                            "A separate card carried by the wind into its own place. ".repeat(10),
                            bg = BgImage.Res(if (position % 2 == 0) R.drawable.bg_2 else R.drawable.bg_3))
                    }) { manager.restoreState(3, true) }
                }

                override fun onViewRecycled(holder: Page) {
                    holder.itemView.findViewById<RecyclerView>(R.id.recyclerFlowCards).adapter = null
                }
            }
        }

        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
        fun recycler(index: Int) = recyclers.getValue(index)
        fun page(index: Int) = recycler(index).parent as View
        fun cards(index: Int): List<TimescapeCardLayout> = recyclers[index]?.let { recycler ->
            (0 until recycler.childCount).map { recycler.getChildAt(it) as TimescapeCardLayout }
        } ?: emptyList()

        fun layoutPose(view: View) = floatArrayOf(view.left.toFloat(), view.top.toFloat(),
            view.width.toFloat(), view.height.toFloat(), view.rotationX, view.rotationY,
            view.rotation, view.scaleX, view.scaleY, view.pivotX, view.pivotY, view.alpha)

        fun onMain(block: Scene.() -> Unit) =
            InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }

        fun moveTo(value: Float) {
            onMain {
                if (!pager.isFakeDragging) assertTrue(pager.beginFakeDrag())
                assertTrue(pager.fakeDragBy((fraction - value) * pager.width * if (rtl) -1f else 1f))
                fraction = value
            }
            frames(3)
        }

        fun finish(index: Int) {
            onMain { assertTrue(pager.endFakeDrag()) }
            await { pager.currentItem == index && pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
            frames(3)
        }

        fun matrices(): Map<Long, FloatArray> {
            var result = emptyMap<Long, FloatArray>()
            onMain {
                result = cards(0).associate { card ->
                    recycler(0).getChildViewHolder(card).itemId to FloatArray(9).also {
                        requireNotNull(card.animationMatrix).getValues(it)
                    }
                }
            }
            return result
        }

        fun frames(count: Int) {
            val latch = CountDownLatch(1)
            onMain {
                var remaining = count
                val frame = object : Runnable {
                    override fun run() {
                        if (--remaining == 0) latch.countDown() else root.postOnAnimation(this)
                    }
                }
                root.postOnAnimation(frame)
            }
            assertTrue("Wind frame was not presented", latch.await(10, TimeUnit.SECONDS))
        }

        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                onMain { ready = condition() }
                if (ready) return
                SystemClock.sleep(20)
            }
            throw AssertionError("Wind pager did not settle: ${pager.currentItem}/${pager.scrollState}")
        }

        fun capture(label: String) {
            if (InstrumentationRegistry.getArguments().getString("captureFlowWind") != "true") return
            frames(2)
            val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                File(activity.cacheDir, "flow-wind-$label.png").outputStream().use {
                    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                image.recycle()
            }
        }
    }

    private class Page(view: View) : RecyclerView.ViewHolder(view)
}
