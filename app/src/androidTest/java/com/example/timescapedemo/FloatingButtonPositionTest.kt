package com.example.timescapedemo

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FloatingButtonPositionTest {
    @Test fun noSavedPositionKeepsTheOriginalXmlPlacement() = scene {
        val page = page()
        val button = button(page)
        val originalX = button.x
        val originalY = button.y
        positions.attach(button)
        resize(page, 600, 900)
        assertEquals(originalX, button.x, .01f)
        val margin = (button.layoutParams as FrameLayout.LayoutParams).topMargin
        assertEquals((900 - button.height) / 2f + margin, button.y, 1f)
        assertNotEquals(originalY, button.y)
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun savingSynchronizesExistingAndNewFlowPagesWithoutClicking() = scene {
        val first = page()
        val second = page()
        val button = button(first)
        var clicks = 0
        var longClicks = 0
        button.setOnClickListener { clicks++ }
        button.setOnLongClickListener { longClicks++; true }
        positions.attach(button)
        positions.attach(button(second))
        moveAndSave(first, .75f, .32f)
        assertPosition(first, .75f, .32f)
        assertPosition(second, .75f, .32f)
        val third = page(600, 1000)
        positions.attach(button(third))
        assertPosition(third, .75f, .32f)
        assertEquals(0, clicks)
        assertEquals(0, longClicks)
        button.performClick()
        button.performLongClick()
        assertEquals(1, clicks)
        assertEquals(1, longClicks)
    }

    @Test fun aNewPositionManagerRestoresPersistedPlacementBeforeFirstLayout() = scene {
        val first = page()
        positions.attach(button(first))
        moveAndSave(first, .63f, .81f)
        positions.detach(button(first))
        val restored = FloatingButtonPosition(prefs, keyPrefix)
        val second = page(layout = false)
        val button = button(second)
        restored.attach(button)
        try {
            resize(second, 700, 1100)
            assertPosition(second, .63f, .81f)
        } finally {
            restored.detach(button)
        }
    }

    @Test fun rotationAndButtonResizingRetainRelativePlacementWithinBounds() = scene {
        val page = page()
        positions.attach(button(page))
        moveAndSave(page, .94f, .87f)
        resize(page, 1200, 500)
        assertPosition(page, .94f, .87f)
        val button = button(page)
        button.layoutParams = button.layoutParams.apply { width = 160; height = 100 }
        resize(page, 1200, 500)
        assertPosition(page, .94f, .87f)
        assertTrue(button.x >= 0f && button.x + button.width <= page.width)
        assertTrue(button.y >= 0f && button.y + button.height <= page.height)
    }

    @Test fun rtlRestoresTheSamePhysicalLocationAndClampsOversizedDrags() = scene {
        val first = page()
        positions.attach(button(first))
        moveAndSave(first, .2f, .7f)
        val rtl = page(layout = false).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        positions.attach(button(rtl))
        resize(rtl, 800, 1200)
        assertPosition(rtl, .2f, .7f)
        button(rtl).x = -200f
        button(rtl).y = 2000f
        positions.save(button(rtl))
        assertPosition(rtl, 0f, 1f)
        assertPosition(first, 0f, 1f)
    }

    @Test fun disposedPagesStopFollowingAndRebindingRestoresTheLatestPosition() = scene {
        val first = page()
        val second = page()
        positions.attach(button(first))
        positions.attach(button(second))
        moveAndSave(first, .4f, .6f)
        positions.detach(button(second))
        val oldX = button(second).x
        val oldY = button(second).y
        moveAndSave(first, .8f, .2f)
        assertEquals(oldX, button(second).x, .01f)
        assertEquals(oldY, button(second).y, .01f)
        positions.attach(button(second))
        assertPosition(second, .8f, .2f)
    }

    @Test fun dragAndCancellationSaveWithoutActivatingTheButtonAndTapsStillClick() = scene {
        val page = page()
        val button = button(page)
        var clicks = 0
        var longClicks = 0
        button.setOnClickListener { clicks++ }
        button.setOnLongClickListener { longClicks++; true }
        positions.attach(button)
        val startX = button.x
        val startY = button.y
        touch(button, MotionEvent.ACTION_DOWN, 20f, 20f)
        touch(button, MotionEvent.ACTION_MOVE, 220f, -80f)
        touch(button, MotionEvent.ACTION_UP, 220f, -80f)
        assertEquals(startX + 200f, button.x, .01f)
        assertEquals(startY - 100f, button.y, .01f)
        assertEquals(0, clicks)
        assertEquals(0, longClicks)
        assertEquals(button.x / (page.width - button.width),
            prefs.getFloat("${keyPrefix}_button_position_x", -1f), .001f)
        touch(button, MotionEvent.ACTION_DOWN, 20f, 20f)
        touch(button, MotionEvent.ACTION_MOVE, -2000f, 2000f)
        touch(button, MotionEvent.ACTION_CANCEL, -2000f, 2000f)
        assertPosition(page, 0f, 1f)
        assertEquals(0, clicks)
        assertEquals(0, longClicks)
        touch(button, MotionEvent.ACTION_DOWN, 20f, 20f)
        touch(button, MotionEvent.ACTION_UP, 20f, 20f)
        assertEquals(1, clicks)
    }

    @Test fun bookmarkAndFlipPositionsRemainIndependentOnTheSameAndNewPages() = scene {
        val page = page()
        val otherPrefix = if (keyPrefix == "bookmark") "flip" else "bookmark"
        val otherId = if (otherPrefix == "flip") R.id.buttonFlowFlip else R.id.buttonFlowBookmark
        val other = page.findViewById<View>(otherId).apply { visibility = View.VISIBLE }
        resize(page, 800, 1200)
        val otherPositions = FloatingButtonPosition(prefs, otherPrefix)
        positions.attach(button(page))
        otherPositions.attach(other)
        try {
            val originalOtherX = other.x
            val originalOtherY = other.y
            moveAndSave(page, .75f, .2f)
            assertEquals(originalOtherX, other.x, .01f)
            assertEquals(originalOtherY, other.y, .01f)
            other.x = .15f * (page.width - other.width)
            other.y = .8f * (page.height - other.height)
            otherPositions.save(other)
            assertPosition(page, .75f, .2f)
            val newPage = page(600, 900)
            val newOther = newPage.findViewById<View>(otherId).apply { visibility = View.VISIBLE }
            resize(newPage, 600, 900)
            positions.attach(button(newPage))
            otherPositions.attach(newOther)
            try {
                assertPosition(newPage, .75f, .2f)
                assertEquals(.15f * (newPage.width - newOther.width), newOther.x, .01f)
                assertEquals(.8f * (newPage.height - newOther.height), newOther.y, .01f)
            } finally {
                otherPositions.detach(newOther)
            }
        } finally {
            otherPositions.detach(other)
        }
    }

    private fun touch(button: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try {
            assertTrue(button.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    private fun scene(test: Scene.() -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_TimescapeDemo)
        for (keyPrefix in listOf("bookmark", "flip")) {
            val name = "floating-position-test-${UUID.randomUUID()}"
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            try {
                instrumentation.runOnMainSync {
                    val scene = Scene(context, prefs, keyPrefix)
                    try {
                        scene.test()
                    } finally {
                        scene.pages.forEach { scene.positions.detach(scene.button(it)) }
                    }
                }
            } finally {
                assertTrue(context.deleteSharedPreferences(name))
            }
        }
    }

    private class Scene(private val context: Context, val prefs: SharedPreferences, val keyPrefix: String) {
        val positions = FloatingButtonPosition(prefs, keyPrefix)
        val pages = mutableListOf<FrameLayout>()

        fun page(width: Int = 800, height: Int = 1200, layout: Boolean = true): FrameLayout {
            val page = LayoutInflater.from(context).inflate(R.layout.page_card_flow, null) as FrameLayout
            button(page).visibility = View.VISIBLE
            pages.add(page)
            if (layout) resize(page, width, height)
            return page
        }

        fun button(page: FrameLayout): View = page.findViewById(
            if (keyPrefix == "flip") R.id.buttonFlowFlip else R.id.buttonFlowBookmark)

        fun resize(page: FrameLayout, width: Int, height: Int) {
            page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            page.layout(0, 0, width, height)
        }

        fun moveAndSave(page: FrameLayout, x: Float, y: Float) {
            val button = button(page)
            button.x = x * (page.width - button.width)
            button.y = y * (page.height - button.height)
            positions.save(button)
        }

        fun assertPosition(page: FrameLayout, x: Float, y: Float) {
            val button = button(page)
            assertEquals(x * (page.width - button.width), button.x, .01f)
            assertEquals(y * (page.height - button.height), button.y, .01f)
        }
    }
}
