package com.example.timescapedemo

import android.content.Context
import android.content.SharedPreferences
import android.view.LayoutInflater
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
class BookmarkButtonPositionTest {
    @Test fun noSavedPositionKeepsTheOriginalXmlPlacement() = scene {
        val page = page()
        val button = button(page)
        val originalX = button.x
        val originalY = button.y
        positions.attach(button)
        resize(page, 600, 900)
        assertEquals(originalX, button.x, .01f)
        assertEquals((900 - button.height) / 2f, button.y, 1f)
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
        val restored = BookmarkButtonPosition(prefs)
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

    private fun scene(test: Scene.() -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_TimescapeDemo)
        val name = "bookmark-position-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            instrumentation.runOnMainSync {
                val scene = Scene(context, prefs)
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

    private class Scene(private val context: Context, val prefs: SharedPreferences) {
        val positions = BookmarkButtonPosition(prefs)
        val pages = mutableListOf<FrameLayout>()

        fun page(width: Int = 800, height: Int = 1200, layout: Boolean = true): FrameLayout {
            val page = LayoutInflater.from(context).inflate(R.layout.page_card_flow, null) as FrameLayout
            button(page).visibility = View.VISIBLE
            pages.add(page)
            if (layout) resize(page, width, height)
            return page
        }

        fun button(page: FrameLayout): View = page.findViewById(R.id.buttonFlowBookmark)

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
