package com.example.timescapedemo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.slider.Slider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerSectionsTest {
    @Test fun freshDrawerHasDirectSearchAndFourCollapsedSections() = scene {
        val fixture = fixture()
        assertEquals(View.VISIBLE, fixture.view(R.id.buttonDrawerGlobalSearch).visibility)
        for ((headerId, contentId) in sectionIds) {
            assertFalse(fixture.view(headerId).isSelected)
            assertEquals(View.GONE, fixture.view(contentId).visibility)
            assertEquals(
                180f,
                fixture.view(headerId).findViewById<ImageView>(R.id.drawerSectionArrow).rotation,
                0f
            )
        }
    }

    @Test fun eachSectionExpandsAndCollapsesIndependently() = scene {
        val fixture = fixture()
        for ((headerId, contentId) in sectionIds) {
            assertTrue(fixture.view(headerId).performClick())
            assertTrue(fixture.view(headerId).isSelected)
            assertEquals(View.VISIBLE, fixture.view(contentId).visibility)
            assertEquals(
                0f,
                fixture.view(headerId).findViewById<ImageView>(R.id.drawerSectionArrow).rotation,
                0f
            )
        }
        fixture.view(R.id.drawerAppearanceHeader).performClick()
        assertEquals(View.GONE, fixture.view(R.id.drawerAppearanceContent).visibility)
        for ((headerId, contentId) in sectionIds.drop(1)) {
            assertTrue(fixture.view(headerId).isSelected)
            assertEquals(View.VISIBLE, fixture.view(contentId).visibility)
        }
    }

    @Test fun controlsRemainInTheirMatchingGroups() = scene {
        val fixture = fixture()
        val controlsByGroup = mapOf(
            R.id.drawerAppearanceContent to listOf(
                R.id.imageAppBackgroundPreview, R.id.appBackgroundPresets,
                R.id.buttonDrawerPickAppBackground, R.id.buttonDrawerResetAppBackground,
                R.id.drawerRecyclerImages, R.id.buttonDrawerAddImages, R.id.buttonDrawerClearImages
            ),
            R.id.drawerReadingContent to listOf(
                R.id.sliderCardFontSize, R.id.textCardFontSizeValue, R.id.textCardFontName,
                R.id.buttonDrawerPickFont, R.id.buttonDrawerResetFont, R.id.buttonDrawerVideoMute
            ),
            R.id.drawerSyncContent to listOf(
                R.id.sliderNotificationFrequency, R.id.textNotificationFrequencyValue,
                R.id.buttonDrawerToggleWatchSync, R.id.textDrawerWatchSyncStatus,
                R.id.textDrawerWatchSyncEndpoint, R.id.buttonDrawerCopyWatchEndpoint,
                R.id.buttonDrawerWatchSyncHelp
            ),
            R.id.drawerDataContent to listOf(
                R.id.buttonDrawerImportNotes, R.id.buttonDrawerExportNotes,
                R.id.buttonDrawerExportStickyNotes
            )
        )
        controlsByGroup.forEach { (groupId, controlIds) ->
            controlIds.forEach { controlId ->
                assertSame(fixture.view(controlId), fixture.view(groupId).findViewById<View>(controlId))
            }
        }
    }

    @Test fun collapsingRetainsValuesAndExistingActionListeners() = scene {
        val fixture = fixture()
        val header = fixture.view(R.id.drawerReadingHeader)
        val slider = fixture.root.findViewById<Slider>(R.id.sliderCardFontSize)
        val button = fixture.view(R.id.buttonDrawerVideoMute)
        var changes = 0
        var clicks = 0
        slider.addOnChangeListener { _, _, _ -> changes++ }
        button.setOnClickListener { clicks++ }
        header.performClick()
        slider.value = 23f
        assertEquals(1, changes)
        repeat(3) {
            header.performClick()
            header.performClick()
        }
        assertSame(slider, fixture.root.findViewById<Slider>(R.id.sliderCardFontSize))
        assertEquals(23f, slider.value, 0f)
        assertEquals(1, changes)
        button.performClick()
        assertEquals(1, clicks)
    }

    @Test fun savedStateRestoresOnlyExpandedGroupsAndFreshLaunchResetsThem() = scene {
        val original = fixture()
        original.view(R.id.drawerAppearanceHeader).performClick()
        original.view(R.id.drawerDataHeader).performClick()
        val state = Bundle()
        original.sections.saveState(state)
        val restored = fixture(state)
        for ((headerId, contentId) in sectionIds) {
            val expanded = headerId == R.id.drawerAppearanceHeader || headerId == R.id.drawerDataHeader
            assertEquals(expanded, restored.view(headerId).isSelected)
            assertEquals(if (expanded) View.VISIBLE else View.GONE, restored.view(contentId).visibility)
        }
        val fresh = fixture()
        sectionIds.forEach { (_, contentId) ->
            assertEquals(View.GONE, fresh.view(contentId).visibility)
        }
    }

    @Test fun accessibilityExposesButtonStateAndExpandCollapseActions() {
        ActivityScenario.launch(TimescapeFlowTestActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { activity ->
                fixture = fixture()
                activity.setContentView(fixture.root)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val header = fixture.view(R.id.drawerReadingHeader)
                fun assertNode(expanded: Boolean) {
                    val info = header.createAccessibilityNodeInfo()
                    assertEquals(Button::class.java.name, info.className)
                    assertTrue(info.isClickable)
                    assertTrue(info.isFocusable)
                    assertEquals(
                        header.context.getString(
                            if (expanded) R.string.card_state_expanded else R.string.card_state_collapsed
                        ),
                        info.stateDescription
                    )
                    assertTrue(info.actionList.any {
                        it.id == if (expanded) AccessibilityNodeInfo.ACTION_COLLAPSE else AccessibilityNodeInfo.ACTION_EXPAND
                    })
                    assertFalse(info.actionList.any {
                        it.id == if (expanded) AccessibilityNodeInfo.ACTION_EXPAND else AccessibilityNodeInfo.ACTION_COLLAPSE
                    })
                }
                assertNode(false)
                assertTrue(header.performAccessibilityAction(AccessibilityNodeInfoCompat.ACTION_EXPAND, null))
                assertEquals(View.VISIBLE, fixture.view(R.id.drawerReadingContent).visibility)
                assertNode(true)
                assertTrue(header.performAccessibilityAction(AccessibilityNodeInfoCompat.ACTION_COLLAPSE, null))
                assertEquals(View.GONE, fixture.view(R.id.drawerReadingContent).visibility)
                assertNode(false)
            }
        }
    }

    private data class Fixture(val root: View, val sections: DrawerSections) {
        fun view(id: Int): View = root.findViewById(id)
    }

    private fun fixture(savedState: Bundle? = null): Fixture {
        val context = ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.Theme_TimescapeDemo
        )
        val root = LayoutInflater.from(context).inflate(R.layout.drawer_backgrounds, null)
        val sections = DrawerSections(root, savedState)
        val density = context.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (800 * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, width, height)
        return Fixture(root, sections)
    }

    private fun scene(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private val sectionIds = listOf(
        R.id.drawerAppearanceHeader to R.id.drawerAppearanceContent,
        R.id.drawerReadingHeader to R.id.drawerReadingContent,
        R.id.drawerSyncHeader to R.id.drawerSyncContent,
        R.id.drawerDataHeader to R.id.drawerDataContent
    )
}
