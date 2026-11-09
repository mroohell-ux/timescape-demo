package com.example.timescapedemo

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ImageView
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
            assertTrue(moving.all { it.animationMatrix != null && it.windAlpha in 0f..1f })
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
            assertTrue(cards(0).all { it.animationMatrix == null && it.windAlpha == 1f })
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
            assertTrue(cards(1).all { it.animationMatrix == null && it.windAlpha == 1f })
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
            assertTrue(outgoing.windAlpha < 1f)
        }
        s.moveTo(1f)
        s.finish(1)
        s.onMain {
            assertEquals(View.VISIBLE, page(1).visibility)
            assertTrue(cards(1).all { it.animationMatrix == null && it.windAlpha == 1f })
        }
    }

    @Test fun pageChangesFromLabelsAlsoUseWindAndRestoreNativeCardTransforms() = scene { s ->
        s.onMain { motion.navigateTo(1) }
        s.await { s.cards(0).any { it.animationMatrix != null } }
        s.await { s.pager.currentItem == 1 && s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
        s.frames(3)
        s.onMain {
            assertTrue(cards(1).all { it.animationMatrix == null && it.windAlpha == 1f })
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
            assertTrue(cards(0).any { it.animationMatrix != null && it.windAlpha < 1f })
        }
        touch(MotionEvent.ACTION_MOVE, .72f)
        s.frames(2)
        touch(MotionEvent.ACTION_UP, .72f)
        s.await { s.pager.currentItem == 1 && s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
        s.frames(3)
        s.onMain {
            assertTrue(cards(1).all { it.animationMatrix == null && it.windAlpha == 1f })
            assertTrue(managers.getValue(1).isFocused(3))
        }
    }

    @Test fun interruptedSmoothSwitchReturnsWithoutResidualWind() = scene { s ->
        s.onMain { motion.navigateTo(1) }
        s.await { s.cards(0).any { it.animationMatrix != null } }
        s.onMain { motion.navigateTo(0) }
        s.await { s.pager.currentItem == 0 && s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
        s.frames(3)
        s.onMain {
            assertEquals(View.VISIBLE, page(0).visibility)
            assertTrue(cards(0).all { it.animationMatrix == null && it.windAlpha == 1f })
            assertTrue(managers.getValue(0).isFocused(3))
        }
    }

    @Test fun resetAndRecyclingDiscardAllTemporaryWindState() = scene { s ->
        s.moveTo(.5f)
        s.onMain {
            val old = cards(0)
            FlowWindPageTransformer.resetPage(page(0))
            assertTrue(old.all { it.animationMatrix == null && it.windAlpha == 1f && it.transitionAlpha == 1f })
            val incoming = cards(1)
            recycler(1).adapter = null
            assertTrue(incoming.all { it.animationMatrix == null && it.windAlpha == 1f && it.transitionAlpha == 1f })
        }
        s.moveTo(0f)
        s.finish(0)
    }

    @Test fun labelSwitchPresentsAFullHalfSecondOfWind() = scene { s ->
        assertLabelSwitchTiming(s, "click")
    }

    @Test fun floatingFlipControlFadesWithItsFlowAndResetRestoresItsOpacity() = scene { s ->
        s.onMain {
            for (index in 0..1) {
                page(index).findViewById<View>(R.id.buttonFlowFlip).visibility = View.VISIBLE
            }
        }
        s.moveTo(.45f)
        s.onMain {
            for (index in 0..1) {
                val flip = page(index).findViewById<View>(R.id.buttonFlowFlip)
                assertTrue(flip.alpha in .1f.. .9f)
                assertEquals(page(index).findViewById<View>(R.id.buttonFlowBookmark).alpha, flip.alpha, 0f)
            }
            FlowWindPageTransformer.resetPage(page(0))
            assertEquals(1f, page(0).findViewById<View>(R.id.buttonFlowFlip).alpha, 0f)
        }
        s.moveTo(0f)
        s.finish(0)
        s.onMain {
            assertEquals(1f, page(0).findViewById<View>(R.id.buttonFlowFlip).alpha, 0f)
        }
    }

    @Test fun dragSourceFadeDoesNotStretchFlowSwitching() = scene { s ->
        s.onMain {
            cards(0).first { it.expansionProgress == 1f }.apply {
                alpha = .65f
                scaleX *= .98f
                scaleY *= .98f
            }
        }
        assertLabelSwitchTiming(s, "drag source")
    }

    private fun assertLabelSwitchTiming(s: Scene, label: String) {
        val samples = mutableListOf<Pair<Long, Float>>()
        val presented = mutableListOf<Float>()
        var scrollPosition = 0f
        val presentation = ViewTreeObserver.OnPreDrawListener {
            val position = scrollPosition
            s.root.viewTreeObserver.registerFrameCommitCallback { presented.add(position) }
            true
        }
        val frames = mutableListOf<List<Double>>()
        val metrics = Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            frames.add(listOf(FrameMetrics.TOTAL_DURATION, FrameMetrics.DRAW_DURATION,
                FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION, FrameMetrics.GPU_DURATION)
                .map { frame.getMetric(it) / 1e6 })
        }
        s.onMain {
            activity.window.addOnFrameMetricsAvailableListener(metrics, Handler(Looper.getMainLooper()))
            root.viewTreeObserver.addOnPreDrawListener(presentation)
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(position: Int, offset: Float, offsetPixels: Int) {
                    scrollPosition = position + offset
                    samples.add(SystemClock.uptimeMillis() to scrollPosition)
                }
            })
            motion.navigateTo(1)
        }
        try {
            s.await { s.pager.currentItem == 1 && s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
            s.onMain {
                val moving = samples.filter { it.second > 0f }
                val duration = moving.last().first - moving.first().first
                Log.i("FlowSwitchMetrics", "$label duration=${duration}ms samples=${moving.size}")
                Log.i("FlowSwitchMetrics", "$label samples=" +
                    moving.joinToString { "${it.first - moving.first().first}:${it.second}" })
                assertTrue("The click transition was compressed to ${duration}ms", duration >= 450)
                assertTrue("A renderer stall stretched the click to ${duration}ms", duration <= 1000)
                assertTrue("Wind needs visible intermediate poses",
                    moving.count { it.second >= .1f && it.second <= .9f } >= 4)
                assertTrue("A late render frame skipped the wind trajectory",
                    moving.zipWithNext().all { abs(it.second.second - it.first.second) <= .122f })
                assertTrue("Wind callbacks advanced without presenting intermediate frames",
                    presented.count { it in .1f.. .9f } >= 4)
                assertTrue("Presented wind poses jumped despite the step cap: $presented",
                    presented.zipWithNext().all { abs(it.second - it.first) <= .122f })
                assertEquals(1, pager.offscreenPageLimit)
                Log.i("FlowSwitchMetrics", "$label frame total/draw/sync/command/gpu ms=$frames")
            }
        } finally {
            s.onMain {
                activity.window.removeOnFrameMetricsAvailableListener(metrics)
                root.viewTreeObserver.removeOnPreDrawListener(presentation)
            }
        }
    }

    @Test fun targetImagePreparationDoesNotConsumeTheClickAnimation() = scene { s ->
        lateinit var loaded: () -> Unit
        s.onMain {
            val holder = recycler(1).getChildViewHolder(cards(1).first()) as CardsAdapter.VH
            loaded = holder.trackFaceLoad()
            motion.navigateTo(1)
        }
        s.frames(5)
        SystemClock.sleep(120)
        s.onMain {
            assertEquals(0, pager.currentItem)
            assertEquals(ViewPager2.SCROLL_STATE_IDLE, pager.scrollState)
            assertTrue(cards(0).all { it.animationMatrix == null })
            loaded()
        }
        val readyAt = SystemClock.uptimeMillis()
        s.await { s.pager.currentItem == 1 && s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
        assertTrue("Waiting for content must not eat the 500ms animation",
            SystemClock.uptimeMillis() - readyAt >= 450)
    }

    @Test fun distantLabelSwitchKeepsABoundedCacheAndReadingPositionsInBothDirections() = scene(pageCount = 7) { s ->
        var attachedPages = 0
        val positions = mutableListOf<Float>()
        s.onMain {
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(position: Int, offset: Float, offsetPixels: Int) {
                    positions.add(position + offset)
                    attachedPages = maxOf(attachedPages, (pager.getChildAt(0) as RecyclerView).childCount)
                }
            })
        }
        for (target in listOf(6, 0)) {
            val started = SystemClock.uptimeMillis()
            s.onMain {
                positions.clear()
                positions.add(pager.currentItem.toFloat())
                motion.navigateTo(target)
            }
            // Distant routes prepare/recycle adjacent pages; allow slow shared-emulator preparation.
            try {
                s.await(timeoutMs = 300_000) {
                    s.pager.currentItem == target && s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE
                }
            } finally {
                s.onMain {
                    Log.i("FlowSwitchMetrics", "distant target=$target last=${positions.lastOrNull()} " +
                        "requested=${motion.requestedTarget} fake=${pager.isFakeDragging} samples=${positions.size}")
                }
            }
            s.frames(3)
            s.onMain {
                Log.i("FlowSwitchMetrics", "distant target=$target duration=${SystemClock.uptimeMillis() - started}ms " +
                    "samples=${positions.size} attached=$attachedPages")
                assertTrue("A distant jump skipped intervening wind poses",
                    positions.zipWithNext().all { abs(it.second - it.first) <= .122f })
                assertEquals(1, pager.offscreenPageLimit)
                assertTrue("Navigation attached $attachedPages pages", attachedPages <= 4)
                assertEquals(3, managers.getValue(target).currentSelectionIndex())
                assertTrue(managers.getValue(target).isFocused(3))
                assertTrue(cards(target).all { it.animationMatrix == null && it.windAlpha == 1f })
            }
        }
    }

    @Test fun aNewLabelRequestCancelsAnUnreadyTargetWithoutAJump() = scene { s ->
        lateinit var loaded: () -> Unit
        s.onMain {
            loaded = (recycler(1).getChildViewHolder(cards(1).first()) as CardsAdapter.VH).trackFaceLoad()
            motion.navigateTo(1)
        }
        s.frames(3)
        s.onMain {
            motion.navigateTo(0, false)
            loaded()
        }
        s.frames(6)
        s.onMain {
            assertEquals(0, pager.currentItem)
            assertEquals(ViewPager2.SCROLL_STATE_IDLE, pager.scrollState)
            assertEquals(1, pager.offscreenPageLimit)
            assertTrue(cards(0).all { it.animationMatrix == null })
        }
    }

    @Test fun aPausedWindPoseDoesNotContinuouslyInvalidateTheWholeScene() = scene { s ->
        s.moveTo(.45f)
        s.frames(4)
        var draws = 0
        val listener = ViewTreeObserver.OnDrawListener { draws++ }
        s.onMain { root.viewTreeObserver.addOnDrawListener(listener) }
        try {
            s.frames(8)
            s.onMain {
                assertTrue("A held gesture redrew $draws unchanged frames", draws <= 1)
            }
        } finally {
            s.onMain { root.viewTreeObserver.removeOnDrawListener(listener) }
            s.moveTo(0f)
            s.finish(0)
        }
    }

    @Test fun slowTouchReleaseUsesAGentleShortSettle() = scene { s ->
        s.touch(MotionEvent.ACTION_DOWN, 0f)
        s.touch(MotionEvent.ACTION_MOVE, .15f)
        s.frames(2)
        s.touch(MotionEvent.ACTION_MOVE, .75f)
        s.frames(3)
        SystemClock.sleep(160)
        val releasedAt = SystemClock.uptimeMillis()
        s.touch(MotionEvent.ACTION_UP, .75f)
        s.await { s.pager.currentItem == 1 && s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
        val duration = SystemClock.uptimeMillis() - releasedAt
        Log.i("FlowSwitchMetrics", "slow release duration=${duration}ms")
        assertTrue("Slow release snapped abruptly in ${duration}ms", duration >= 190)
    }

    @Test fun fastTouchReleaseStillPresentsAVisibleSettle() = scene { s ->
        s.touch(MotionEvent.ACTION_DOWN, 0f)
        s.touch(MotionEvent.ACTION_MOVE, .15f)
        s.frames(1)
        s.touch(MotionEvent.ACTION_MOVE, .4f)
        s.frames(1)
        s.touch(MotionEvent.ACTION_MOVE, .72f)
        SystemClock.sleep(16)
        val releasedAt = SystemClock.uptimeMillis()
        s.touch(MotionEvent.ACTION_UP, .72f)
        s.await { s.pager.currentItem == 1 && s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
        val duration = SystemClock.uptimeMillis() - releasedAt
        Log.i("FlowSwitchMetrics", "fast release duration=${duration}ms")
        assertTrue("Fast release snapped abruptly in ${duration}ms", duration >= 190)
    }

    @Test fun cachedLetteringBoundsFollowTextSizeTypefaceAndInPlaceEdits() = scene { s ->
        lateinit var text: TimescapeTextView
        s.onMain {
            text = cards(0).first().findViewById(R.id.previewSnippet)
        }
        fun compare() = s.onMain {
            val expected = RectF()
            val actual = RectF()
            requireNotNull(text.layout).copyGlyphBounds(expected)
            text.copyLayoutGlyphBounds(actual)
            assertEquals(expected, actual)
        }
        compare()
        s.onMain { text.text = "Short" }
        s.frames(3)
        compare()
        s.onMain {
            text.textSize = 26f
            text.typeface = Typeface.MONOSPACE
            text.setText("Mutable lettering", TextView.BufferType.EDITABLE)
        }
        s.frames(3)
        compare()
        s.onMain { text.editableText.replace(0, text.length(), "Longer shaped letters 字形更新") }
        s.frames(3)
        compare()
    }

    @Test fun rtlLabelSwitchAlsoFinishesWithoutPrimeOrWindResidue() = scene(rtl = true) { s ->
        s.onMain { motion.navigateTo(1) }
        s.await { s.pager.currentItem == 1 && s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
        s.frames(3)
        s.onMain {
            assertFalse(pager.isFakeDragging)
            assertEquals(1, pager.offscreenPageLimit)
            assertTrue(cards(1).all { it.animationMatrix == null && it.windAlpha == 1f })
        }
    }

    @Test fun aFreshTouchTakesOverWithoutLeavingAHalfSettledFakeDrag() = scene { s ->
        var progress = 0f
        s.onMain {
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(position: Int, offset: Float, offsetPixels: Int) {
                    progress = position + offset
                }
            })
            motion.navigateTo(1)
        }
        s.await { progress in .25f.. .8f && s.pager.isFakeDragging }
        s.touch(MotionEvent.ACTION_DOWN, 0f)
        s.touch(MotionEvent.ACTION_UP, 0f)
        s.await { s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
        s.frames(3)
        s.onMain {
            assertFalse(pager.isFakeDragging)
            assertTrue(cards(pager.currentItem).all { it.animationMatrix == null && it.windAlpha == 1f })
            assertEquals(3, managers.getValue(pager.currentItem).currentSelectionIndex())
        }
    }

    @Test fun repeatedLabelRequestsDoNotRestartAnActiveTransition() = scene { s ->
        s.onMain { motion.navigateTo(1) }
        s.await { s.pager.isFakeDragging && s.cards(0).any { it.animationMatrix != null } }
        s.onMain {
            val original = requests.toList()
            repeat(5) { motion.navigateTo(1) }
            assertEquals(original, requests)
            assertEquals(1, motion.requestedTarget)
        }
        s.await { s.pager.currentItem == 1 && s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
        s.onMain { assertNull(motion.requestedTarget) }
    }

    @Test fun stoppingMidSwitchImmediatelyRestoresTheRequestedFlow() = scene { s ->
        s.onMain { motion.navigateTo(2) }
        s.await { s.pager.isFakeDragging && s.cards(0).any { it.animationMatrix != null } }
        s.onMain { motion.finish() }
        s.await {
            s.pager.currentItem == 2 && s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE &&
                s.cards(2).size >= 4 && s.managers.getValue(2).isFocused(3)
        }
        s.frames(3)
        s.onMain {
            assertFalse(pager.isFakeDragging)
            assertNull(motion.requestedTarget)
            assertTrue(cards(2).all { it.animationMatrix == null && it.windAlpha == 1f })
            assertEquals(1, pager.offscreenPageLimit)
        }
    }

    @Test fun cardEdgeHoverWaitsAndRestartsOnlyAfterTheWindLands() = scene { s ->
        var enteredAt = 0L
        s.onMain {
            installCardDrag()
            requireNotNull(cardDrag).start()
            enteredAt = SystemClock.uptimeMillis()
            requireNotNull(cardDrag).hover(pager.width * .97f)
        }
        s.await(60_000) { s.cardRequests.size >= 2 }
        s.onMain {
            requireNotNull(cardDrag).cancel()
            assertEquals(listOf(1, 2), cardRequests.map { it.first })
            assertTrue(cardRequests[0].second - enteredAt >= CardMoveEdgeDwell.DWELL_MS)
            assertTrue("The next dwell must start after landing, not during the wind",
                cardRequests[1].second - cardLandings.first() >= CardMoveEdgeDwell.DWELL_MS)
        }
        s.await(60_000) { s.motion.requestedTarget == null && s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
    }

    @Test fun cardEdgeExitCancellationAndOldWideZoneCannotTriggerASwitch() = scene { s ->
        s.onMain {
            installCardDrag()
            requireNotNull(cardDrag).start()
            requireNotNull(cardDrag).hover(pager.width * .97f)
            requireNotNull(cardDrag).leave()
        }
        SystemClock.sleep(CardMoveEdgeDwell.DWELL_MS + 100)
        s.onMain {
            assertTrue(cardRequests.isEmpty())
            requireNotNull(cardDrag).hover(pager.width * .82f)
        }
        SystemClock.sleep(CardMoveEdgeDwell.DWELL_MS + 100)
        s.onMain {
            assertTrue(cardRequests.isEmpty())
            requireNotNull(cardDrag).hover(pager.width * .97f)
            requireNotNull(cardDrag).cancel()
        }
        SystemClock.sleep(CardMoveEdgeDwell.DWELL_MS + 100)
        s.onMain {
            assertTrue(cardRequests.isEmpty())
            assertEquals(0, pager.currentItem)
            assertFalse(requireNotNull(cardDrag).isDragging)
        }
    }

    @Test fun cardDragPendingPreparationAndRtlFlowBoundariesAreRespected() = scene(rtl = true, pageCount = 2) { s ->
        var preparing = true
        var releasedAt = 0L
        s.onMain {
            installCardDrag { if (preparing) 1 else motion.requestedTarget }
            requireNotNull(cardDrag).start()
            requireNotNull(cardDrag).hover(pager.width * .97f)
        }
        SystemClock.sleep(CardMoveEdgeDwell.DWELL_MS + 100)
        s.onMain {
            assertTrue(cardRequests.isEmpty())
            requireNotNull(cardDrag).hover(pager.width * .03f)
        }
        SystemClock.sleep(CardMoveEdgeDwell.DWELL_MS + 100)
        s.onMain {
            assertTrue(cardRequests.isEmpty())
            preparing = false
            releasedAt = SystemClock.uptimeMillis()
        }
        s.await(60_000) { s.pager.currentItem == 1 && s.motion.requestedTarget == null &&
            s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
        SystemClock.sleep(CardMoveEdgeDwell.DWELL_MS + 100)
        s.onMain {
            assertEquals(listOf(1), cardRequests.map { it.first })
            assertTrue(cardRequests.single().second - releasedAt >= CardMoveEdgeDwell.DWELL_MS)
            requireNotNull(cardDrag).cancel()
        }
    }

    @Test fun realLongPressCardDragWaitsAtTheEdgeAndDropsIntoItsPendingFlow() = scene { s ->
        lateinit var handle: View
        s.onMain {
            blockWindPreparation = true
            installCardDrag()
            handle = View(activity).apply {
                setBackgroundColor(android.graphics.Color.WHITE)
                setOnLongClickListener { view ->
                    view.startDragAndDrop(ClipData.newPlainText(CARD_MOVE_DRAG_LABEL, "42"),
                        View.DragShadowBuilder(view), 42L, View.DRAG_FLAG_OPAQUE)
                }
            }
            root.addView(handle, FrameLayout.LayoutParams(dp(48), dp(48), android.view.Gravity.CENTER))
        }
        s.frames(3)
        var center = PointF()
        var edge = PointF()
        s.onMain {
            val location = IntArray(2)
            handle.getLocationOnScreen(location)
            center = PointF(location[0] + handle.width / 2f, location[1] + handle.height / 2f)
            pager.getLocationOnScreen(location)
            edge = PointF(location[0] + pager.width * .97f, center.y)
        }
        val pointer = NativeCardPointer(center)
        try {
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150L)
            s.await { requireNotNull(s.cardDrag).isDragging }
            pointer.move(edge)
            pointer.move(center)
            SystemClock.sleep(CardMoveEdgeDwell.DWELL_MS + 100)
            s.onMain { assertTrue(cardRequests.isEmpty()) }
            pointer.move(edge)
            s.await { s.cardRequests.size == 1 }
            s.onMain {
                assertEquals(1, motion.requestedTarget)
                assertEquals(0, pager.currentItem)
            }
            pointer.up()
            s.await { s.cardDrops == listOf(1) && !requireNotNull(s.cardDrag).isDragging }
            s.onMain {
                assertEquals("Dropping must not force the wind to finish", 1, motion.requestedTarget)
                blockWindPreparation = false
            }
            s.await(60_000) { s.pager.currentItem == 1 && s.motion.requestedTarget == null &&
                s.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE }
            SystemClock.sleep(CardMoveEdgeDwell.DWELL_MS + 100)
            s.onMain { assertEquals(listOf(1), cardRequests.map { it.first }) }

            var cancelledResult: Boolean? = null
            s.onMain {
                handle.setOnDragListener { _, event ->
                    if (event.action == android.view.DragEvent.ACTION_DRAG_ENDED) {
                        cancelledResult = event.result
                    }
                    event.action != android.view.DragEvent.ACTION_DROP
                }
            }
            val cancelledPointer = NativeCardPointer(center)
            try {
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150L)
                s.await { requireNotNull(s.cardDrag).isDragging }
                cancelledPointer.up()
                s.await { cancelledResult == false && !requireNotNull(s.cardDrag).isDragging }
                SystemClock.sleep(CardMoveEdgeDwell.DWELL_MS + 100)
                s.onMain {
                    assertEquals(listOf(1), cardRequests.map { it.first })
                    assertEquals(listOf(1), cardDrops)
                }
            } finally {
                cancelledPointer.up()
            }
        } finally {
            pointer.up()
        }
    }

    @Test fun independentPhotoCachingPreservesNativeWindPixels() = scene { s ->
        val layers = mutableMapOf<View, Int>()
        s.onMain {
            for (page in 0..1) for (card in cards(page)) {
                for (id in listOf(R.id.previewImage, R.id.bgImage, R.id.cardPreview, R.id.card)) {
                    val view = card.findViewById<View>(id)
                    layers[view] = view.layerType
                }
            }
        }
        s.moveTo(.4f)
        s.frames(3)
        val cached = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            s.onMain { layers.forEach { (view, layer) -> view.setLayerType(layer, null) } }
            s.frames(4)
            val original = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                s.assertNativePixels(cached, original, "photo cache")
            } finally {
                original.recycle()
            }
        } finally {
            cached.recycle()
            s.moveTo(0f)
            s.finish(0)
        }
    }

    @Test fun canvasWindOpacityRetainsNativeWholeCardCompositing() = scene { s ->
        s.moveTo(.45f)
        s.frames(3)
        val canvasAlpha = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val opacity = mutableMapOf<TimescapeCardLayout, Float>()
        try {
            s.onMain {
                for (page in 0..1) for (card in cards(page)) {
                    opacity[card] = card.windAlpha
                    card.setWindAlpha(1f)
                    card.setTransitionAlpha(opacity.getValue(card))
                }
            }
            s.frames(3)
            val nativeAlpha = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                // Native layers rasterize before perspective; canvas groups rasterize in device space.
                s.assertNativePixels(canvasAlpha, nativeAlpha, "native/canvas opacity", 1.0, .01)
            } finally {
                nativeAlpha.recycle()
            }
        } finally {
            canvasAlpha.recycle()
            s.onMain { opacity.forEach { (card, alpha) -> card.setTransitionAlpha(1f); card.setWindAlpha(alpha) } }
            s.moveTo(0f)
            s.finish(0)
        }
    }

    @Test fun windReusesStaticCardFacesWithoutRedrawingTheirPhotographsAndText() = scene { s ->
        val text = mutableListOf<TimescapeTextView>()
        val normalLayers = mutableMapOf<View, Int>()
        s.onMain {
            for (page in 0..1) for (card in cards(page)) {
                for (id in listOf(R.id.cardPreview, R.id.previewText, R.id.card)) {
                    val view = card.findViewById<View>(id)
                    normalLayers[view] = view.layerType
                }
                if (card.expansionProgress == 1f) {
                    text.add(card.findViewById(R.id.snippet))
                    text.add(card.findViewById(R.id.time))
                } else if (card.findViewById<View>(R.id.cardPreview).layerType == View.LAYER_TYPE_HARDWARE) {
                    text.add(card.findViewById(R.id.previewTime))
                }
            }
        }
        s.moveTo(.35f)
        s.frames(3)
        var firstDraws = emptyList<Int>()
        s.onMain {
            firstDraws = text.map { it.drawPassCount }
            for (page in 0..1) for (card in cards(page)) {
                val face = card.findViewById<View>(
                    if (card.expansionProgress == 1f) R.id.card else R.id.cardPreview)
                if (card.expansionProgress == 1f || normalLayers[face] == View.LAYER_TYPE_HARDWARE) {
                    assertEquals(View.LAYER_TYPE_HARDWARE, face.layerType)
                }
            }
        }
        s.moveTo(.55f)
        s.moveTo(.35f)
        s.onMain { assertEquals("Wind rebuilt unchanged face lettering", firstDraws, text.map { it.drawPassCount }) }
        s.moveTo(0f)
        s.finish(0)
        s.onMain {
            normalLayers.forEach { (view, layer) ->
                assertEquals("Temporary face caching leaked after landing", layer, view.layerType)
            }
        }
    }

    @Test fun videoSurfacesRetainNativeOpacityWithoutWholeCardRasterCaching() = scene(media = true) { s ->
        s.onMain {
            for (page in 0..1) for (card in cards(page)) {
                card.configurePreview(true, video = true)
                card.findViewById<View>(R.id.videoInlineView).visibility = View.VISIBLE
            }
        }
        s.frames(3)
        s.moveTo(.45f)
        s.onMain {
            for (page in 0..1) for (card in cards(page)) {
                assertEquals(View.LAYER_TYPE_NONE, card.layerType)
                assertEquals(card.windAlpha, card.transitionAlpha, 0f)
            }
            val rebound = cards(0).first { it.expansionProgress == 1f }
            rebound.findViewById<View>(R.id.videoInlineView).visibility = View.GONE
            rebound.configurePreview(true)
            rebound.setRenderCaching(true)
            assertEquals(View.LAYER_TYPE_HARDWARE, rebound.layerType)
        }
        s.moveTo(0f)
        s.finish(0)
        s.onMain {
            for (page in 0..1) for (card in cards(page)) {
                assertEquals(View.LAYER_TYPE_NONE, card.layerType)
                assertEquals(1f, card.transitionAlpha, 0f)
            }
        }
    }

    @Test fun mediaWindCachesWholeCardsWithoutBlankingPicturesOrChangingTheirImageFit() = scene(media = true) { s ->
        lateinit var image: ImageView
        lateinit var bitmap: Bitmap
        val matrix = Matrix()
        var pose = FloatArray(0)
        s.await {
            s.cards(0).first { it.expansionProgress == 1f }
                .findViewById<ImageView>(R.id.imageCard).drawable is BitmapDrawable
        }
        s.onMain {
            val card = cards(0).first { it.expansionProgress == 1f }
            image = card.findViewById(R.id.imageCard)
            bitmap = (image.drawable as BitmapDrawable).bitmap
            matrix.set(image.imageMatrix)
            pose = layoutPose(image)
        }
        s.moveTo(.45f)
        s.frames(3)
        s.onMain {
            assertTrue(cards(0).any { it.layerType == View.LAYER_TYPE_HARDWARE })
            assertSame(bitmap, (image.drawable as BitmapDrawable).bitmap)
            assertEquals(matrix, image.imageMatrix)
            assertArrayEquals(pose, layoutPose(image), .001f)
            assertTrue(cards(0).all { it.windAlpha == it.transitionAlpha })
            val card = cards(0).first { it.expansionProgress == 1f }
            card.setFlipLifted(true)
            assertEquals(View.LAYER_TYPE_NONE, card.layerType)
            card.setFlipLifted(false)
            assertEquals(View.LAYER_TYPE_HARDWARE, card.layerType)
        }
        s.frames(3)
        val cached = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            s.onMain { for (page in 0..1) for (card in cards(page)) card.setRenderCaching(false) }
            s.frames(3)
            val original = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                s.assertNativePixels(cached, original, "media cache")
            } finally {
                original.recycle()
            }
        } finally {
            cached.recycle()
        }
        s.moveTo(.7f)
        s.moveTo(0f)
        s.finish(0)
        s.onMain {
            for (page in 0..1) for (card in cards(page)) {
                assertEquals(View.LAYER_TYPE_NONE, card.layerType)
                assertEquals(1f, card.windAlpha, 0f)
                assertEquals(1f, card.transitionAlpha, 0f)
            }
            assertSame(bitmap, (image.drawable as BitmapDrawable).bitmap)
            assertEquals(matrix, image.imageMatrix)
        }
    }

    private fun scene(rtl: Boolean = false, pageCount: Int = 3, media: Boolean = false, test: (Scene) -> Unit) {
        ActivityScenario.launch(TimescapeFlowTestActivity::class.java).use { scenario ->
            lateinit var scene: Scene
            scenario.onActivity {
                scene = Scene(it, rtl, pageCount, media)
                it.setContentView(scene.root)
            }
            try {
                scene.await {
                    scene.recyclers.size >= 2 && scene.cards(0).size >= 4 &&
                        scene.cards(1).size >= 4 && scene.managers.getValue(0).isFocused(3) &&
                        (!media || FlowPagerMotionController.isPageReady(scene.page(0)) &&
                            FlowPagerMotionController.isPageReady(scene.page(1)))
                }
                scene.frames(8)
                if (InstrumentationRegistry.getArguments().getString("profileFlowSwitch") == "true") {
                    Debug.startMethodTracingSampling(
                        File(scene.activity.cacheDir, "flow-switch-pacing.trace").path,
                        8 * 1024 * 1024, 1000)
                }
                test(scene)
            } finally {
                if (InstrumentationRegistry.getArguments().getString("profileFlowSwitch") == "true") {
                    Debug.stopMethodTracing()
                }
                scenario.onActivity {
                    scene.cardDrag?.dispose()
                    if (scene.pager.isFakeDragging) scene.pager.endFakeDrag()
                    scene.motion.dispose()
                    scene.pager.adapter = null
                }
            }
        }
    }

    private class Scene(val activity: TimescapeFlowTestActivity, private val rtl: Boolean, pageCount: Int,
        media: Boolean
    ) {
        val root = FrameLayout(activity).apply {
            setBackgroundResource(R.drawable.bg_app_cosmic_flow)
            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        val pager = ViewPager2(activity)
        val recyclers = mutableMapOf<Int, RecyclerView>()
        val managers = mutableMapOf<Int, RightRailFlowLayoutManager>()
        val requests = mutableListOf<Int?>()
        val motion: FlowPagerMotionController
        var cardDrag: CardMovePagerDragController? = null
        val cardRequests = mutableListOf<Pair<Int, Long>>()
        val cardLandings = mutableListOf<Long>()
        val cardDrops = mutableListOf<Int>()
        var blockWindPreparation = false
        private var fraction = 0f
        private var touchStarted = 0L

        init {
            root.addView(pager, FrameLayout.LayoutParams(-1, -1))
            pager.offscreenPageLimit = 1
            pager.setPageTransformer(FlowWindPageTransformer())
            pager.adapter = object : RecyclerView.Adapter<Page>() {
                override fun onCreateViewHolder(parent: ViewGroup, type: Int): Page {
                    val view = activity.layoutInflater.inflate(R.layout.page_card_flow, parent, false)
                    return Page(view)
                }

                override fun getItemCount() = pageCount

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
                            bg = if (media) null else BgImage.Res(
                                if (position % 2 == 0) R.drawable.bg_2 else R.drawable.bg_3),
                            image = if (media) CardImage(Uri.parse(
                                "android.resource://${activity.packageName}/${R.drawable.bg_2}")) else null)
                    }) { manager.restoreState(3, true) }
                }
                override fun onViewRecycled(holder: Page) {
                    holder.itemView.findViewById<RecyclerView>(R.id.recyclerFlowCards).adapter = null
                }
            }
            motion = FlowPagerMotionController(pager,
                pageReady = { !blockWindPreparation && FlowPagerMotionController.isPageReady(it) },
                onRequestChanged = {
                    requests.add(it)
                    if (it == null) cardLandings.add(SystemClock.uptimeMillis())
                })
        }

        fun installCardDrag(pending: () -> Int? = { motion.requestedTarget }) {
            cardDrag = CardMovePagerDragController(pager,
                currentIndex = { pager.currentItem },
                flowCount = { requireNotNull(pager.adapter).itemCount },
                requestedTarget = pending,
                navigate = {
                    cardRequests.add(it to SystemClock.uptimeMillis())
                    motion.navigateTo(it)
                },
                onDrop = { event, target ->
                    assertEquals(42L, event.localState)
                    cardDrops.add(target)
                    true
                })
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

        fun assertNativePixels(firstImage: Bitmap, secondImage: Bitmap, label: String,
            maximumMean: Double = .5, maximumLargeFraction: Double = .001
        ) {
            val area = IntArray(4)
            onMain {
                val location = IntArray(2)
                root.getLocationOnScreen(location)
                area[0] = location[0]
                area[1] = location[1]
                area[2] = root.width
                area[3] = root.height
            }
            val first = IntArray(area[2] * area[3])
            val second = IntArray(first.size)
            firstImage.getPixels(first, 0, area[2], area[0], area[1], area[2], area[3])
            secondImage.getPixels(second, 0, area[2], area[0], area[1], area[2], area[3])
            var difference = 0L
            var changed = 0
            val shifts = intArrayOf(0, 8, 16)
            for (index in first.indices) {
                var peak = 0
                for (shift in shifts) {
                    val delta = abs(((first[index] ushr shift) and 255) -
                        ((second[index] ushr shift) and 255))
                    difference += delta
                    peak = maxOf(peak, delta)
                }
                if (peak > 20) changed++
            }
            val mean = difference.toDouble() / (first.size * 3)
            val fraction = changed.toDouble() / first.size
            Log.i("FlowSwitchMetrics", "$label pixel mean=$mean largeDiffFraction=$fraction")
            assertTrue("$label changed native appearance: $mean", mean < maximumMean)
            assertTrue("$label changed native pictures/edges: $fraction", fraction < maximumLargeFraction)
        }

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

        fun touch(action: Int, progress: Float) = onMain {
            if (action == MotionEvent.ACTION_DOWN) touchStarted = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(touchStarted, SystemClock.uptimeMillis(), action,
                pager.width * (.97f - progress), pager.height * .86f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(root.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
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

        fun await(timeoutMs: Long = 15_000, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
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

    private class NativeCardPointer(private var position: PointF) {
        private val downTime = SystemClock.uptimeMillis()
        private var finished = false
        init { send(MotionEvent.ACTION_DOWN) }
        fun move(target: PointF) {
            val start = position
            for (step in 1..6) {
                val progress = step / 6f
                position = PointF(start.x + (target.x - start.x) * progress,
                    start.y + (target.y - start.y) * progress)
                send(MotionEvent.ACTION_MOVE)
                SystemClock.sleep(16)
            }
        }
        fun up() {
            if (finished) return
            finished = true
            send(MotionEvent.ACTION_UP)
        }
        private fun send(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, position.x, position.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
    }
}
