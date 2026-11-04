package com.example.timescapedemo

import android.animation.ValueAnimator
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FlowChipDragTest {
    @Test fun longPressThenGapCommitsOnlyOnRelease() = scene { s ->
        val initialY = s.point(1).y
        val drag = s.press(1)
        s.onMain {
            assertEquals(initialY, pointOnMain(1).y, 1f)
            assertEquals(listOf(1L, 2L, 3L), order)
        }
        drag.move(s.gap(2))
        s.await { s.hint.text == s.activity.getString(R.string.flow_drag_reorder_hint) }
        s.onMain { assertEquals(listOf(1L, 2L, 3L), order) }
        drag.up()
        s.await { s.order == listOf(2L, 1L, 3L) }
        s.onMain {
            assertEquals(1, reorders)
            assertEquals(2L, selected)
            assertFalse(controller.isDragging)
            assertEquals(View.GONE, hint.visibility)
        }
    }

    @Test fun centerDwellRequiresConfirmationAndCanBeCancelled() = scene { s ->
        val drag = s.press(1)
        drag.move(s.point(2))
        s.awaitMergeReady(2)
        drag.up()
        s.await { s.dialog?.isShowing == true }
        s.onMain {
            assertEquals(listOf(1L, 2L, 3L), order)
            assertEquals(1, requests)
            assertEquals(0, confirmations)
            dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.performClick()
        }
        s.await { s.dialog?.isShowing == false }
        s.onMain {
            assertEquals(listOf(1L, 2L, 3L), order)
            assertEquals(0, confirmations)
        }
    }

    @Test fun confirmedMergeUsesStableIdsAndKeepsAllCards() = scene { s ->
        val drag = s.press(1)
        drag.move(s.point(2))
        s.awaitMergeReady(2)
        drag.up()
        s.await { s.dialog?.isShowing == true }
        s.onMain {
            order.reverse()
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
        }
        s.await { s.confirmations == 1 }
        s.onMain {
            assertEquals(listOf(3L, 2L), order)
            assertEquals(3, cards[2])
            assertEquals(6, cards.values.sum())
            assertEquals(1, confirmations)
            assertEquals(0, reorders)
        }
    }

    @Test fun earlyCenterReleaseDoesNothing() = scene { s ->
        val drag = s.press(1)
        drag.move(s.point(2), steps = 2)
        drag.up()
        s.await { !s.controller.isDragging }
        s.onMain {
            assertEquals(listOf(1L, 2L, 3L), order)
            assertEquals(0, requests)
            assertEquals(0, reorders)
        }
    }

    @Test fun movingAwayFromReadyMergeRevertsToInsertion() = scene { s ->
        val drag = s.press(1)
        drag.move(s.point(2))
        s.awaitMergeReady(2)
        drag.move(s.gap(2))
        s.await { s.hint.text == s.activity.getString(R.string.flow_drag_reorder_hint) }
        drag.up()
        s.await { s.order == listOf(2L, 1L, 3L) }
        s.onMain { assertEquals(0, requests) }
    }

    @Test fun outsideReleaseCancelsWithoutChangingOrder() = scene { s ->
        val drag = s.press(1)
        drag.move(s.gap(2))
        val outside = s.point(2).apply { y -= s.dp(150) }
        drag.move(outside)
        drag.up()
        s.await { !s.controller.isDragging }
        s.onMain {
            assertEquals(listOf(1L, 2L, 3L), order)
            assertEquals(0, requests)
            assertEquals(0, reorders)
            assertEquals(1f, chip(1).alpha, 0f)
        }
    }

    @Test fun cancellationDisarmsPendingMerge() = scene { s ->
        val drag = s.press(1)
        drag.move(s.point(2))
        s.onMain { controller.cancel() }
        drag.up()
        SystemClock.sleep(FlowDragSession.MERGE_DWELL_MS + 100)
        s.onMain {
            assertFalse(controller.isDragging)
            assertEquals(View.GONE, hint.visibility)
            assertEquals(1f, chip(2).scaleX, 0f)
            assertEquals(0, requests)
            assertEquals(listOf(1L, 2L, 3L), order)
        }
    }

    @Test fun edgeScrollingCanReachOffscreenFlow() = scene(count = 9) { s ->
        val drag = s.press(1)
        val edge = s.onMainPoint {
            val location = IntArray(2)
            scroll.getLocationOnScreen(location)
            PointF(location[0] + scroll.width - dp(6), pointOnMain(1).y)
        }
        drag.move(edge)
        s.await { s.scroll.scrollX > 0 && !s.scroll.canScrollHorizontally(1) }
        drag.move(s.point(9))
        s.awaitMergeReady(9)
        drag.up()
        s.await { s.dialog?.isShowing == true }
        s.onMain {
            assertEquals(1, requests)
            assertEquals(9, order.size)
            dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.performClick()
        }
    }

    @Test fun tapsStillSelectAndDrawerHasNoModeSwitch() = scene { s ->
        repeat(2) {
            val tap = s.touch(2)
            SystemClock.sleep(35)
            tap.up()
            SystemClock.sleep(60)
        }
        s.onMain {
            assertEquals(2, taps)
            assertEquals(2L, selected)
            assertFalse(controller.isDragging)
            fun hasSwitch(view: View): Boolean =
                (view is TextView && view.text.toString().startsWith("Reorder flows")) ||
                    (view is ViewGroup && (0 until view.childCount).any { hasSwitch(view.getChildAt(it)) })
            assertFalse(hasSwitch(root))
        }
    }

    @Test fun ordinaryHorizontalSwipeStillScrollsLabels() = scene(count = 9) { s ->
        val touch = s.touch(2)
        touch.move(s.point(1))
        touch.up()
        s.await { s.scroll.scrollX > 0 }
        s.onMain {
            assertFalse(controller.isDragging)
            assertEquals(0, taps)
            assertEquals(0, requests)
            assertEquals(0, reorders)
        }
    }

    @Test fun cardMoveDropsStillBelongToIndividualChipTargets() = scene { s ->
        var cardDrops = 0
        s.onMain {
            chip(1).setOnLongClickListener { view ->
                view.startDragAndDrop(
                    ClipData.newPlainText("card_move_drag", "42"),
                    View.DragShadowBuilder(view), 42L, View.DRAG_FLAG_OPAQUE
                )
            }
            chip(2).setOnDragListener { _, event ->
                if (event.clipDescription?.label?.toString() != "card_move_drag") false
                else {
                    if (event.action == DragEvent.ACTION_DROP) {
                        assertEquals(42L, event.localState)
                        cardDrops++
                    }
                    true
                }
            }
        }
        val touch = s.touch(1)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150L)
        touch.move(s.point(2))
        touch.up()
        s.await { cardDrops == 1 }
        s.onMain {
            assertFalse(controller.isDragging)
            assertEquals(0, reorders)
            assertEquals(0, requests)
            assertEquals(listOf(1L, 2L, 3L), order)
        }
    }

    @Test fun rebuildingLabelsAndDisposalCancelDelayedWork() = scene { s ->
        val drag = s.press(1)
        drag.move(s.point(2))
        s.onMain { render() }
        drag.up()
        val touch = s.touch(1)
        s.onMain { controller.dispose() }
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + FlowDragSession.MERGE_DWELL_MS)
        touch.up()
        s.onMain {
            assertFalse(controller.isDragging)
            assertEquals(View.GONE, hint.visibility)
            assertEquals(0, requests)
            assertEquals(0, reorders)
            assertEquals(listOf(1L, 2L, 3L), order)
        }
    }

    @Test fun selectionSlidesBetweenAccessibleUnfilledLabels() = scene { s ->
        SystemClock.sleep(300)
        s.capture("idle")
        val initial = s.onMainPoint { PointF(group.selectionBounds.left, group.selectionBounds.right) }
        val touch = s.touch(3)
        SystemClock.sleep(35)
        touch.up()
        if (ValueAnimator.areAnimatorsEnabled()) {
            s.await {
                s.group.selectionBounds.left > initial.x &&
                    s.group.selectionBounds.left < s.chip(3).left - 1
            }
        }
        s.await { kotlin.math.abs(s.group.selectionBounds.left - s.chip(3).left) < .5f }
        s.onMain {
            assertEquals(3L, selected)
            assertTrue(chip(3).isChecked)
            order.forEach {
                assertTrue(chip(it).height >= dp(48) - 1)
                assertEquals(0, chip(it).chipBackgroundColor?.defaultColor)
            }
            val library = root.findViewById<View>(R.id.buttonFlowLibrary)
            assertEquals(activity.getString(R.string.flow_library_button), library.contentDescription)
            assertEquals(dp(48), library.width.toFloat(), 1f)
            val label = chip(1)
            val image = Bitmap.createBitmap(label.width, label.height, Bitmap.Config.ARGB_8888)
            label.draw(Canvas(image))
            assertEquals(0, Color.alpha(image.getPixel(label.width / 2, 2)))
            image.recycle()
        }
        s.capture("selected")
    }

    @Test fun mergeRingFillsWithoutResizingDockOrMovingLabels() = scene { s ->
        var height = 0
        s.onMain { height = root.findViewById<View>(R.id.flowBar).height }
        val original = s.point(2)
        val drag = s.press(1)
        drag.move(s.point(2), steps = 2)
        s.await { s.group.mergeProgress in .15f.. .8f }
        s.onMain {
            assertEquals(height, root.findViewById<View>(R.id.flowBar).height)
            assertEquals(original.y, pointOnMain(2).y, 2f)
            val visibleHint = Rect()
            assertTrue(hint.getGlobalVisibleRect(visibleHint))
            assertEquals(hint.height, visibleHint.height())
            val dockLocation = IntArray(2)
            root.findViewById<View>(R.id.flowBar).getLocationOnScreen(dockLocation)
            assertTrue(visibleHint.bottom < dockLocation[1])
        }
        s.capture("merge-progress")
        s.awaitMergeReady(2)
        s.onMain { assertEquals(1f, group.mergeProgress, 0f) }
        s.capture("merge-ready")
        s.onMain { controller.cancel() }
        drag.up()
        s.onMain {
            assertEquals(0f, group.mergeProgress, 0f)
            assertEquals(0, requests)
        }
    }

    @Test fun dockIsCompactCenteredAndCappedOnWideAndNarrowLayouts() = scene { s ->
        s.onMain {
            for (width in listOf(280, 1000)) {
                for (count in listOf(2, 15)) {
                    for (rtl in listOf(false, true)) {
                        val layout = activity.layoutInflater.inflate(R.layout.activity_main, null) as ViewGroup
                        layout.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                        val labels = layout.findViewById<FlowDockChipGroup>(R.id.flowChips)
                        repeat(count) { labels.addView(labels.createChip(it.toLong(), "Flow $it", it == 0)) }
                        layout.measure(
                            View.MeasureSpec.makeMeasureSpec(dp(width).toInt(), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(dp(800).toInt(), View.MeasureSpec.EXACTLY)
                        )
                        layout.layout(0, 0, layout.measuredWidth, layout.measuredHeight)
                        val dock = layout.findViewById<FlowDockView>(R.id.flowBar)
                        assertTrue(dock.width <= dp(600) + 1)
                        assertTrue(dock.left >= dp(12) - 1)
                        assertTrue(dock.right <= layout.width - dp(12) + 1)
                        assertEquals(layout.width / 2f, (dock.left + dock.right) / 2f, 1f)
                        if (count == 2 && width == 1000) assertTrue(dock.width < dp(400))
                        if (rtl) assertTrue(labels.getChildAt(0).left > labels.getChildAt(count - 1).left)
                    }
                }
            }
        }
    }

    @Test fun dockGlassFadesToTransparentWithoutAnOpaqueCapsule() = scene { s ->
        s.onMain {
            val dock = root.findViewById<FlowDockView>(R.id.flowBar)
            assertEquals(Color.TRANSPARENT, dock.cardBackgroundColor.defaultColor)
            assertEquals(0f, dock.cardElevation, 0f)
            assertEquals(0, dock.strokeWidth)
            val image = Bitmap.createBitmap(dock.width, dock.height, Bitmap.Config.ARGB_8888)
            try {
                dock.draw(Canvas(image))
                val y = dp(7).toInt()
                val center = image.getPixel(image.width / 2, y)
                val opacity = Color.alpha(center)
                assertTrue("The glass strip should remain translucent", opacity in 32..100)
                assertTrue(Color.blue(center) > Color.red(center))
                assertTrue(Color.alpha(image.getPixel(0, y)) < opacity / 8)
                assertTrue(Color.alpha(image.getPixel(image.width - 1, y)) < opacity / 8)
                val fade = minOf(dp(56), image.width * .32f).toInt()
                var previous = 0
                for (x in 0 until fade) {
                    val alpha = Color.alpha(image.getPixel(x, y))
                    assertTrue("Glass edge opacity did not increase smoothly", alpha >= previous)
                    previous = alpha
                }
                assertEquals(Color.WHITE, chip(2).currentTextColor)
            } finally {
                image.recycle()
            }
        }
        s.capture("glass")
    }

    @Test fun labelsFadeBothEdgesForShortOverflowingAndRtlStrips() = scene { s ->
        s.onMain {
            for (overflow in listOf(false, true)) for (rtl in listOf(false, true)) {
                val viewportWidth = dp(240).toInt()
                val viewportHeight = dp(48).toInt()
                val viewport = FlowDockScrollView(activity).apply {
                    layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    isHorizontalScrollBarEnabled = false
                    addView(View(activity).apply {
                        setBackgroundColor(Color.WHITE)
                        minimumWidth = if (overflow) viewportWidth * 2 else viewportWidth
                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, viewportHeight))
                    measure(View.MeasureSpec.makeMeasureSpec(viewportWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY))
                    layout(0, 0, viewportWidth, viewportHeight)
                }
                assertEquals(viewportWidth, viewport.width)
                assertEquals(if (overflow) viewportWidth * 2 else viewportWidth, viewport.getChildAt(0).width)
                assertEquals(viewportHeight, viewport.getChildAt(0).height)
                val image = Bitmap.createBitmap(viewportWidth, viewportHeight, Bitmap.Config.ARGB_8888)
                try {
                    for (end in listOf(0, if (overflow) viewportWidth else 0)) {
                        viewport.scrollTo(end, 0)
                        image.eraseColor(Color.TRANSPARENT)
                        viewport.draw(Canvas(image).apply {
                            translate(-viewport.scrollX.toFloat(), -viewport.scrollY.toFloat())
                        })
                        val y = viewportHeight / 2
                        assertEquals("The center faded ($overflow/$rtl/$end)",
                            255, Color.alpha(image.getPixel(viewportWidth / 2, y)))
                        assertTrue("Left labels did not fade ($overflow/$rtl/$end)",
                            Color.alpha(image.getPixel(0, y)) < 20)
                        assertTrue("Right labels did not fade ($overflow/$rtl/$end)",
                            Color.alpha(image.getPixel(viewportWidth - 1, y)) < 20)
                        val halfFade = (dp(18) / 2).toInt()
                        val faded = image.getPixel(halfFade, y)
                        assertTrue(Color.alpha(faded) in 70..190)
                        assertTrue("The fade must change alpha, not paint a dark cover", Color.red(faded) > 240)
                    }
                } finally {
                    image.recycle()
                }
            }
        }
    }

    @Test fun liftedGlassLabelKeepsWhiteTextReadable() = scene { s ->
        s.onMain {
            val shadow = FlowDockDragShadow(chip(2))
            val size = Point()
            val touch = Point()
            shadow.onProvideShadowMetrics(size, touch)
            val image = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888)
            try {
                shadow.onDrawShadow(Canvas(image))
                val backing = image.getPixel(image.width / 2, dp(17).toInt())
                assertTrue(Color.alpha(backing) in 180..240)
                assertTrue("White labels need a blue-glass backing when lifted",
                    Color.blue(backing) > Color.red(backing))
                var lettering = 0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val pixel = image.getPixel(x, y)
                    if (Color.alpha(pixel) > 180 && Color.red(pixel) > 220 &&
                        Color.green(pixel) > 220 && Color.blue(pixel) > 220
                    ) lettering++
                }
                assertTrue("Lifted label lost its bright lettering", lettering > dp(10))
            } finally {
                image.recycle()
            }
        }
    }

    private fun scene(count: Int = 3, test: (Scene) -> Unit) {
        ActivityScenario.launch(TimescapeFlowTestActivity::class.java).use { scenario ->
            lateinit var scene: Scene
            scenario.onActivity { activity ->
                scene = Scene(activity, count)
                activity.setContentView(scene.root)
            }
            try {
                scene.await { scene.root.hasWindowFocus() && scene.chip(1).width > 0 }
                test(scene)
                scene.onMain { assertEquals(0, failures) }
            } finally {
                scenario.onActivity { scene.controller.dispose(); scene.dialog?.dismiss() }
                scene.pointer?.up()
            }
        }
    }

    private class Scene(val activity: TimescapeFlowTestActivity, count: Int) {
        val root = activity.layoutInflater.inflate(R.layout.activity_main, null) as ViewGroup
        val group: FlowDockChipGroup = root.findViewById(R.id.flowChips)
        val scroll: HorizontalScrollView = root.findViewById(R.id.flowChipScroll)
        val hint: TextView = root.findViewById(R.id.flowDragHint)
        val order = (1L..count.toLong()).toMutableList()
        val cards = order.associateWith { it.toInt() }.toMutableMap()
        var selected = 2L
        var taps = 0
        var reorders = 0
        var requests = 0
        var confirmations = 0
        var failures = 0
        var pointer: Pointer? = null
        var dialog: AlertDialog? = null
        val controller = FlowChipDragController(group, scroll, hint,
            onReorder = { source, insertion ->
                val index = order.indexOf(source)
                val destination = (if (index < insertion) insertion - 1 else insertion)
                    .coerceIn(0, order.lastIndex)
                order.removeAt(index)
                order.add(destination, source)
                reorders++
                render()
            },
            onMerge = { source, target ->
                requests++
                dialog = AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.dialog_merge_flows_title, name(source), name(target)))
                    .setMessage(activity.getString(R.string.dialog_merge_flows_message, name(source), name(target)))
                    .setPositiveButton(R.string.dialog_merge) { _, _ ->
                        cards[target] = cards.getValue(target) + cards.getValue(source)
                        cards.remove(source)
                        order.remove(source)
                        selected = target
                        confirmations++
                        render()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
            onInteraction = {},
            onFailure = { failures++ })

        init {
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                root.findViewById<View>(R.id.flowBar).updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = dp(12).toInt() + insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                }
                insets
            }
            render()
        }

        fun name(id: Long) = "Flow $id"
        fun render() {
            controller.cancel()
            group.removeAllViews()
            order.forEach { id ->
                group.addView(group.createChip(id, name(id), id == selected).apply {
                    controller.bind(this, id) { selected = id; taps++ }
                })
            }
        }
        fun chip(id: Long) = (0 until group.childCount).map { group.getChildAt(it) as Chip }.first { it.tag == id }
        fun dp(value: Int) = value * root.resources.displayMetrics.density
        fun onMain(block: Scene.() -> Unit) =
            InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
        fun onMainPoint(block: Scene.() -> PointF): PointF {
            lateinit var result: PointF
            onMain { result = block() }
            return result
        }
        fun pointOnMain(id: Long): PointF {
            val chip = chip(id)
            val location = IntArray(2)
            chip.getLocationOnScreen(location)
            return PointF(location[0] + chip.width / 2f, location[1] + chip.height / 2f)
        }
        fun point(id: Long) = onMainPoint { pointOnMain(id) }
        fun gap(index: Int) = onMainPoint {
            val first = chip(order[index - 1])
            val second = chip(order[index])
            val location = IntArray(2)
            group.getLocationOnScreen(location)
            PointF(location[0] + (first.right + second.left) / 2f, pointOnMain(order[index]).y)
        }
        fun touch(id: Long): Pointer = Pointer(point(id)).also { pointer = it }
        fun press(id: Long): Pointer {
            val pointer = touch(id)
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150L)
            await { controller.isDragging }
            return pointer
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                onMain { ready = condition() }
                if (ready) return
                SystemClock.sleep(30)
            }
            throw AssertionError("Flow drag did not reach expected state: order=$order, hint=${hint.text}, failures=$failures")
        }
        fun awaitMergeReady(id: Long) = await {
            hint.text == activity.getString(R.string.flow_drag_merge_ready, name(id))
        }
        fun capture(name: String) {
            if (InstrumentationRegistry.getArguments().getString("captureFlowDock") != "true") return
            val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(activity.cacheDir, "flow-dock-$name.png").outputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            image.recycle()
        }
    }

    private class Pointer(start: PointF) {
        private val downTime = SystemClock.uptimeMillis()
        private var position = start
        private var finished = false
        init { send(MotionEvent.ACTION_DOWN) }
        fun move(target: PointF, steps: Int = 6) {
            val start = position
            for (step in 1..steps) {
                val progress = step.toFloat() / steps
                position = PointF(start.x + (target.x - start.x) * progress, start.y + (target.y - start.y) * progress)
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
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
    }
}
