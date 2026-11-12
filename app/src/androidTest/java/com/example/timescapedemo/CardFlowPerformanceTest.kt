package com.example.timescapedemo

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.text.Layout
import android.util.TypedValue
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class CardFlowPerformanceTest {
    @Test fun profileOverview() = profile(false)
    @Test fun profileContinuousReading() = profile(true)

    @Test fun inactiveAndWholeImageReadersDoNotInvalidateLayoutForAnimationProgress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        try {
            instrumentation.runOnMainSync {
                for (enabled in listOf(false, true)) {
                    val reader = ImageCardScrollView(instrumentation.targetContext)
                    val image = ImageView(instrumentation.targetContext).apply { setImageBitmap(bitmap) }
                    reader.addView(image)
                    reader.bind(99L, enabled)
                    reader.markContentReady()
                    reader.measure(View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
                    reader.layout(0, 0, 120, 120)
                    assertFalse(reader.usesWidthFitViewport)
                    assertFalse(reader.isLayoutRequested)
                    reader.setReadingViewport(80, .5f)
                    assertFalse("A non-scrolling reader invalidated the whole reading card",
                        reader.isLayoutRequested)
                    image.setImageDrawable(null)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun pendingLongReaderAppliesTheLatestViewportWhenItsImageBecomesReady() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = Bitmap.createBitmap(120, 400, Bitmap.Config.ARGB_8888)
        try {
            instrumentation.runOnMainSync {
                val reader = ImageCardScrollView(instrumentation.targetContext)
                val image = ImageView(instrumentation.targetContext).apply { setImageBitmap(bitmap) }
                reader.addView(image)
                reader.bind(99L, true)
                reader.setReadingViewport(100, 1f)
                reader.markContentReady()
                reader.measure(View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                assertTrue(reader.usesWidthFitViewport)
                assertEquals(100, reader.measuredHeight)
                reader.layout(0, 0, 120, 100)
                reader.setReadingViewport(80, .5f)
                assertTrue("An active reader must update its scroll viewport", reader.isLayoutRequested)
                image.setImageDrawable(null)
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun readingReusesIntrinsicPreviewHeightWhileThePhotographicFaceGrows() = scene { s ->
        s.frames(24)
        s.onMain { manager.restoreState(6, true) }
        s.await { s.holder(6)?.presentation?.expansionProgress == 1f }
        lateinit var card: CardsAdapter.VH
        var intrinsicMeasures = 0
        var compactHeight = 0
        s.onMain {
            card = requireNotNull(holder(6))
            intrinsicMeasures = card.presentation.previewIntrinsicMeasureCount
            compactHeight = card.presentation.previewHeight
        }
        s.frames(6)
        s.onMain {
            assertTrue(card.presentation.expansionProgress > 0f && card.presentation.expansionProgress < 1f)
            assertEquals("The same preview was intrinsically remeasured during the transition",
                intrinsicMeasures, card.presentation.previewIntrinsicMeasureCount)
            assertEquals(compactHeight, card.presentation.previewHeight)
        }
    }

    @Test fun cachedPreviewHeightStillUpdatesWhenTheFontChanges() = scene { s ->
        s.frames(24)
        s.onMain { manager.restoreState(6, false) }
        s.await { s.holder(6) != null }
        lateinit var card: CardsAdapter.VH
        var height = 0
        var textSize = 0f
        var measures = 0
        s.onMain {
            card = requireNotNull(holder(6))
            height = card.presentation.previewHeight
            textSize = card.previewSnippet.textSize
            measures = card.presentation.previewIntrinsicMeasureCount
            card.previewSnippet.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
        }
        s.await { card.presentation.previewHeight > height }
        s.onMain {
            assertTrue(card.presentation.previewIntrinsicMeasureCount > measures)
            card.previewSnippet.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        }
        s.await { card.presentation.previewHeight == height }
    }

    @Test fun overviewReusesLayoutsAndReleasesRasterCaches() = scene { s ->
        s.frames(24)
        lateinit var card: CardsAdapter.VH
        var width = 0
        var scale = 0f
        var measures = 0
        var draws = 0
        s.onMain {
            card = recycler.findViewHolderForAdapterPosition(manager.nearestIndex()) as CardsAdapter.VH
            width = card.itemView.width
            scale = card.itemView.scaleX
            measures = measureCount()
            draws = drawCount()
            assertEquals(View.LAYER_TYPE_HARDWARE, card.preview.layerType)
            assertEquals(View.LAYER_TYPE_HARDWARE, card.previewText.layerType)
        }
        s.frames(12)
        s.onMain {
            assertEquals(width, card.itemView.width)
            assertTrue(kotlin.math.abs(scale - card.itemView.scaleX) > .001f)
            assertTrue("Overview is remeasuring per frame", measureCount() - measures <= 8)
            assertTrue("Overview is rerecording glyphs per frame", drawCount() - draws <= 24)
            recycler.adapter = null
            for (view in cards) {
                assertEquals(View.LAYER_TYPE_NONE, view.findViewById<View>(R.id.cardPreview).layerType)
                assertEquals(View.LAYER_TYPE_NONE, view.findViewById<View>(R.id.previewText).layerType)
                assertEquals(View.LAYER_TYPE_NONE, view.findViewById<View>(R.id.card).layerType)
            }
        }
    }

    @Test fun readingKeepsTextLayoutStableAndRestoresUncachedInteractiveFace() = scene { s ->
        s.frames(24)
        s.onMain { manager.restoreState(6, true) }
        s.await { s.holder(6)?.presentation?.expansionProgress == 1f }
        lateinit var card: CardsAdapter.VH
        lateinit var textLayout: Layout
        var readingWidth = 0
        s.onMain {
            card = requireNotNull(holder(6))
            textLayout = card.snippet.layout
            readingWidth = card.card.width
        }
        s.frames(6)
        s.onMain {
            assertTrue(card.presentation.expansionProgress > 0f && card.presentation.expansionProgress < 1f)
            assertEquals(readingWidth, card.card.width)
            assertSame("Reading text reflowed during the drag", textLayout, card.snippet.layout)
            assertEquals(View.LAYER_TYPE_HARDWARE, card.card.layerType)
            assertTrue(card.presentation.getChildAt(0).scaleX < 1f)
            manager.restoreState(6, true)
        }
        s.await { s.holder(6)?.presentation?.expansionProgress == 1f }
        s.onMain {
            assertEquals(View.LAYER_TYPE_NONE, card.card.layerType)
            assertEquals(1f, card.presentation.getChildAt(0).scaleX, .001f)
            assertEquals(1f, card.itemView.scaleX, .001f)
        }
    }

    @Test fun prefetchesBeyondTheAttachedWindowBeforeItAdvances() = scene { s ->
        s.frames(60)
        s.onMain {
            val attached = (0 until recycler.childCount).map {
                recycler.getChildAdapterPosition(recycler.getChildAt(it))
            }
            for (direction in listOf(1, -1)) {
                val positions = mutableListOf<Int>()
                manager.collectAdjacentPrefetchPositions(0, direction, RecyclerView.State(),
                    object : RecyclerView.LayoutManager.LayoutPrefetchRegistry {
                        override fun addPosition(layoutPosition: Int, pixelDistance: Int) {
                            assertTrue(pixelDistance >= 0)
                            positions += layoutPosition
                        }
                    })
                val expected = if (direction > 0) attached.max() + 1 else attached.min() - 1
                assertTrue("Missing lookahead row $expected: $positions", expected in positions)
            }
        }
    }

    @Test fun hitTestingUsesRenderedCardBoundsInsteadOfCachedLayoutWidths() = scene { s ->
        s.frames(24)
        s.onMain {
            val card = (0 until recycler.childCount).map { recycler.getChildAt(it) }.first {
                it.scaleX < .8f && it.top + it.height / 2 in dp(80)..(recycler.height - dp(80))
            }
            fun point(localX: Float): FloatArray {
                val position = floatArrayOf(localX, card.height / 2f)
                card.matrix.mapPoints(position)
                position[0] += card.left
                position[1] += card.top
                return position
            }
            val center = point(card.width / 2f)
            assertTrue(manager.hasCardUnder(center[0], center[1]))
            val outside = point(-dp(8).toFloat())
            assertTrue("Expected empty space inside the logical layout box", outside[0] > card.left)
            assertFalse("Cached width captured an empty-area tap", manager.hasCardUnder(outside[0], outside[1]))
        }
    }

    private fun scene(test: (Scene) -> Unit) {
        ActivityScenario.launch(TimescapeFlowTestActivity::class.java).use { scenario ->
            lateinit var scene: Scene
            scenario.onActivity {
                scene = Scene(it)
                it.setContentView(scene.root)
            }
            try {
                scene.await { scene.recycler.childCount > 0 && scene.adapter.itemCount == 40 }
                test(scene)
            } finally {
                scenario.onActivity { scene.recycler.adapter = null }
            }
        }
    }

    private fun profile(reading: Boolean) {
        ActivityScenario.launch(TimescapeFlowTestActivity::class.java).use { scenario ->
            lateinit var scene: Scene
            scenario.onActivity {
                scene = Scene(it)
                it.setContentView(scene.root)
            }
            val worker = HandlerThread("CardFlowFrameMetrics").apply { start() }
            val handler = Handler(worker.looper)
            val samples = mutableListOf<FrameSample>()
            var droppedReports = 0
            val listener = Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
                samples += FrameSample(
                    frame.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6,
                    frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) / 1e6,
                    frame.getMetric(FrameMetrics.DRAW_DURATION) / 1e6,
                    frame.getMetric(FrameMetrics.DEADLINE) / 1e6,
                    frame.getMetric(FrameMetrics.GPU_DURATION) / 1e6,
                    frame.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) / 1e6
                )
                droppedReports += dropped
            }
            var listening = false
            try {
                scene.await { scene.recycler.childCount > 0 && scene.adapter.itemCount == 40 }
                scene.frames(60)
                scene.onMain { manager.restoreState(6, reading) }
                scene.await {
                    scene.recycler.findViewHolderForAdapterPosition(6) != null &&
                        (!reading || scene.manager.isFocused(6))
                }
                SystemClock.sleep(150)
                var initialMeasures = 0
                var initialIntrinsicMeasures = 0
                var initialDraws = 0
                var initialSnapshots = 0
                var initialPreparations = 0
                var initialInkScans = 0
                scene.onMain {
                    initialMeasures = measureCount()
                    initialIntrinsicMeasures = intrinsicMeasureCount()
                    initialDraws = drawCount()
                    initialSnapshots = snapshotCount()
                    initialPreparations = preparationCount()
                    initialInkScans = inkScanCount()
                    activity.window.addOnFrameMetricsAvailableListener(listener, handler)
                    listening = true
                }
                val reversing = InstrumentationRegistry.getArguments().getString("profileReverse") == "true"
                val calls = scene.frames(240, reversing)
                SystemClock.sleep(100)
                scene.onMain {
                    activity.window.removeOnFrameMetricsAvailableListener(listener)
                    listening = false
                }
                val flush = CountDownLatch(1)
                handler.post { flush.countDown() }
                assertTrue(flush.await(5, TimeUnit.SECONDS))
                assertTrue("Missing hardware frame reports: ${samples.size}", samples.size >= 120)
                var measures = 0
                var intrinsicMeasures = 0
                var draws = 0
                var snapshots = 0
                var preparations = 0
                var inkScans = 0
                scene.onMain {
                    measures = measureCount() - initialMeasures
                    intrinsicMeasures = intrinsicMeasureCount() - initialIntrinsicMeasures
                    draws = drawCount() - initialDraws
                    snapshots = snapshotCount() - initialSnapshots
                    preparations = preparationCount() - initialPreparations
                    inkScans = inkScanCount() - initialInkScans
                }
                val result = JSONObject().apply {
                    put("mode", if (reading) "reading" else "overview")
                    put("previewEffectsDisabled", scene.disablePreviewEffects)
                    put("reversing", reversing)
                    put("frames", samples.size)
                    put("droppedReports", droppedReports)
                    put("scrollCalls", calls.size)
                    put("scrollP50Ms", percentile(calls, .5))
                    put("scrollP95Ms", percentile(calls, .95))
                    put("scrollMeanMs", calls.average())
                    put("scrollTotalMs", calls.sum())
                    put("frameP50Ms", percentile(samples.map { it.total }, .5))
                    put("frameP95Ms", percentile(samples.map { it.total }, .95))
                    put("layoutP95Ms", percentile(samples.map { it.layout }, .95))
                    put("drawP95Ms", percentile(samples.map { it.draw }, .95))
                    put("gpuP95Ms", percentile(samples.map { it.gpu }, .95))
                    put("commandP95Ms", percentile(samples.map { it.command }, .95))
                    put("vsyncIntervalP50Ms", percentile(scene.intervals, .5))
                    put("vsyncIntervalP95Ms", percentile(scene.intervals, .95))
                    put("deadlineP50Ms", percentile(samples.map { it.deadline }, .5))
                    put("overDeadlineFrames", samples.count { it.deadline > 0 && it.total > it.deadline })
                    put("measurementPasses", measures)
                    put("previewIntrinsicMeasurementPasses", intrinsicMeasures)
                    put("textDrawPasses", draws)
                    put("textSnapshots", snapshots)
                    put("textPreparations", preparations)
                    put("textInkScans", inkScans)
                }
                val label = InstrumentationRegistry.getArguments().getString("profileLabel") ?: "current"
                require(label.matches(Regex("[a-zA-Z0-9_-]+")))
                val mode = if (reading) "reading" else "overview"
                File(scene.activity.cacheDir, "card-flow-$label-$mode.json").writeText(result.toString())
                val screenshot = requireNotNull(
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                )
                try {
                    File(scene.activity.cacheDir, "card-flow-$label-$mode.png").outputStream().use {
                        assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    screenshot.recycle()
                }
                println("CardFlowProfile $result")
            } finally {
                scenario.onActivity {
                    if (listening) it.window.removeOnFrameMetricsAvailableListener(listener)
                    scene.recycler.adapter = null
                }
                worker.quitSafely()
                worker.join(5000)
            }
        }
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        require(values.isNotEmpty())
        val ordered = values.sorted()
        return ordered[ceil((ordered.size - 1) * fraction).toInt()]
    }

    private data class FrameSample(
        val total: Double, val layout: Double, val draw: Double, val deadline: Double,
        val gpu: Double, val command: Double
    )

    private class Scene(val activity: TimescapeFlowTestActivity) {
        val root = FrameLayout(activity).apply { clipChildren = false; clipToPadding = false }
        val recycler = RecyclerView(activity).apply { clipChildren = false; clipToPadding = false }
        private val side = activity.resources.displayMetrics.widthPixels - dp(32)
        val manager = RightRailFlowLayoutManager(side, side,
            (side * .28f).coerceIn(dp(92).toFloat(), dp(124).toFloat()).roundToInt(), dp(8))
        val cards = mutableSetOf<TimescapeCardLayout>()
        val intervals = mutableListOf<Double>()
        val disablePreviewEffects =
            InstrumentationRegistry.getArguments().getString("profileDisablePreviewEffects") == "true"
        val adapter = CardsAdapter(
            TintStyle.None,
            onItemClick = { manager.focus(it) },
            onItemDoubleClick = { _, _ -> },
            onItemLongPress = { _, _ -> false },
            onStickyNotesClick = {}
        )

        init {
            recycler.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    cards += view as TimescapeCardLayout
                    if (disablePreviewEffects) {
                        val image = view.findViewById<ImageView>(R.id.previewImage)
                        (image as? PhotographImageView)?.setSoftening(0f)
                        (image as? PhotographImageView)?.setFadingEdges(false)
                        image.setRenderEffect(null)
                        image.colorFilter = null
                        image.foreground = null
                        image.background = null
                    }
                }
                override fun onChildViewDetachedFromWindow(view: View) = Unit
            })
            recycler.layoutManager = manager
            recycler.itemAnimator = null
            recycler.adapter = adapter
            root.addView(recycler, FrameLayout.LayoutParams(-1, -1))
            adapter.submitList((0 until 40).map {
                CardItem(it.toLong(), "Memory $it · 山间晨光",
                    "Photographs, notes and quiet moments through the seasons. ".repeat(40),
                    bg = BgImage.Res(if (it % 2 == 0) R.drawable.bg_2 else R.drawable.bg_3))
            }) { manager.restoreState(0, false) }
        }

        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
        fun holder(position: Int) = recycler.findViewHolderForAdapterPosition(position) as? CardsAdapter.VH
        fun onMain(block: Scene.() -> Unit) =
            InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
        fun measureCount(): Int = cards.sumOf { it.measurePassCount }
        fun intrinsicMeasureCount(): Int = cards.sumOf { it.previewIntrinsicMeasureCount }
        fun snapshotCount(): Int = cards.sumOf { it.textTransitionSnapshotCount }
        fun preparationCount(): Int = cards.sumOf { it.textTransitionPreparationCount }
        fun inkScanCount(): Int = cards.sumOf { it.textTransitionInkScanCount }
        fun drawCount(): Int {
            fun count(view: View): Int {
                var result = (view as? TimescapeTextView)?.drawPassCount ?: 0
                if (view is ViewGroup) for (i in 0 until view.childCount) result += count(view.getChildAt(i))
                return result
            }
            return cards.sumOf { count(it) }
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                onMain { ready = condition() }
                if (ready) return
                SystemClock.sleep(30)
            }
            throw AssertionError("Card profile fixture was not ready")
        }
        fun frames(count: Int, reversing: Boolean = false): List<Double> {
            val complete = CountDownLatch(1)
            val durations = mutableListOf<Double>()
            intervals.clear()
            var previousFrame = 0L
            lateinit var choreographer: Choreographer
            lateinit var callback: Choreographer.FrameCallback
            onMain {
                choreographer = Choreographer.getInstance()
                callback = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (previousFrame != 0L) intervals += (frameTimeNanos - previousFrame) / 1e6
                        previousFrame = frameTimeNanos
                        val start = System.nanoTime()
                        recycler.scrollBy(0, dp(if (reversing && durations.size / 8 % 2 == 1) -14 else 14))
                        durations += (System.nanoTime() - start) / 1e6
                        if (durations.size < count) choreographer.postFrameCallback(this)
                        else complete.countDown()
                    }
                }
                choreographer.postFrameCallback(callback)
            }
            try {
                assertTrue("Timed out rendering $count scrolling frames", complete.await(60, TimeUnit.SECONDS))
            } finally {
                onMain { choreographer.removeFrameCallback(callback) }
            }
            return durations
        }
    }
}
