package com.example.timescapedemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardViewportFocusTest {
    @Test
    fun keyboardFocusDoesNotPaintAVeilOverTheMainPager() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(TimescapeFlowTestActivity::class.java).use { scenario ->
            lateinit var viewport: RecyclerView
            lateinit var motion: FlowPagerMotionController
            val location = IntArray(2)
            scenario.onActivity { activity ->
                val pager = ViewPager2(activity)
                pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = 1
                    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
                        object : RecyclerView.ViewHolder(FrameLayout(activity).apply {
                            layoutParams = RecyclerView.LayoutParams(-1, -1)
                        }) {}
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                }
                motion = FlowPagerMotionController(pager, pageReady = { true })
                viewport = pager.getChildAt(0) as RecyclerView
                viewport.setBackgroundColor(Color.rgb(43, 91, 137))
                activity.setContentView(pager)
            }
            try {
                instrumentation.waitForIdleSync()
                scenario.onActivity { viewport.getLocationOnScreen(location) }
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                        location[0] + viewport.width / 2f, location[1] + viewport.height / 2f, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try {
                        assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                    } finally {
                        event.recycle()
                    }
                }
                instrumentation.waitForIdleSync()
                var before = IntArray(0)
                scenario.onActivity {
                    assertTrue(viewport.isInTouchMode)
                    before = pixels(viewport)
                }
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    assertTrue(instrumentation.uiAutomation.injectInputEvent(
                        KeyEvent(now, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_A, 0,
                            0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), true
                    ))
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertFalse(viewport.isInTouchMode)
                    assertTrue(viewport.requestFocus())
                    val after = pixels(viewport)
                    assertArrayEquals("Keyboard focus tinted the full-screen pager " +
                        "(defaultHighlight=${viewport.defaultFocusHighlightEnabled})", before, after)
                }
            } finally {
                scenario.onActivity { motion.dispose() }
            }
        }
    }

    @Test
    fun cardAndSearchViewportsKeepKeyboardFocusWithoutAFullSurfaceHighlight() {
        ActivityScenario.launch(TimescapeFlowTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for ((layout, id) in listOf(R.layout.page_card_flow to R.id.recyclerFlowCards,
                    R.layout.dialog_search_results to R.id.searchResultsRecycler)) {
                    val root = activity.layoutInflater.inflate(layout, null)
                    val viewport = root.findViewById<RecyclerView>(id)
                    assertTrue(viewport.isFocusable)
                    assertFalse(viewport.defaultFocusHighlightEnabled)
                }
                val page = activity.layoutInflater.inflate(R.layout.page_card_flow, null)
                assertTrue(page.findViewById<View>(R.id.buttonFlowFlip).defaultFocusHighlightEnabled)
            }
        }
    }

    private fun pixels(view: View): IntArray {
        val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(frame))
            return IntArray(frame.width * frame.height).also {
                frame.getPixels(it, 0, frame.width, 0, 0, frame.width, frame.height)
            }
        } finally {
            frame.recycle()
        }
    }
}
