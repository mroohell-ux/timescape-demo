package com.example.timescapedemo

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GestureCardFlipTest {
    @Test fun shortSlowDragReturnsWithoutChangingFace() = scene { s ->
        s.onMain {
            val stroke = stroke()
            stroke.move(.15f, 200)
            stroke.move(.30f, 500)
            assertEquals(.30f, holder().flip.flipProgress, .025f)
            assertEquals(HandwritingFace.FRONT, adapter.currentFaceFor(0))
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                holder().flip.importantForAccessibility)
            stroke.up(900)
        }
        s.await { !s.holder().flip.isFlipping }
        s.onMain {
            assertEquals(HandwritingFace.FRONT, adapter.currentFaceFor(0))
            assertEquals(0f, holder().flip.flipProgress, 0f)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, holder().flip.importantForAccessibility)
        }
    }

    @Test fun dragPastMidpointCommitsOnlyAfterReleaseAndWorksBothDirections() = scene { s ->
        for (sign in listOf(1f, -1f)) {
            s.onMain {
                val stroke = stroke(sign)
                stroke.move(sign * .2f, 200)
                stroke.move(sign * .7f, 1000)
                assertEquals(if (sign > 0) HandwritingFace.FRONT else HandwritingFace.BACK,
                    adapter.currentFaceFor(0))
                stroke.up(1400)
            }
            s.await { !s.holder().flip.isFlipping }
            s.onMain {
                assertEquals(if (sign > 0) HandwritingFace.BACK else HandwritingFace.FRONT,
                    adapter.currentFaceFor(0))
                assertEquals(if (sign > 0) "Back side" else "Front side",
                    holder().snippet.text.toString())
                assertEquals(0, root.interceptions)
            }
        }
    }

    @Test fun quickShortFlickCompletes() = scene { s ->
        s.onMain {
            val stroke = stroke()
            stroke.move(.08f, 10)
            stroke.move(.3f, 30)
            stroke.up(31)
        }
        s.await { s.adapter.currentFaceFor(0) == HandwritingFace.BACK && !s.holder().flip.isFlipping }
    }

    @Test fun reverseFlickReturnsEvenAfterMidpoint() = scene { s ->
        s.onMain {
            val stroke = stroke()
            stroke.move(.2f, 200)
            stroke.move(.75f, 900)
            stroke.move(.72f, 1100)
            stroke.move(.53f, 1120)
            stroke.up(1121)
        }
        s.await { !s.holder().flip.isFlipping }
        s.onMain { assertEquals(HandwritingFace.FRONT, adapter.currentFaceFor(0)) }
    }

    @Test fun cancelAndRebindDiscardTransientFace() = scene { s ->
        s.onMain {
            val stroke = stroke()
            stroke.move(.2f, 200)
            stroke.move(.7f, 800)
            stroke.cancel(850)
        }
        s.await { !s.holder().flip.isFlipping }
        s.onMain {
            assertEquals(HandwritingFace.FRONT, adapter.currentFaceFor(0))
            val stroke = stroke()
            stroke.move(.2f, 200)
            stroke.move(.7f, 800)
            adapter.notifyItemChanged(0)
        }
        s.await { !s.holder().flip.isFlipping }
        s.onMain { adapter.toggleCardFace(holder()) }
        s.await { s.adapter.currentFaceFor(0) == HandwritingFace.BACK && !s.holder().flip.isFlipping }
    }

    @Test fun tappingAnimationDoesNotFreezeIt() = scene { s ->
        s.onMain {
            adapter.toggleCardFace(holder())
            stroke().up(80)
        }
        s.await { s.adapter.currentFaceFor(0) == HandwritingFace.BACK && !s.holder().flip.isFlipping }
    }

    @Test fun verticalGestureStillBrowsesOpenCards() = scene { s ->
        s.onMain {
            val stroke = stroke()
            stroke.vertical(-dp(40).toFloat(), 100)
            stroke.vertical(-dp(100).toFloat(), 200)
            stroke.vertical(-dp(220).toFloat(), 350)
            stroke.vertical(-dp(380).toFloat(), 500)
            stroke.up(800)
        }
        s.await { s.manager.currentSelectionIndex()?.let { it > 0 && s.manager.isFocused(it) } == true }
        s.onMain {
            assertEquals(HandwritingFace.FRONT, adapter.currentFaceFor(0))
            assertEquals(0, root.interceptions)
        }
    }

    @Test fun handwritingAndImageBackLoadRealAssetsAndStayReadable() = scene { s ->
        for (position in listOf(1, 2)) {
            s.focus(position)
            s.onMain { adapter.toggleCardFace(holder()) }
            s.await {
                s.adapter.currentFaceFor(position) == HandwritingFace.BACK && !s.holder().flip.isFlipping
            }
            s.onMain {
                val bitmap = (holder().handwriting.drawable as BitmapDrawable).bitmap
                assertEquals(Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                assertEquals(HandwritingFace.BACK, holder().boundFace)
                adapter.toggleCardFace(holder())
            }
            s.await {
                s.adapter.currentFaceFor(position) == HandwritingFace.FRONT && !s.holder().flip.isFlipping
            }
        }
    }

    @Test fun receiptKeepsRibbonOccupancyUntilLanding() = scene { s ->
        s.focus(3)
        var initialHeight = 0
        var neighborTop = 0
        s.onMain {
            initialHeight = holder().flip.height
            neighborTop = requireNotNull(manager.findViewByPosition(4)).top
        }
        s.onMain {
            val stroke = stroke()
            stroke.move(.2f, 200)
            stroke.move(.65f, 900)
        }
        s.await { s.holder().flip.liftFraction > .95f }
        s.onMain {
            assertEquals(initialHeight, holder().flip.height)
            assertEquals(neighborTop, requireNotNull(manager.findViewByPosition(4)).top)
            assertTrue(holder().flip.height > holder().card.height)
            assertEquals(HandwritingFace.FRONT, adapter.currentFaceFor(3))
        }
        s.onMain { holder().flip.cancelInteraction(); adapter.toggleCardFace(holder()) }
        s.await { s.adapter.currentFaceFor(3) == HandwritingFace.BACK && !s.holder().flip.isFlipping }
        s.onMain { assertEquals(View.GONE, holder().receipt.visibility) }
        s.onMain { adapter.toggleCardFace(holder()) }
        s.await { s.adapter.currentFaceFor(3) == HandwritingFace.FRONT && !s.holder().flip.isFlipping }
        s.onMain {
            assertEquals(View.VISIBLE, holder().receipt.visibility)
            val bitmap = (holder().receipt.drawable as BitmapDrawable).bitmap
            assertEquals(Color.GREEN, bitmap.getPixel(0, 0))
        }
    }

    @Test fun blankHandwritingBackCanReturnToFront() = scene { s ->
        s.focus(4)
        s.onMain { adapter.toggleCardFace(holder()) }
        s.await { s.adapter.currentFaceFor(4) == HandwritingFace.BACK && !s.holder().flip.isFlipping }
        s.onMain { adapter.toggleCardFace(holder()) }
        s.await { s.adapter.currentFaceFor(4) == HandwritingFace.FRONT && !s.holder().flip.isFlipping }
    }

    @Test fun renderedBackIsNotMirrored() = scene { s ->
        s.focus(1)
        repeat(2) { index ->
            s.onMain { adapter.toggleCardFace(holder()) }
            s.await {
                s.adapter.currentFaceFor(1) == (if (index == 0) HandwritingFace.BACK else HandwritingFace.FRONT) &&
                    !s.holder().flip.isFlipping
            }
        }
        s.onMain {
            val stroke = stroke()
            stroke.move(.2f, 200)
            stroke.move(1f, 900)
        }
        s.onMain {
            val flip = holder().flip
            assertEquals(1f, flip.flipProgress, .001f)
            val image = Bitmap.createBitmap(flip.width, flip.height, Bitmap.Config.ARGB_8888)
            flip.draw(Canvas(image))
            assertEquals(Color.RED, image.getPixel(image.width / 4, image.height / 2))
            assertEquals(Color.BLUE, image.getPixel(image.width * 4 / 5, image.height / 2))
            image.recycle()
        }
        s.await { s.holder().flip.liftFraction > .95f }
        captureFrame("fingertip-back.png")
        s.onMain { holder().flip.cancelInteraction() }
    }

    @Test fun secondFingerCancelsAndCollapseDoesNotCommit() = scene { s ->
        s.onMain {
            val stroke = stroke()
            stroke.move(.2f, 200)
            stroke.move(.7f, 900)
            stroke.secondFinger(950)
            stroke.up(1000)
        }
        s.await { !s.holder().flip.isFlipping }
        s.onMain {
            assertEquals(HandwritingFace.FRONT, adapter.currentFaceFor(0))
            adapter.toggleCardFace(holder())
            manager.clearFocus()
        }
        s.await { !s.holder().flip.isFlipping }
        s.onMain { assertEquals(HandwritingFace.FRONT, adapter.currentFaceFor(0)) }
        s.focus(0)
        s.onMain { adapter.toggleCardFace(holder()) }
        s.await { s.adapter.currentFaceFor(0) == HandwritingFace.BACK && !s.holder().flip.isFlipping }
    }

    @Test fun longBackGrowsOnlyAfterRelease() = scene { s ->
        s.focus(5)
        lateinit var stroke: Stroke
        var frontHeight = 0
        s.onMain {
            frontHeight = holder().flip.height
            stroke = stroke()
            stroke.move(.2f, 200)
            stroke.move(1f, 1000)
        }
        s.await { s.holder().flip.liftFraction > .95f }
        s.onMain {
            assertEquals(frontHeight, holder().flip.height)
            assertEquals(HandwritingFace.FRONT, adapter.currentFaceFor(5))
            stroke.up(1400)
        }
        s.await { s.adapter.currentFaceFor(5) == HandwritingFace.BACK && !s.holder().flip.isFlipping }
        s.onMain { assertTrue(holder().flip.height > frontHeight) }
    }

    @Test fun contactPositionChangesPickupTilt() = scene { s ->
        s.focus(1)
        s.onMain {
            val stroke = stroke(heightFraction = .2f)
            stroke.move(.1f, 200)
            stroke.move(.32f, 600)
        }
        s.await { s.holder().flip.liftFraction > .95f && s.holder().flip.tiltX > 2f }
        captureFrame("fingertip-upper.png")
        s.onMain {
            holder().flip.cancelInteraction()
            val stroke = stroke(heightFraction = .8f)
            stroke.move(.1f, 200)
            stroke.move(.32f, 600)
        }
        s.await { s.holder().flip.liftFraction > .95f && s.holder().flip.tiltX < -2f }
        captureFrame("fingertip-lower.png")
        s.onMain { holder().flip.cancelInteraction() }
    }

    @Test fun touchingWithoutHorizontalIntentDoesNotLift() = scene { s ->
        lateinit var stroke: Stroke
        s.onMain { stroke = stroke() }
        SystemClock.sleep(120)
        s.onMain {
            assertFalse(holder().flip.isFlipping)
            assertEquals(0f, holder().flip.liftFraction, 0f)
            stroke.cancel(200)
        }
    }

    @Test fun releaseTransfersVelocityIntoSpring() = scene { s ->
        s.onMain {
            val stroke = stroke()
            stroke.move(.08f, 10)
            stroke.move(.3f, 30)
            stroke.up(31)
            assertTrue(holder().flip.turnVelocity > 2f)
        }
        s.await { s.adapter.currentFaceFor(0) == HandwritingFace.BACK && !s.holder().flip.isFlipping }
    }

    @Test fun catchingTurnPreservesPoseAndLetsFingerReverseIt() = scene { s ->
        lateinit var stroke: Stroke
        var caughtProgress = 0f
        var caughtLift = 0f
        var caught = false
        s.onMain {
            onMatchingFrame({ holder().flip.flipProgress in .2f.. .9f && !holder().flip.isLanding }) {
                caughtProgress = holder().flip.flipProgress
                caughtLift = holder().flip.liftFraction
                stroke = stroke()
                assertEquals(caughtProgress, holder().flip.flipProgress, 0f)
                assertEquals(caughtLift, holder().flip.liftFraction, 0f)
                caught = true
            }
            assertNotNull(adapter.toggleCardFace(holder()))
        }
        s.await { caught }
        SystemClock.sleep(120)
        s.onMain {
            assertEquals(caughtProgress, holder().flip.flipProgress, 0f)
            stroke.move(-caughtProgress, 300)
            assertEquals(0f, holder().flip.flipProgress, .001f)
            stroke.up(700)
        }
        s.await { !s.holder().flip.isFlipping }
        s.onMain { assertEquals(HandwritingFace.FRONT, adapter.currentFaceFor(0)) }
    }

    @Test fun landingCanBeCaughtWithoutHeightJumpOrEarlyCommit() = scene { s ->
        s.focus(5)
        lateinit var stroke: Stroke
        var caughtHeight = 0
        var caughtProgress = 0f
        var caught = false
        s.onMain {
            onMatchingFrame({ holder().flip.isLanding && holder().flip.liftFraction > .2f }) {
                caughtHeight = holder().flip.height
                caughtProgress = holder().flip.flipProgress
                stroke = stroke()
                assertEquals(caughtHeight, holder().flip.height)
                assertEquals(caughtProgress, holder().flip.flipProgress, 0f)
                caught = true
            }
            assertNotNull(adapter.toggleCardFace(holder()))
        }
        s.await { caught }
        SystemClock.sleep(100)
        s.onMain {
            assertEquals(caughtHeight, holder().flip.height)
            assertEquals(caughtProgress, holder().flip.flipProgress, 0f)
            assertEquals(HandwritingFace.FRONT, adapter.currentFaceFor(5))
            stroke.up(300)
        }
        s.await { s.adapter.currentFaceFor(5) == HandwritingFace.BACK && !s.holder().flip.isFlipping }
    }

    private fun captureFrame(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureFlip") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun scene(test: (Scene) -> Unit) {
        ActivityScenario.launch(TimescapeFlowTestActivity::class.java).use { scenario ->
            lateinit var scene: Scene
            scenario.onActivity { activity ->
                scene = Scene(activity)
                activity.setContentView(scene.root)
            }
            try {
                scene.await { scene.recycler.childCount > 0 }
                scene.focus(0)
                test(scene)
            } finally {
                scenario.onActivity { scene.dispose() }
                scene.files.forEach { it.delete() }
            }
        }
    }

    private class Pager(activity: TimescapeFlowTestActivity) : FrameLayout(activity) {
        var interceptions = 0
        private var x = 0f
        private var y = 0f
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { x = event.x; y = event.y }
            if (event.actionMasked == MotionEvent.ACTION_MOVE &&
                abs(event.x - x) > ViewConfiguration.get(context).scaledTouchSlop &&
                abs(event.x - x) > abs(event.y - y)
            ) {
                interceptions++
                return true
            }
            return false
        }
    }

    private class Scene(activity: TimescapeFlowTestActivity) {
        val root = Pager(activity)
        val recycler = RecyclerView(activity)
        val manager = RightRailFlowLayoutManager(dp(340), dp(360), dp(138), dp(8))
        val files = mutableListOf<File>()
        private val frameListeners = mutableListOf<ViewTreeObserver.OnPreDrawListener>()
        val adapter = CardsAdapter(TintStyle.None,
            onItemClick = {
                if (manager.isFocused(it)) adapterFlip(it) else manager.focus(it)
            },
            onItemDoubleClick = { _, _ -> },
            onItemLongPress = { _, _ -> false },
            onStickyNotesClick = {},
            onItemCollapse = { manager.clearFocus() })

        init {
            fun photo(color: Int): File {
                val file = File.createTempFile("gesture-flip-", ".png", activity.filesDir)
                val bitmap = Bitmap.createBitmap(300, 450, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                if (color == Color.RED) {
                    Canvas(bitmap).drawRect(200f, 0f, 300f, 450f, Paint().apply { this.color = Color.BLUE })
                }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                files += file
                return file
            }
            val front = photo(Color.GREEN)
            val back = photo(Color.RED)
            val options = HandwritingOptions(Color.WHITE, Color.BLACK, 2f, 300, 450,
                HandwritingFormat.PNG, HandwritingPaperStyle.PLAIN, HandwritingPenType.ROUND,
                10f, HandwritingEraserType.ROUND)
            val side = HandwritingSide(back.name, options)
            recycler.layoutManager = manager
            recycler.clipChildren = false
            recycler.clipToPadding = false
            recycler.itemAnimator = null
            recycler.adapter = adapter
            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) manager.settleScrollIfNeeded()
                }
            })
            root.addView(recycler, FrameLayout.LayoutParams(-1, -1))
            adapter.submitList(listOf(
                CardItem(101, "Two sides", "Front side", backSnippet = "Back side", bg = BgImage.Res(R.drawable.bg_2)),
                CardItem(102, "Handwriting", "", handwriting = HandwritingContent(front.name, options, back = side)),
                CardItem(103, "Image", "", image = CardImage(Uri.fromFile(front)), imageHandwriting = side),
                CardItem(104, "Receipt", "Front side", backSnippet = "Back side",
                    thermalReceipt = ThermalReceipt(HandwritingSide(front.name, options))),
                CardItem(105, "Blank back", "", handwriting = HandwritingContent(front.name, options)),
                CardItem(106, "Long back", "Short front", backSnippet = "A much longer reverse side. ".repeat(200),
                    bg = BgImage.Res(R.drawable.bg_3))
            )) { manager.restoreState(0, false) }
        }

        private fun adapterFlip(index: Int) {
            adapter.toggleCardFace(recycler.findViewHolderForAdapterPosition(index) as CardsAdapter.VH)
        }
        fun dp(value: Int) = (value * root.resources.displayMetrics.density).toInt()
        fun holder() = recycler.findViewHolderForAdapterPosition(requireNotNull(manager.currentSelectionIndex())) as CardsAdapter.VH
        fun onMain(block: Scene.() -> Unit) =
            InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
        fun onMatchingFrame(condition: Scene.() -> Boolean, action: Scene.() -> Unit) {
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (condition(this@Scene)) {
                        root.viewTreeObserver.removeOnPreDrawListener(this)
                        frameListeners.remove(this)
                        action(this@Scene)
                    }
                    return true
                }
            }
            frameListeners.add(listener)
            root.viewTreeObserver.addOnPreDrawListener(listener)
        }
        fun dispose() {
            frameListeners.forEach { root.viewTreeObserver.removeOnPreDrawListener(it) }
            frameListeners.clear()
            recycler.adapter = null
        }
        fun focus(position: Int) {
            onMain { manager.restoreState(position, false); manager.focus(position) }
            await {
                manager.isFocused(position) && holder().presentation.expansionProgress == 1f &&
                    holder().flip.height > 0 && holder().flip.canFlip()
            }
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                onMain { ready = condition() }
                if (ready) return
                SystemClock.sleep(40)
            }
            var state = ""
            onMain {
                state = "selection=${manager.currentSelectionIndex()}, " +
                    "focused=${manager.currentSelectionIndex()?.let(manager::isFocused)}, " +
                    "face=${manager.currentSelectionIndex()?.let(adapter::currentFaceFor)}, " +
                    "progress=${holder().flip.flipProgress}, lift=${holder().flip.liftFraction}, " +
                    "landing=${holder().flip.isLanding}, " +
                    "expansion=${holder().presentation.expansionProgress}"
            }
            throw AssertionError("Flip did not settle or content was not ready: $state")
        }
        fun stroke(sign: Float = 1f, heightFraction: Float? = null) = Stroke(root, holder().flip, sign, heightFraction)
    }

    private class Stroke(
        private val root: FrameLayout,
        flip: GestureCardFlipLayout,
        sign: Float,
        heightFraction: Float?
    ) {
        private val started = SystemClock.uptimeMillis()
        private val distance = flip.width * .85f
        private val bounds = Rect().also { flip.getDrawingRect(it); root.offsetDescendantRectToMyCoords(flip, it) }
        private val startX = bounds.left + flip.width * if (sign > 0) .1f else .9f
        private var x = startX
        private var y = bounds.top + (heightFraction?.let { flip.height * it } ?: minOf(flip.height / 2f, 180f))
        init { send(MotionEvent.ACTION_DOWN, 0) }
        fun move(progress: Float, time: Long) { x = startX + progress * distance; send(MotionEvent.ACTION_MOVE, time) }
        fun vertical(delta: Float, time: Long) { y = bounds.top + minOf(bounds.height() / 2f, 180f) + delta; send(MotionEvent.ACTION_MOVE, time) }
        fun up(time: Long) = send(MotionEvent.ACTION_UP, time)
        fun cancel(time: Long) = send(MotionEvent.ACTION_CANCEL, time)
        fun secondFinger(time: Long) {
            val properties = Array(2) { index ->
                MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER }
            }
            val coordinates = Array(2) { index ->
                MotionEvent.PointerCoords().apply {
                    x = this@Stroke.x + index * 10
                    y = this@Stroke.y
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(started, started + time,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
            root.dispatchTouchEvent(event)
            event.recycle()
        }
        private fun send(action: Int, time: Long) {
            val event = MotionEvent.obtain(started, started + time, action, x, y, 0)
            root.dispatchTouchEvent(event)
            event.recycle()
        }
    }
}
