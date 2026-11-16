package com.example.timescapedemo

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.viewpager2.widget.ViewPager2
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CardCollectionNavigationTest {
    @Test
    fun collectionKeepsNextCardThroughEarlySaveAndAnotherRefresh() = fixture { f ->
        f.blockDiff {
            f.star()
            f.save()
            f.refresh()
        }
        f.assertCard(4)
        f.onMain {
            assertEquals(3L, f.flow.cards.first().id)
            assertTrue(f.flow.cards.first().isCollected)
            assertEquals(2, f.flow.cards.first().collectionOriginalIndex)
            assertEquals(4L, f.persistedFlow().getLong("lastViewedCardId"))
            assertEquals(5L, f.flow.bookmarkedCardId)
        }
        f.select(3)
        f.onMain { assertTrue(f.holder.collectionButton.isSelected) }
    }

    @Test
    fun portraitCollectionReplacesPendingSearchReturn() = pendingSearchReturn(horizontal = false)

    @Test
    fun horizontalCollectionReplacesPendingSearchReturn() = pendingSearchReturn(horizontal = true)

    private fun pendingSearchReturn(horizontal: Boolean) = fixture(horizontal = horizontal) { f ->
        f.blockDiff {
            val search = requireNotNull(field(f.activity, "searchMenuItem") as? MenuItem)
            assertTrue(search.expandActionView())
            assertTrue(search.collapseActionView())
            assertNotNull(field(f.controller, "pendingRestorationPosition"))
            f.star()
        }
        f.assertCard(4)
        f.onMain {
            assertEquals(3L, f.flow.cards.first().id)
            assertTrue(f.flow.cards.first().isCollected)
            assertEquals(4L, f.persistedFlow().getLong("lastViewedCardId"))
            assertEquals(horizontal, f.manager.canScrollHorizontally())
        }
    }

    @Test
    fun filteredCollectionSelectsNextVisibleCardAndPreservesSearchOrigin() = fixture { f ->
        f.onMain {
            val item = requireNotNull(field(f.activity, "searchMenuItem") as? MenuItem)
            assertTrue(item.expandActionView())
            requireNotNull(field(f.activity, "searchView") as? SearchView).setQuery("Match", false)
        }
        f.await { f.adapter.currentList.map { it.id } == listOf(1L, 3L, 5L, 7L, 9L) }
        f.select(3)
        f.blockDiff { f.star(); f.save() }
        f.assertCard(5)
        f.onMain {
            assertEquals(listOf(3L, 1L, 5L, 7L, 9L), f.adapter.currentList.map { it.id })
            assertEquals(3L, f.persistedFlow().getLong("lastViewedCardId"))
            assertTrue(requireNotNull(field(f.activity, "searchMenuItem") as? MenuItem).collapseActionView())
        }
        f.assertCard(3)
        f.onMain { assertTrue(f.holder.collectionButton.isSelected) }
    }

    @Test
    fun collectingLastCardKeepsPreviousCardExpanded() = fixture { f ->
        f.select(9)
        f.blockDiff { f.star(); f.save() }
        f.assertCard(8)
        f.onMain {
            assertEquals(9L, f.flow.cards.first().id)
            assertEquals(8, f.flow.cards.first().collectionOriginalIndex)
        }
    }

    @Test
    fun uncollectingRestoresCardOrderAndShowsNextCard() = fixture(collected = true) { f ->
        f.blockDiff { f.star(); f.save() }
        f.assertCard(1)
        f.onMain {
            assertEquals((1L..9L).toList(), f.flow.cards.map { it.id })
            assertFalse(f.flow.cards[2].isCollected)
            assertNull(f.flow.cards[2].collectionOriginalIndex)
        }
    }

    @Test
    fun collectingOnlyCardKeepsItExpandedAndUpdatesStarControls() = fixture(count = 1) { f ->
        f.blockDiff { f.star(); f.save() }
        f.assertCard(1)
        f.onMain {
            assertTrue(f.flow.cards.single().isCollected)
            assertTrue(f.holder.collectionButton.isSelected)
        }
    }

    @Test
    fun repeatedCollectionClicksKeepLatestRestorationAndActualCollectionState() = fixture { f ->
        f.blockDiff { f.star(); f.star(); f.save() }
        f.assertCard(1)
        f.onMain {
            assertEquals((1L..9L).toList(), f.flow.cards.map { it.id })
            assertFalse(f.flow.cards[2].isCollected)
            assertNull(f.flow.cards[2].collectionOriginalIndex)
            assertEquals(1L, f.persistedFlow().getLong("lastViewedCardId"))
        }
    }

    private fun fixture(
        horizontal: Boolean = false,
        collected: Boolean = false,
        count: Int = 9,
        test: (Fixture) -> Unit
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Requires explicitly isolated app data",
            InstrumentationRegistry.getArguments().getString("isolatedCollectionData") == "true")
        check(instrumentation.targetContext.packageName != "com.example.timescapedemo.phone") {
            "Collection navigation fixtures must not run against the user's app"
        }
        ActivityScenario.launch(TimescapeFlowTestActivity::class.java).use { scenario ->
            val fixture = Fixture(horizontal, collected, count)
            try {
                fixture.seed()
                scenario.onActivity { host -> host.startActivity(Intent(host, MainActivity::class.java)) }
                fixture.launch()
                test(fixture)
            } finally {
                fixture.close()
            }
        }
    }

    private class Fixture(
        private val horizontal: Boolean,
        private val collected: Boolean,
        private val count: Int
    ) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val prefs = instrumentation.targetContext
            .getSharedPreferences("timescape_state", Context.MODE_PRIVATE)
        private val saved = listOf(
            "flows", "selected_flow_index", "next_card_id", "next_flow_id", "two_flow_landscape_enabled"
        ).associateWith { prefs.all[it] }
        private var main: MainActivity? = null
        val activity get() = requireNotNull(main)
        val controller get() = requireNotNull(
            (field(activity, "flowControllers") as? Map<*, *>)?.get(42L)
        )
        val recycler get() = requireNotNull(field(controller, "recycler") as? RecyclerView)
        val adapter get() = requireNotNull(field(controller, "adapter") as? CardsAdapter)
        val manager get() = requireNotNull(field(controller, "layoutManager") as? RightRailFlowLayoutManager)
        val flow get() = requireNotNull((field(activity, "flows") as? List<*>)
            ?.filterIsInstance<CardFlow>()?.firstOrNull { it.id == 42L })
        val holder get() = requireNotNull(
            recycler.findViewHolderForAdapterPosition(manager.currentSelectionIndex() ?: manager.nearestIndex())
                as? CardsAdapter.VH
        )

        fun seed() {
            val ids = if (collected) listOf(3, 1, 2) + (4..count).toList() else (1..count).toList()
            val cards = ids.map { id ->
                JSONObject().put("id", id).put("title", "${if (id % 2 == 1) "Match" else "Plain"} card $id")
                    .put("snippet", "Disposable reading card $id")
                    .put("isCollected", collected && id == 3)
                    .apply { if (collected && id == 3) put("collectionOriginalIndex", 2) }
            }
            val currentId = if (count == 1) 1 else 3
            val seededFlow = JSONObject().put("id", 42).put("name", "Collection navigation fixture")
                .put("cards", JSONArray(cards)).put("lastViewedCardId", currentId)
                .put("lastViewedCardIndex", ids.indexOf(currentId)).put("lastViewedCardFocused", true)
                .put("bookmarkedCardId", if (count == 1) 1 else 5)
                .put("bookmarkedCardIndex", if (count == 1) 0 else 4)
            val video = JSONObject().put("id", 0).put("name", "Movies").put("cards", JSONArray())
            assertTrue(prefs.edit().putString("flows", JSONArray(listOf(video, seededFlow)).toString())
                .putInt("selected_flow_index", 1).putLong("next_card_id", 100)
                .putLong("next_flow_id", 43).putBoolean("two_flow_landscape_enabled", false).commit())
        }

        fun launch() {
            awaitReady()
            select(if (count == 1) 1 else 3)
            onMain {
                activity.requestedOrientation = if (horizontal) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            awaitReady(if (horizontal) Configuration.ORIENTATION_LANDSCAPE
                else Configuration.ORIENTATION_PORTRAIT)
            select(if (count == 1) 1 else 3)
        }

        private fun awaitReady(orientation: Int? = null) {
            await {
                main = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull()
                val current = main ?: return@await false
                val controllers = field(current, "flowControllers") as? Map<*, *> ?: return@await false
                val controller = controllers[42L] ?: return@await false
                val pager = field(current, "flowPager") as? ViewPager2 ?: return@await false
                (orientation == null || current.resources.configuration.orientation == orientation) &&
                    pager.currentItem == 1 && field(current, "selectedFlowIndex") == 1 &&
                    field(controller, "cardsReadyForTransition") == true &&
                    field(current, "searchMenuItem") != null
            }
        }

        fun select(id: Long) {
            onMain {
                val index = adapter.currentList.indexOfFirst { it.id == id }
                check(index >= 0) { "Missing visible fixture card $id" }
                manager.restoreState(index, true)
            }
            assertCard(id)
        }

        fun star() {
            assertTrue(holder.collectionButton.performClick())
        }

        fun save() {
            activity.javaClass.getDeclaredMethod("saveState").apply { isAccessible = true }.invoke(activity)
        }

        fun refresh() {
            activity.javaClass.getDeclaredMethod(
                "refreshFlow", CardFlow::class.java, Boolean::class.javaPrimitiveType, CardFlowPosition::class.java
            ).apply { isAccessible = true }.invoke(activity, flow, false, null)
        }

        fun blockDiff(action: () -> Unit) {
            val gate = onMain {
                val differ = ListAdapter::class.java.getDeclaredField("mDiffer").apply { isAccessible = true }
                    .get(adapter)
                val config = AsyncListDiffer::class.java.getDeclaredField("mConfig")
                    .apply { isAccessible = true }.get(differ) as? AsyncDifferConfig<*>
                DiffGate(requireNotNull(config).backgroundThreadExecutor)
            }
            try {
                assertTrue("Diff workers did not reach the barrier", gate.started.await(5, TimeUnit.SECONDS))
                onMain(action)
            } finally {
                gate.release.countDown()
            }
        }

        fun persistedFlow(): JSONObject {
            val flows = JSONArray(requireNotNull(prefs.getString("flows", null)))
            return (0 until flows.length()).map(flows::getJSONObject).first { it.getLong("id") == 42L }
        }

        fun assertCard(id: Long) {
            await {
                val index = manager.currentSelectionIndex() ?: return@await false
                val view = recycler.findViewHolderForAdapterPosition(index) as? CardsAdapter.VH
                    ?: return@await false
                adapter.getItemAt(index)?.id == id && manager.isFocused(index) &&
                    view.presentation.expansionProgress == 1f && view.isFaceReady
            }
            onMain {
                val index = requireNotNull(manager.currentSelectionIndex())
                assertEquals(id, adapter.getItemAt(index)?.id)
                val counter = requireNotNull(field(controller, "cardCountView") as? android.widget.TextView)
                assertEquals("${index + 1}/${adapter.itemCount}", counter.text.toString())
            }
        }

        fun <T> onMain(action: () -> T): T {
            val result = AtomicReference<T>()
            instrumentation.runOnMainSync { result.set(action()) }
            return result.get()
        }

        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 10000
            while (SystemClock.uptimeMillis() < deadline) {
                if (onMain(condition)) return
                SystemClock.sleep(40)
            }
            val state = onMain {
                if (main == null) "MainActivity not resumed" else
                    "flow=${field(activity, "selectedFlowIndex")}, " +
                        "orientation=${activity.resources.configuration.orientation}, " +
                        "requested=${activity.requestedOrientation}, " +
                        "selected=${manager.currentSelectionIndex()}, card=${adapter.getItemAt(manager.nearestIndex())?.id}, " +
                        "order=${adapter.currentList.map { it.id }}, focused=${manager.hasSelection()}"
            }
            throw AssertionError("Collection navigation did not settle: $state")
        }

        fun close() {
            main?.let { closing ->
                onMain { closing.finish() }
                val deadline = SystemClock.uptimeMillis() + 5000
                while (!onMain { closing.isDestroyed } && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(40)
                }
                assertTrue("MainActivity did not finish", onMain { closing.isDestroyed })
            }
            val editor = prefs.edit()
            saved.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> error("Unsupported fixture preference $key")
                }
            }
            assertTrue(editor.commit())
        }
    }

    private class DiffGate(executor: Executor) {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)

        init {
            // Hold both default workers so restoration and saves run before the diff commits.
            repeat(2) {
                executor.execute {
                    started.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "Diff barrier was not released" }
                }
            }
        }
    }

    companion object {
        private fun field(owner: Any, name: String): Any? =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    }
}
