package com.example.timescapedemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class TimescapeFlowTest {
    @Test
    fun overviewUsesPhotographsPerspectiveAndRaisedReadableText() = scene { s ->
        s.onMain {
            val text = holder(0)
            assertFalse(manager.hasSelection())
            assertEquals(0f, text.presentation.expansionProgress, 0f)
            assertTrue(text.previewImage.drawable != null)
            assertEquals(text.preview.width, text.previewImage.width)
            assertEquals(text.preview.height, text.previewImage.height)
            assertEquals(ImageView.ScaleType.CENTER_CROP, text.previewImage.scaleType)
            assertTrue(text.preview.foreground != null)
            assertSame(text.previewSurface, text.previewText.parent)
            assertSame(text.previewSurface, text.preview.parent)
            assertTrue(text.previewText.paddingStart + text.previewText.translationX < text.preview.left)
            assertTrue(text.previewText.translationZ > text.preview.translationZ)
            assertTrue(text.previewTitle is TimescapeTextView)
            assertTrue(text.previewSnippet is TimescapeTextView)
            assertTrue(text.itemView.rotationX in 8f..10f)
            assertTrue(text.itemView.rotationY in 1.5f..4.5f)
            val next = holder(1).itemView
            assertTrue(next.left + next.width / 2f < text.itemView.left + text.itemView.width / 2f)
            assertTrue(next.rotation < 0f)
            assertTrue(holder(1).previewText.translationX > 0f)
            assertTrue(text.previewText.rotationY > 0f)
            assertEquals(-3f, text.itemView.rotationX + text.previewText.rotationX, 0.001f)
            assertEquals(
                (text.previewText.height - text.previewText.paddingBottom).toFloat(),
                text.previewText.pivotY, 0.001f
            )
            assertTrue(text.previewText.translationY < 0f)
            assertTrue(text.previewTitle.shadowRadius > 0f)
            assertEquals(2, text.previewSnippet.maxLines)
            assertTrue(text.previewTime.bottom <= text.preview.height)
            assertFalse(text.collapseButton.isShown)
            assertEquals(View.INVISIBLE, text.presentation.getChildAt(0).visibility)
            assertTrue(holder(1).itemView.width < text.itemView.width)
            assertTrue(holder(1).itemView.translationZ < text.itemView.translationZ)
        }
    }

    @Test
    fun previewTextStaysCompactWithOrWithoutATitleAndRespectsTextSizeChanges() = scene { s ->
        fun pixels(sp: Float) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sp, s.root.resources.displayMetrics
        )
        for ((body, title) in listOf(18f to 20f, 26f to 24f)) {
            s.onMain { adapter.setBodyTextSize(body) }
            val summary = (body - 2f).coerceIn(14f, 22f)
            for (index in listOf(0, 6)) {
                s.onMain { manager.restoreState(index, false) }
                s.await {
                    s.recycler.findViewHolderForAdapterPosition(index) != null &&
                        kotlin.math.abs(s.holder(index).previewSnippet.textSize - pixels(summary)) < 0.1f
                }
                s.onMain {
                    val card = holder(index)
                    assertEquals(pixels(summary), card.previewSnippet.textSize, 0.1f)
                    assertEquals(pixels(title), card.previewTitle.textSize, 0.1f)
                    assertEquals(pixels(11f).roundToInt().toFloat(), card.previewTime.textSize, 0.1f)
                    assertEquals(index == 0, card.previewTitle.isShown)
                    assertTrue(card.previewSnippet is TimescapeTextView)
                    assertTrue(card.previewText.translationZ > 0f)
                    assertEquals(2, card.previewSnippet.maxLines)
                }
            }
        }
    }

    @Test
    fun raisedLetteringRendersLitEdgesAndDetachedShadowsWithoutChangingLayout() = scene { s ->
        s.onMain {
            val flat = TextView(root.context)
            val raised = TimescapeTextView(root.context)
            val width = dp(320)
            for (text in listOf(flat, raised)) {
                text.text = "Timescape 立体文字\nFloating type above a photograph"
                text.textSize = 32f
                text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                text.setTextColor(Color.WHITE)
                text.setPadding(dp(8), dp(8), dp(12), dp(12))
                text.maxLines = 2
                text.ellipsize = TextUtils.TruncateAt.END
                text.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                text.layout(0, 0, text.measuredWidth, text.measuredHeight)
            }
            assertEquals(flat.height, raised.height)
            assertEquals(flat.baseline, raised.baseline)
            assertEquals(flat.layout.lineCount, raised.layout.lineCount)
            for (line in 0 until flat.layout.lineCount) {
                assertEquals(flat.layout.getEllipsisCount(line), raised.layout.getEllipsisCount(line))
            }
            val flatBitmap = Bitmap.createBitmap(width, flat.height, Bitmap.Config.ARGB_8888)
            val raisedBitmap = Bitmap.createBitmap(width, raised.height, Bitmap.Config.ARGB_8888)
            try {
                flat.draw(Canvas(flatBitmap))
                raised.draw(Canvas(raisedBitmap))
                val density = root.resources.displayMetrics.density
                val shadowX = (1.2f * density).roundToInt()
                val shadowY = (3.2f * density).roundToInt()
                var displacedShadow = 0
                var blueEdge = 0
                var brightFace = 0
                var flatFace = 0
                for (y in shadowY until flatBitmap.height) {
                    for (x in shadowX until width) {
                        val plain = flatBitmap.getPixel(x, y)
                        val lit = raisedBitmap.getPixel(x, y)
                        if (Color.alpha(plain) > 200) {
                            flatFace++
                            if (Color.red(lit) > 210) brightFace++
                        }
                        if (Color.alpha(plain) < 8 && Color.alpha(lit) > 30) {
                            if (Color.red(lit) < 128 &&
                                Color.alpha(flatBitmap.getPixel(x - shadowX, y - shadowY)) > 200
                            ) displacedShadow++
                            if (Color.alpha(lit) > 90 && Color.red(lit) in 128..200 &&
                                Color.blue(lit) - Color.red(lit) > 10
                            ) blueEdge++
                        }
                    }
                }
                assertTrue("Missing detached glyph shadows", displacedShadow > dp(10))
                assertTrue("Missing cool lit glyph edges", blueEdge > dp(10))
                assertTrue("Letter faces should stay bright and crisp", brightFace > flatFace * 0.8f)
                assertNull(raised.paint.shader)
                raised.setTextColor(Color.rgb(220, 70, 100))
                raisedBitmap.eraseColor(Color.TRANSPARENT)
                raised.draw(Canvas(raisedBitmap))
                var coloredFace = 0
                for (y in shadowY until flatBitmap.height) {
                    for (x in shadowX until width) {
                        if (Color.alpha(flatBitmap.getPixel(x, y)) > 200) {
                            val lit = raisedBitmap.getPixel(x, y)
                            if (Color.red(lit) > Color.green(lit) * 1.5f) coloredFace++
                        }
                    }
                }
                assertTrue("Letter lighting must preserve the chosen text color", coloredFace > flatFace * 0.8f)
            } finally {
                flatBitmap.recycle()
                raisedBitmap.recycle()
            }
        }
    }

    @Test
    fun overviewAndReadingKeepCardEdgesNearlyHorizontal() = scene { s ->
        fun assertSubtlePerspective() = s.onMain {
            for (i in 0 until recycler.childCount) {
                val card = recycler.getChildAt(i)
                assertTrue(card.rotationX in 0f..10f)
                assertTrue(kotlin.math.abs(card.rotationY) <= 4.5f)
                assertTrue(kotlin.math.abs(card.rotation) <= 0.6f)
                val corners = floatArrayOf(
                    0f, 0f, card.width.toFloat(), 0f,
                    0f, card.height.toFloat(), card.width.toFloat(), card.height.toFloat()
                )
                card.matrix.mapPoints(corners)
                for (edge in listOf(0, 4)) {
                    val angle = Math.toDegrees(kotlin.math.atan2(
                        kotlin.math.abs(corners[edge + 3] - corners[edge + 1]).toDouble(),
                        kotlin.math.abs(corners[edge + 2] - corners[edge]).toDouble()
                    ))
                    assertTrue("Card edge tilts by $angle degrees", angle <= 3.0)
                }
            }
        }
        for (index in listOf(0, 3, 7)) {
            s.onMain { manager.restoreState(index, false) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertSubtlePerspective()
        }
        s.onMain { manager.restoreState(3, true) }
        s.await { s.holder(3).presentation.expansionProgress == 1f }
        assertSubtlePerspective()
        repeat(3) {
            s.onMain { recycler.scrollBy(0, dp(138) / 4) }
            assertSubtlePerspective()
        }
    }

    @Test
    fun portraitAndLandscapeImagesStayWholeNotSideThumbnails() = scene { s ->
        for ((index, aspect) in listOf(1 to 0.5f, 2 to 2f)) {
            s.onMain { manager.restoreState(index, false) }
            s.await {
                s.recycler.findViewHolderForAdapterPosition(index) != null &&
                    s.holder(index).previewImage.drawable != null &&
                    kotlin.math.abs(s.holder(index).preview.width.toFloat() / s.holder(index).preview.height - aspect) < 0.02f
            }
            s.onMain {
                val image = holder(index)
                assertTrue(image.presentation.isMediaPreview)
                assertEquals(View.GONE, image.previewText.visibility)
                assertEquals(View.GONE, image.previewScrim.visibility)
                assertEquals(image.preview.width, image.previewImage.width)
                assertEquals(image.preview.height, image.previewImage.height)
                assertEquals(0, image.preview.left)
                assertEquals(image.previewSurface.width, image.preview.width)
                assertEquals(ImageView.ScaleType.FIT_CENTER, image.previewImage.scaleType)
                assertTrue(image.preview.foreground == null)
                assertTrue(image.previewImage.colorFilter == null)
                assertTrue(image.preview.height < recycler.height / 2)
            }
        }
    }

    @Test
    fun oneTapOpensAndCollapseReturnsToPerspective() = scene { s ->
        var compact = 0
        s.onMain {
            compact = holder(0).itemView.height
            tap(holder(0).preview)
        }
        s.await { s.holder(0).presentation.expansionProgress == 1f }
        s.onMain {
            assertTrue(manager.isFocused(0))
            assertTrue(holder(0).itemView.height > compact)
            assertEquals(0f, holder(0).itemView.rotationX, 0.001f)
            assertEquals(0f, holder(0).itemView.rotationY, 0.001f)
            assertTrue(holder(0).collapseButton.isShown)
            assertEquals(0.55f, holder(0).snippet.depthAmount, 0f)
            assertFalse(holder(0).preview.isShown)
            assertEquals(0f, holder(1).presentation.expansionProgress, 0f)
            holder(0).collapseButton.performClick()
        }
        s.await { !s.manager.hasSelection() && s.holder(0).presentation.expansionProgress == 0f }
        s.onMain { assertEquals(compact, holder(0).itemView.height) }
    }

    @Test
    fun overviewScrollingStaysCollapsedAndExplicitSelectionStillSwitchesCards() = scene { s ->
        s.onMain { recycler.scrollBy(0, dp(130)) }
        s.onMain {
            assertFalse(manager.hasSelection())
            for (i in 0 until recycler.childCount) {
                assertEquals(0f, (recycler.getChildAt(i) as TimescapeCardLayout).expansionProgress, 0f)
            }
        }
        s.onMain { manager.focus(0) }
        s.await { s.manager.isFocused(0) }
        s.onMain { manager.focus(1) }
        s.await { s.manager.isFocused(1) }
        s.onMain {
            assertEquals(0f, holder(0).presentation.expansionProgress, 0f)
            manager.clearFocus(true)
        }
    }

    @Test
    fun readingProgressTracksScrollAndKeepsBothCardsPartlyOpen() = scene { s ->
        s.onMain { manager.restoreState(0, true) }
        s.await { s.holder(0).presentation.expansionProgress == 1f }
        var previous = 1f
        var next = 0f
        repeat(3) {
            s.onMain { recycler.scrollBy(0, dp(138) / 4) }
            s.onMain {
                val departing = holder(0).presentation.expansionProgress
                val arriving = holder(1).presentation.expansionProgress
                assertTrue(departing > 0f && departing < previous)
                assertTrue(arriving > next && arriving < 1f)
                assertEquals(1f, departing + arriving, 0.001f)
                assertTrue(manager.hasSelection())
                assertFalse(manager.isFocused(0))
                assertFalse(manager.isFocused(1))
                assertTrue(holder(0).itemView.rotationX > 0f)
                assertTrue(holder(1).itemView.rotationX > 0f)
                for (index in 0..1) {
                    val card = holder(index)
                    assertEquals(
                        -3f * (1f - card.presentation.expansionProgress),
                        card.itemView.rotationX + card.previewText.rotationX, 0.001f
                    )
                    assertEquals(card.itemView.height, card.itemView.clipBounds?.bottom)
                    assertEquals(1f, card.preview.alpha, 0f)
                    assertEquals(card.itemView.height, card.previewSurface.bottom)
                    assertTrue(kotlin.math.abs(
                        card.presentation.getChildAt(0).top +
                            card.presentation.findViewById<View>(R.id.card).top - card.previewSurface.top
                    ) <= 1)
                    if (!card.presentation.isMediaPreview) {
                        assertEquals(
                            card.presentation.getChildAt(0).left + card.card.left,
                            card.previewSurface.left + card.preview.left
                        )
                    }
                    if (card.presentation.getChildAt(0).alpha > 0f) {
                        assertEquals(0f, card.previewText.alpha, 0f)
                    }
                    if (card.presentation.expansionProgress <= 0.85f) {
                        assertEquals(0f, card.collapseButton.alpha, 0f)
                    }
                }
                previous = departing
                next = arriving
            }
        }
        s.onMain { manager.settleScrollIfNeeded() }
        s.await { s.manager.isFocused(1) && s.holder(1).presentation.expansionProgress == 1f }
        s.onMain { assertEquals(0f, holder(0).presentation.expansionProgress, 0f) }
    }

    @Test
    fun reversingDragRestoresOriginalCardAndShortDragSnapsBack() = scene { s ->
        s.onMain { manager.restoreState(1, true) }
        s.await { s.holder(1).presentation.expansionProgress == 1f }
        s.onMain { recycler.scrollBy(0, dp(138) * 2 / 5) }
        s.onMain {
            assertTrue(holder(1).presentation.expansionProgress < 1f)
            assertTrue(holder(2).presentation.expansionProgress > 0f)
            recycler.scrollBy(0, -dp(138) * 2 / 5)
        }
        s.onMain {
            assertEquals(1f, holder(1).presentation.expansionProgress, 0.001f)
            assertEquals(0f, holder(2).presentation.expansionProgress, 0.001f)
            recycler.scrollBy(0, -dp(138) / 3)
            manager.settleScrollIfNeeded()
        }
        s.await { s.manager.isFocused(1) && s.holder(1).presentation.expansionProgress == 1f }
        s.onMain { assertEquals(0, manager.offsetTo(1)) }
    }

    @Test
    fun midpointChangesDoNotJumpNeighborPositionsOrDepth() = scene { s ->
        s.onMain { manager.restoreState(0, true) }
        s.await { s.holder(0).presentation.expansionProgress == 1f }
        var oldTop = 0
        var oldZ = 0f
        s.onMain {
            recycler.scrollBy(0, dp(138) / 2 - 2)
            oldTop = holder(1).itemView.top
            oldZ = holder(1).itemView.translationZ
            recycler.scrollBy(0, 4)
        }
        s.onMain {
            assertTrue(kotlin.math.abs(holder(1).itemView.top - oldTop) < dp(16))
            assertTrue(kotlin.math.abs(holder(1).itemView.translationZ - oldZ) < dp(2))
            assertTrue(holder(0).presentation.expansionProgress > 0.4f)
            assertTrue(holder(1).presentation.expansionProgress > 0.4f)
        }
    }

    @Test
    fun firstAndLastReadingCardsStayOpenAtBoundaries() = scene { s ->
        for ((index, direction) in listOf(0 to -1, 7 to 1)) {
            s.onMain { manager.restoreState(index, true) }
            s.await { s.recycler.findViewHolderForAdapterPosition(index) != null }
            s.onMain {
                recycler.scrollBy(0, direction * dp(400))
                manager.settleScrollIfNeeded()
            }
            s.await { s.manager.isFocused(index) && s.holder(index).presentation.expansionProgress == 1f }
            s.onMain { assertEquals(0, manager.offsetTo(index)) }
        }
    }

    @Test
    fun multiCardScrollSettlesWithOnlyOneOpenCardAndSignalsPlaybackReadiness() = scene { s ->
        val readiness = mutableListOf<Boolean>()
        s.onMain {
            manager.restoreState(0, true)
            manager.selectionListener = { index -> readiness += index != null && manager.isFocused(index) }
        }
        s.await { s.holder(0).presentation.expansionProgress == 1f }
        s.onMain {
            recycler.scrollBy(0, dp(138) * 3)
            assertFalse(manager.isFocused(3))
            assertFalse(readiness.last())
            recycler.scrollBy(0, dp(90))
            manager.settleScrollIfNeeded()
        }
        s.await { s.manager.isFocused(4) && s.holder(4).presentation.expansionProgress == 1f }
        s.onMain {
            assertTrue(readiness.last())
            assertEquals(4, manager.currentSelectionIndex())
            for (i in 0 until recycler.childCount) {
                val child = recycler.getChildAt(i) as TimescapeCardLayout
                assertEquals(if (recycler.getChildAdapterPosition(child) == 4) 1f else 0f, child.expansionProgress, 0.001f)
            }
        }
    }

    @Test
    fun interruptedSnapCanReverseOrExitReadingWithoutReopening() = scene { s ->
        s.onMain { manager.restoreState(0, true) }
        s.await { s.holder(0).presentation.expansionProgress == 1f }
        s.onMain {
            recycler.scrollBy(0, dp(100))
            manager.settleScrollIfNeeded()
            recycler.scrollBy(0, -dp(80))
            manager.settleScrollIfNeeded()
        }
        s.await { s.manager.isFocused(0) }
        s.onMain {
            recycler.scrollBy(0, dp(100))
            manager.settleScrollIfNeeded()
            manager.clearFocus()
        }
        s.await { !s.manager.hasSelection() }
        SystemClock.sleep(300)
        s.onMain {
            for (i in 0 until recycler.childCount) {
                assertEquals(0f, (recycler.getChildAt(i) as TimescapeCardLayout).expansionProgress, 0f)
            }
        }

    }

    @Test
    fun actualDragFromAnOpenCardSnapsToNextReadingCard() = scene { s ->
        s.onMain { manager.restoreState(0, true) }
        s.await { s.holder(0).presentation.expansionProgress == 1f }
        val now = SystemClock.uptimeMillis()
        val startY = s.recycler.height * 0.55f
        fun event(action: Int, y: Float) {
            s.onMain {
                val e = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, recycler.width / 2f, y, 0)
                recycler.dispatchTouchEvent(e)
                e.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN, startY)
        for (i in 1..8) {
            SystemClock.sleep(35)
            event(MotionEvent.ACTION_MOVE, startY - s.dp(115) * i / 8f)
        }
        s.onMain {
            assertTrue(holder(0).presentation.expansionProgress < 1f)
            assertTrue(holder(1).presentation.expansionProgress > 0f)
        }
        SystemClock.sleep(250)
        event(MotionEvent.ACTION_UP, startY - s.dp(115))
        s.await { s.manager.isFocused(1) && s.holder(1).presentation.expansionProgress == 1f }
    }

    @Test
    fun flingFinishesOnAnExpandedCardInsteadOfReturningToOverview() = scene { s ->
        s.onMain { manager.restoreState(1, true) }
        s.await { s.holder(1).presentation.expansionProgress == 1f }
        s.onMain { assertTrue(recycler.fling(0, dp(1200))) }
        s.await {
            val index = s.manager.currentSelectionIndex()
            s.recycler.scrollState == RecyclerView.SCROLL_STATE_IDLE && index != null &&
                index > 1 && s.manager.isFocused(index) &&
                s.recycler.findViewHolderForAdapterPosition(index) != null &&
                s.holder(index).presentation.expansionProgress == 1f
        }
    }

    @Test
    fun receiptAndOriginalActionsExistOnlyWhenOpened() = scene { s ->
        s.onMain { manager.restoreState(3, false) }
        s.await { s.recycler.findViewHolderForAdapterPosition(3) != null }
        s.onMain {
            assertFalse(holder(3).receipt.isShown)
            manager.focus(3)
        }
        s.await { s.manager.isFocused(3) && s.holder(3).receipt.isShown }
        s.onMain {
            assertTrue(holder(3).stickyNotesButton.isShown)
            assertTrue(holder(3).card.height <= (recycler.height * 2f / 3).toInt() + 1)
            assertTrue(holder(3).receipt.height > 0)
        }
    }

    @Test
    fun interruptedAnimationAndRecyclingDoNotLeakPresentation() = scene { s ->
        s.onMain {
            manager.focus(0)
            manager.clearFocus()
            manager.focus(1)
        }
        s.await { s.manager.isFocused(1) }
        s.onMain {
            assertEquals(0f, holder(0).presentation.expansionProgress, 0f)
            manager.clearFocus(true)
            manager.restoreState(7, false)
        }
        s.await { s.recycler.findViewHolderForAdapterPosition(7) != null }
        s.onMain { manager.restoreState(0, false) }
        s.await { s.recycler.findViewHolderForAdapterPosition(0) != null }
        s.onMain {
            assertFalse(manager.hasSelection())
            assertEquals("Mountain morning", holder(0).previewTitle.text.toString())
            assertTrue(holder(0).previewText.isShown)
            assertTrue(holder(0).previewImage.colorFilter != null)
            assertEquals(0.55f, holder(0).snippet.depthAmount, 0f)
            assertEquals(0f, holder(7).snippet.depthAmount, 0f)
        }
    }

    @Test
    fun shortViewportAndLargeTextKeepPreviewAndReadingSurfaceBounded() = scene { s ->
        s.onMain {
            recycler.layoutParams.height = dp(340)
            recycler.requestLayout()
            adapter.setBodyTextSize(26f)
            manager.restoreState(0, false)
        }
        s.await { s.recycler.height == s.dp(340) }
        s.onMain {
            assertTrue(holder(0).previewSnippet.height > 0)
            assertTrue(holder(0).previewTime.bottom <= holder(0).preview.height)
            manager.focus(0)
        }
        s.await { s.manager.isFocused(0) }
        s.onMain { assertTrue(holder(0).card.height <= (recycler.height * 2f / 3).toInt() + 1) }
    }

    private fun scene(test: (Scene) -> Unit) {
        ActivityScenario.launch(TimescapeFlowTestActivity::class.java).use { scenario ->
            lateinit var scene: Scene
            scenario.onActivity { activity ->
                scene = Scene(activity)
                activity.setContentView(scene.root)
            }
            try {
                scene.await { scene.recycler.childCount > 0 && scene.holder(0).preview.height > 0 }
                test(scene)
            } finally {
                scenario.onActivity { scene.recycler.adapter = null }
                scene.files.forEach { it.delete() }
            }
        }
    }

    private class Scene(activity: TimescapeFlowTestActivity) {
        val root = FrameLayout(activity)
        val recycler = RecyclerView(activity)
        val manager = RightRailFlowLayoutManager(dp(340), dp(360), dp(138), dp(8))
        val files = mutableListOf<File>()
        val adapter = CardsAdapter(
            TintStyle.None,
            onItemClick = { manager.restoreState(it, false); manager.focus(it) },
            onItemDoubleClick = { _, _ -> },
            onItemLongPress = { _, _ -> false },
            onStickyNotesClick = {},
            onItemCollapse = { manager.clearFocus() }
        )

        init {
            fun photo(width: Int, height: Int): File {
                val file = File.createTempFile("timescape-flow-", ".png", activity.cacheDir)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint()
                paint.color = Color.rgb(32, 113, 185)
                canvas.drawPaint(paint)
                paint.color = Color.rgb(243, 163, 76)
                canvas.drawRect(0f, 0f, width / 2f, height / 2f, paint)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                files += file
                return file
            }
            val portrait = photo(300, 600)
            val landscape = photo(600, 300)
            val options = HandwritingOptions(Color.WHITE, Color.BLACK, 2f, 300, 600,
                HandwritingFormat.PNG, HandwritingPaperStyle.PLAIN, HandwritingPenType.ROUND,
                10f, HandwritingEraserType.ROUND)
            recycler.layoutManager = manager
            recycler.itemAnimator = null
            recycler.adapter = adapter
            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) manager.settleScrollIfNeeded()
                }
            })
            root.addView(recycler, FrameLayout.LayoutParams(-1, -1))
            adapter.submitList(listOf(
                CardItem(1, "Mountain morning", "A quiet moment above the clouds. ".repeat(35), bg = BgImage.Res(R.drawable.bg_2)),
                CardItem(2, "Portrait", "", image = CardImage(Uri.fromFile(portrait))),
                CardItem(3, "Landscape", "", image = CardImage(Uri.fromFile(landscape))),
                CardItem(4, "Receipt", "The original receipt stays attached. ".repeat(12),
                    bg = BgImage.Res(R.drawable.bg_3),
                    thermalReceipt = ThermalReceipt(HandwritingSide(portrait.path, options))),
                CardItem(5, "Handwriting", "", handwriting = HandwritingContent(portrait.path, options)),
                CardItem(6, "Afternoon", "Light through the trees.", bg = BgImage.Res(R.drawable.bg_3)),
                CardItem(7, "", "A title-free thought.", bg = BgImage.Res(R.drawable.bg_2)),
                CardItem(8, "Plain text", "No photograph attached.")
            )) { manager.restoreState(0, false) }
        }

        fun dp(value: Int) = (value * root.resources.displayMetrics.density).toInt()
        fun holder(position: Int) = recycler.findViewHolderForAdapterPosition(position) as CardsAdapter.VH
        fun onMain(block: Scene.() -> Unit) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 10000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                onMain { ready = condition() }
                if (ready) return
                SystemClock.sleep(40)
            }
            var state = ""
            onMain {
                state = "selection=${manager.currentSelectionIndex()}, nearest=${manager.nearestIndex()}, " +
                    "children=" + (0 until recycler.childCount).joinToString {
                        val child = recycler.getChildAt(it) as TimescapeCardLayout
                        "${recycler.getChildAdapterPosition(child)}:${child.expansionProgress}"
                    }
            }
            throw AssertionError("Timescape flow did not reach the expected state: $state")
        }
        fun tap(view: View) {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now + 50, action, view.width / 2f, view.height / 2f, 0)
                view.dispatchTouchEvent(event)
                event.recycle()
            }
        }
    }
}
