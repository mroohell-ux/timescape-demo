package com.example.timescapedemo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.text.TextUtils
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class TimescapeFlowTest {
    @Test
    fun previewDatesSitOnTheLowerCardFaceSeparateFromRaisedProse() = scene { s ->
        for (index in listOf(0, 6, 7)) {
            s.onMain { manager.restoreState(index, false) }
            s.await { s.recycler.findViewHolderForAdapterPosition(index) != null }
            s.onMain {
                val card = holder(index)
                assertEquals(dp(112), card.previewText.minimumHeight)
                assertTrue(card.previewTime.isShown)
                assertNotSame(card.previewTitle.parent, card.previewTime.parent)
                assertEquals(card.time.text.toString(), card.previewTime.text.toString())
                assertEquals(normalizeCardText(checkNotNull(adapter.getItemAt(index)).snippet).text,
                    card.previewSnippet.text.toString())
                val date = boundsInAncestor(card.previewTime, card.presentation)
                val prose = boundsInAncestor(card.previewSnippet, card.presentation)
                val mark = boundsInAncestor(card.previewTypeIcon, card.presentation)
                assertTrue("Card $index date $date touches raised prose $prose", date.top >= prose.bottom + dp(2))
                assertTrue(date.right < mark.left)
                assertEquals(4, card.previewSnippet.maxLines)
                assertEquals(checkNotNull(adapter.getItemAt(index)).title.isNotBlank(), card.previewHeading.isShown)
            }
        }
        s.onMain { manager.restoreState(5, false) }
        s.await { (3..7).all { s.recycler.findViewHolderForAdapterPosition(it) != null } }
        s.onMain {
            for (index in listOf(3, 6, 7)) {
                val card = holder(index)
                assertEquals(0f, card.previewTime.rotationX, 0f)
                assertEquals(0f, card.previewTime.translationZ, 0f)
                assertTrue(boundsInAncestor(card.previewTime, card.presentation).top >
                    boundsInAncestor(card.previewSnippet, card.presentation).bottom)
            }
        }
        capturePerspective(s, "offset-date-overview", 3..7)
    }

    @Test
    fun readingDateSharesCompactIconsAndKeepsAllActionTargets() = scene(allActions = true) { s ->
        for (index in listOf(0, 5, 6, 7)) {
            s.onMain { manager.restoreState(index, true) }
            s.await {
                s.manager.isFocused(index) && s.holder(index).presentation.expansionProgress == 1f &&
                    !s.holder(index).presentation.isLayoutRequested
            }
            s.onMain {
                val card = holder(index)
                assertSame(card.time.parent, card.stickyNotesButton.parent)
                assertEquals(Gravity.END or Gravity.CENTER_VERTICAL, card.time.gravity)
                val date = boundsInAncestor(card.time, card.card)
                val body = boundsInAncestor(
                    if (checkNotNull(adapter.getItemAt(index)).title.isNotBlank()) {
                        card.titleContainer
                    } else card.snippet,
                    card.card
                )
                val targetPixels = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 28f, root.resources.displayMetrics
                ).roundToInt()
                for (button in listOf(card.collectionButton, card.receiptButton, card.stickyNotesButton)) {
                    assertTrue(button.isShown)
                    assertEquals(targetPixels, button.width)
                    assertEquals(targetPixels, button.height)
                    assertTrue(button.width - button.paddingLeft - button.paddingRight <= dp(17))
                    val icon = boundsInAncestor(button, card.card)
                    assertEquals(date.centerY(), icon.centerY(), 1f)
                    assertTrue(date.right <= icon.left)
                    assertTrue("Body $body overlaps header $icon on card $index", body.top >= icon.bottom)
                }
                assertTrue(card.time.textSize <= TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 12f, root.resources.displayMetrics
                ) + .1f)
                if (index != 0) {
                    val maximumTop = if (index == 6) dp(38) else dp(34)
                    assertTrue("Card $index header is still oversized: $body", body.top <= maximumTop)
                }
                if (index != 6) {
                    assertEquals(dp(24), card.titleSpeakButton.width)
                    assertEquals(dp(24), card.titleSpeakButton.height)
                    assertEquals(dp(16).toFloat(), (card.titleSpeakButton.width -
                        card.titleSpeakButton.paddingLeft - card.titleSpeakButton.paddingRight).toFloat(), 1f)
                    assertFalse(card.title.includeFontPadding)
                    assertEquals(
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, root.resources.displayMetrics),
                        card.title.textSize, .1f
                    )
                }
                if (index == 0) {
                    for (button in listOf(card.collectionButton, card.receiptButton,
                        card.stickyNotesButton, card.titleSpeakButton)) tap(button)
                }
            }
        }
        s.onMain {
            assertEquals(listOf("collection" to 1L, "receipt" to 1L,
                "sticky" to 1L, "speak" to 1L), actionClicks)
        }
    }

    @Test
    fun shortReadingTitleAndThreeLineBodyUseTheReducedHeaderSpace() = scene(allActions = true) { s ->
        s.onMain {
            val item = checkNotNull(adapter.getItemAt(5))
            item.title = "555"
            item.snippet = "The window is a heat pump.\n月亮与六便士，老人与海。\nA quiet moment before sunset."
            adapter.notifyItemChanged(5)
            manager.restoreState(5, true)
        }
        s.await {
            s.manager.isFocused(5) && s.holder(5).presentation.expansionProgress == 1f &&
                !s.holder(5).presentation.isLayoutRequested
        }
        s.onMain {
            val card = holder(5)
            val title = boundsInAncestor(card.titleContainer, card.card)
            val body = boundsInAncestor(card.snippet, card.card)
            assertEquals("555", card.title.text.toString())
            assertEquals(dp(32).toFloat(), title.top, 2f)
            assertEquals(dp(24).toFloat(), title.height(), 2f)
            assertEquals(dp(56).toFloat(), title.bottom, 2f)
            assertEquals(dp(4).toFloat(), body.top - title.bottom, 2f)
            assertEquals(3, card.snippet.lineCount)
            assertTrue("Short card still reserves excessive space: ${card.card.height}",
                card.card.height <= dp(162))
        }
        capturePerspective(s, "compact-short-reading-title", 5..5)
    }

    @Test
    fun compactTitleKeepsTwoMultilingualLinesWithoutFontPadding() = scene(allActions = true) { s ->
        s.onMain {
            val item = checkNotNull(adapter.getItemAt(5))
            item.title = "ÉÅ gyp 中文\nمرحبا بالعالم"
            adapter.notifyItemChanged(5)
            manager.restoreState(5, true)
        }
        s.await {
            s.manager.isFocused(5) && s.holder(5).presentation.expansionProgress == 1f &&
                !s.holder(5).presentation.isLayoutRequested
        }
        s.onMain {
            val card = holder(5)
            assertEquals("ÉÅ gyp 中文\nمرحبا بالعالم", card.title.text.toString())
            assertFalse(card.title.includeFontPadding)
            assertEquals(2, card.title.maxLines)
            assertEquals(2, card.title.lineCount)
            assertTrue(card.title.layout.height <= card.title.height)
            val title = boundsInAncestor(card.titleContainer, card.card)
            val body = boundsInAncestor(card.snippet, card.card)
            assertEquals(dp(32).toFloat(), title.top, 2f)
            assertTrue(body.top >= title.bottom + dp(4))
        }
        capturePerspective(s, "compact-multilingual-title", 5..5)
    }

    @Test
    fun longTextScrollsInsideItsViewportAndAFreshEdgeSwipeBrowsesTheFlow() = scene(allActions = true) { s ->
        s.longText()
        s.await {
            s.manager.isFocused(5) && s.holder(5).presentation.expansionProgress == 1f &&
                s.holder(5).textScroll.canScrollVertically(1)
        }
        s.onMain {
            val card = holder(5)
            val frame = boundsInAncestor(card.textScroll, recycler)
            val title = boundsInAncestor(card.titleContainer, recycler)
            val controls = boundsInAncestor(card.stickyNotesButton, recycler)
            assertTrue(card.snippet.height > card.textScroll.height)
            assertTrue(card.card.height <= card.presentation.readingHeightCap)
            swipe(recycler, frame.centerX(), frame.top + frame.height() * .75f,
                frame.centerX(), frame.top + frame.height() * .35f, MotionEvent.ACTION_CANCEL)
            assertTrue(card.textScroll.scrollY > dp(40))
            assertEquals(5, manager.currentSelectionIndex())
            assertEquals(1f, card.presentation.expansionProgress, 0f)
            assertEquals(title, boundsInAncestor(card.titleContainer, recycler))
            assertEquals(controls, boundsInAncestor(card.stickyNotesButton, recycler))
            val bottom = card.snippet.height - card.textScroll.height
            card.textScroll.scrollTo(0, bottom - dp(40))
            swipe(recycler, frame.centerX(), frame.top + frame.height() * .75f,
                frame.centerX(), frame.top + frame.height() * .35f, MotionEvent.ACTION_CANCEL)
            assertEquals(bottom, card.textScroll.scrollY)
            assertFalse(card.textScroll.canScrollVertically(1))
            assertEquals(5, manager.currentSelectionIndex())
        }
        capturePerspective(s, "long-text-bottom", 5..5)
        s.onMain {
            val frame = boundsInAncestor(holder(5).textScroll, recycler)
            swipe(recycler, frame.centerX(), frame.top + frame.height() * .75f,
                frame.centerX(), frame.top + frame.height() * .25f)
        }
        s.await {
            val selected = s.manager.currentSelectionIndex()
            selected != null && selected > 5 && s.manager.isFocused(selected)
        }
        s.onMain { manager.restoreState(5, true) }
        s.await { s.manager.isFocused(5) && s.holder(5).presentation.expansionProgress == 1f }
        s.onMain {
            val card = holder(5)
            card.textScroll.scrollTo(0, 0)
            val frame = boundsInAncestor(card.textScroll, recycler)
            swipe(recycler, frame.centerX(), frame.top + frame.height() * .25f,
                frame.centerX(), frame.top + frame.height() * .75f)
        }
        s.await {
            val selected = s.manager.currentSelectionIndex()
            selected != null && selected < 5 && s.manager.isFocused(selected)
        }
    }

    @Test
    fun plainLongTextScrollsToItsLastParagraphWithoutMovingTheCard() = scene { s ->
        s.longText(7)
        s.await { s.manager.isFocused(7) && s.holder(7).presentation.expansionProgress == 1f &&
            s.holder(7).textScroll.canScrollVertically(1) }
        s.onMain {
            val card = holder(7)
            val title = boundsInAncestor(card.titleContainer, recycler)
            assertNull(card.bg.drawable)
            assertEquals(0f, card.snippet.depthAmount, 0f)
            val frame = boundsInAncestor(card.textScroll, recycler)
            swipe(recycler, frame.centerX(), frame.top + frame.height() * .75f,
                frame.centerX(), frame.top + frame.height() * .35f, MotionEvent.ACTION_CANCEL)
            assertTrue(card.textScroll.scrollY > dp(40))
            assertEquals(7, manager.currentSelectionIndex())
            card.textScroll.scrollTo(0, Int.MAX_VALUE)
            val textLayout = checkNotNull(card.snippet.layout)
            assertTrue(card.snippet.text.contains("Front 100"))
            assertTrue(textLayout.getLineBottom(textLayout.lineCount - 1) + card.snippet.totalPaddingTop -
                card.textScroll.scrollY <= card.textScroll.height)
            assertFalse(card.textScroll.canScrollVertically(1))
            assertEquals(title, boundsInAncestor(card.titleContainer, recycler))
            assertEquals(7, manager.currentSelectionIndex())
        }
    }

    @Test
    fun longTextTurnsRebindingAndReopeningKeepIndependentFaceOffsets() = scene { s ->
        s.longText()
        s.await { s.holder(5).textScroll.canScrollVertically(1) &&
            s.holder(5).presentation.expansionProgress == 1f }
        var frontOffset = 0
        var backOffset = 0
        s.onMain {
            val card = holder(5)
            card.textScroll.scrollTo(0, dp(240))
            frontOffset = card.textScroll.scrollY
            val frame = boundsInAncestor(card.textScroll, recycler)
            swipe(recycler, frame.left + frame.width() * .1f, frame.centerY(),
                frame.right - frame.width() * .1f, frame.centerY())
        }
        s.await { s.adapter.currentFaceFor(5) == HandwritingFace.BACK &&
            !s.holder(5).flip.isFlipping && s.holder(5).textScroll.canScrollVertically(1) }
        s.onMain {
            val card = holder(5)
            assertEquals(0, card.textScroll.scrollY)
            assertTrue(card.snippet.text.startsWith("Back 1"))
            card.textScroll.scrollTo(0, dp(420))
            backOffset = card.textScroll.scrollY
            assertTrue(card.flip.flip())
        }
        s.await { s.adapter.currentFaceFor(5) == HandwritingFace.FRONT &&
            !s.holder(5).flip.isFlipping && s.holder(5).textScroll.scrollY == frontOffset }
        s.onMain { assertTrue(holder(5).flip.flip()) }
        s.await { s.adapter.currentFaceFor(5) == HandwritingFace.BACK &&
            !s.holder(5).flip.isFlipping && s.holder(5).textScroll.scrollY == backOffset }
        s.onMain { assertTrue(holder(5).flip.flip()) }
        s.await { s.adapter.currentFaceFor(5) == HandwritingFace.FRONT &&
            !s.holder(5).flip.isFlipping && s.holder(5).textScroll.scrollY == frontOffset }
        s.onMain { adapter.notifyItemChanged(5) }
        s.await { s.holder(5).textScroll.scrollY == frontOffset && !s.holder(5).textScroll.isLayoutRequested }
        s.onMain { manager.restoreState(5, false) }
        s.await { s.holder(5).presentation.expansionProgress == 0f }
        s.onMain { manager.focus(5) }
        s.await { s.manager.isFocused(5) && s.holder(5).presentation.expansionProgress == 1f &&
            s.holder(5).textScroll.scrollY == frontOffset }
        s.onMain {
            val card = holder(5)
            adapter.onBindViewHolder(card, 1)
            assertFalse(card.textScroll.readerEnabled)
            assertEquals(0, card.textScroll.scrollY)
            adapter.onBindViewHolder(card, 5)
            assertEquals(0, card.textScroll.scrollY)
        }
    }

    @Test
    fun shortTextDoesNotCaptureVerticalCardBrowsing() = scene { s ->
        s.onMain { manager.restoreState(5, true) }
        s.await { s.manager.isFocused(5) && s.holder(5).presentation.expansionProgress == 1f }
        s.onMain {
            val card = holder(5)
            assertFalse(card.textScroll.canScrollVertically(-1))
            assertFalse(card.textScroll.canScrollVertically(1))
            val frame = boundsInAncestor(card.textScroll, recycler)
            swipe(recycler, frame.centerX(), frame.centerY(), frame.centerX(), frame.centerY() - dp(220))
        }
        s.await {
            val selected = s.manager.currentSelectionIndex()
            selected != null && selected > 5 && s.manager.isFocused(selected)
        }
    }

    @Test
    fun longTextGlyphSnapshotsCoverOnlyTheVisibleWindowAtReadableResolution() = scene { s ->
        s.longText()
        s.await { s.holder(5).textScroll.canScrollVertically(1) &&
            s.holder(5).presentation.expansionProgress == 1f }
        s.onMain {
            val card = holder(5)
            assertTrue(card.snippet.height > card.textScroll.height * 8)
            card.textScroll.scrollTo(0, dp(360))
            val renderer = CardTextTransition(card.presentation)
            try {
                assertTrue(renderer.prepare(.8f, false))
                val bounds = renderer.readingSnapshotBounds
                val size = checkNotNull(renderer.readingSnapshotSize)
                assertEquals(card.textScroll.scrollY.toFloat(), bounds.top, 0f)
                assertEquals(card.textScroll.height.toFloat(), bounds.height(), 0f)
                assertTrue(size.first.toLong() * size.second <= 1_048_576)
                assertTrue(maxOf(size.first, size.second) <= 1536)
                assertTrue("Visible glyphs were scaled to unreadable whole-document resolution",
                    size.first / bounds.width() >= .5f)
                val previousCount = renderer.snapshotCount
                card.textScroll.scrollBy(0, dp(30))
                assertTrue(renderer.prepare(.7f, false))
                assertEquals(previousCount + 1, renderer.snapshotCount)
                assertEquals(card.textScroll.scrollY.toFloat(), renderer.readingSnapshotBounds.top, 0f)
            } finally {
                renderer.clear()
            }
            val reader = card.textScroll
            val bottom = reader.bottom
            reader.layout(reader.left, reader.top, reader.right, reader.top)
            assertFalse(renderer.prepare(.8f, false))
            reader.layout(reader.left, reader.top, reader.right, bottom)
            val otherText = listOf(card.time, card.title, card.previewTime, card.previewTitle, card.previewSnippet)
                .associateWith { it.visibility }
            try {
                otherText.keys.forEach { it.visibility = View.GONE }
                fun pixels(progress: Float): IntArray {
                    assertTrue(renderer.prepare(progress, false))
                    val frame = Bitmap.createBitmap(card.presentation.width, card.presentation.height,
                        Bitmap.Config.ARGB_8888)
                    try {
                        renderer.draw(Canvas(frame), progress)
                        return IntArray(frame.width * frame.height).also {
                            frame.getPixels(it, 0, frame.width, 0, 0, frame.width, frame.height)
                        }
                    } finally {
                        frame.recycle()
                    }
                }
                val first = pixels(.7f)
                pixels(.3f)
                assertArrayEquals(first, pixels(.7f))
                val viewport = boundsInAncestor(card.textScroll, card.presentation)
                var visibleInk = 0
                for (index in first.indices) {
                    val alpha = Color.alpha(first[index])
                    val y = index / card.presentation.width
                    if (y < viewport.top || y >= viewport.bottom) {
                        assertEquals("Scrolled text painted outside its fixed body viewport", 0, alpha)
                    } else if (alpha > 64) {
                        visibleInk++
                    }
                }
                assertTrue("The cropped snapshot lost the visible paragraphs", visibleInk > 500)
            } finally {
                renderer.clear()
                otherText.forEach { (view, visibility) -> view.visibility = visibility }
            }
            assertEquals(5, manager.currentSelectionIndex())
        }
    }

    @Test
    fun glassPhotographsKeepWhiteAndCustomLetteringAndNeverLeakIntoRecycledMedia() = scene { s ->
        s.onMain {
            val card = holder(0)
            assertNotNull(card.bg.background)
            assertNull(card.bg.foreground)
            assertNotNull(card.bg.colorFilter)
            assertEquals(ImageView.ScaleType.CENTER_CROP, card.bg.scaleType)
            assertEquals(Color.WHITE, card.title.currentTextColor)
            assertEquals(Color.WHITE, card.snippet.currentTextColor)
            assertEquals(Color.WHITE, card.titleSpeakButton.imageTintList?.defaultColor)
            val previewColor = card.previewTitle.currentTextColor
            val custom = Color.rgb(83, 214, 201)
            checkNotNull(adapter.getItemAt(0)).textColor = custom
            adapter.onBindViewHolder(card, 0)
            assertEquals(custom, card.title.currentTextColor)
            assertEquals(custom, card.snippet.currentTextColor)
            assertEquals(previewColor, card.previewTitle.currentTextColor)
            assertNull(card.cardContent.foreground)

            adapter.onBindViewHolder(card, 1)
            assertEquals(View.GONE, card.previewTime.visibility)
            assertNull(card.bg.foreground)
            assertNull(card.bg.background)
            assertNull(card.bg.colorFilter)
            assertNull(card.previewImage.foreground)
            assertNull(card.previewImage.background)
            assertNull(card.previewImage.colorFilter)
            assertEquals(ImageView.ScaleType.FIT_CENTER, card.previewImage.scaleType)

            adapter.onBindViewHolder(card, 0)
            assertEquals(View.VISIBLE, card.previewTime.visibility)
            assertNotNull(card.bg.background)
            assertNull(card.bg.foreground)
            assertNotNull(card.bg.colorFilter)
            assertNotNull(card.previewImage.background)
            assertNull(card.previewImage.foreground)
            assertNotNull(card.previewImage.colorFilter)
            assertEquals(custom, card.snippet.currentTextColor)
        }
    }

    @Test
    fun photographicOpacityDoesNotPulseBetweenOverviewAndReading() = scene { s ->
        s.onMain { manager.restoreState(0, true) }
        s.await {
            s.holder(0).presentation.expansionProgress == 1f &&
                ((s.holder(0).bg.drawable as? BitmapDrawable)?.bitmap?.width ?: 0) > 32 &&
                ((s.holder(0).previewImage.drawable as? BitmapDrawable)?.bitmap?.width ?: 0) > 32
        }
        s.onMain {
            val card = holder(0)
            val source = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(68, 111, 165))
            }
            card.bg.setImageBitmap(source)
            card.previewImage.setImageBitmap(source)
            for (text in listOf(card.title, card.snippet, card.previewTitle, card.previewSnippet)) text.text = ""
            val samples = mutableListOf<Int>()
            try {
                for (progress in listOf(0f, .1f, .25f, .5f, .75f, .9f, 1f, .5f, 0f)) {
                    card.presentation.setExpansion(progress)
                    card.presentation.measure(
                        View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    card.presentation.layout(0, 0, card.presentation.measuredWidth, card.presentation.measuredHeight)
                    val frame = Bitmap.createBitmap(card.itemView.width, card.itemView.height, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(frame).apply {
                            drawColor(Color.BLACK)
                            card.presentation.draw(this)
                        }
                        val photo = boundsInAncestor(if (progress == 1f) card.bg else card.previewImage,
                            card.presentation)
                        samples += frame.getPixel(photo.centerX().roundToInt(),
                            (photo.top + photo.height() * .7f).roundToInt())
                    } finally {
                        frame.recycle()
                    }
                }
                for (channel in listOf<(Int) -> Int>(Color::red, Color::green, Color::blue)) {
                    val values = samples.map(channel)
                    assertTrue("The same photograph changes brightness during opening: $values",
                        values.max() - values.min() <= 2)
                }
            } finally {
                card.bg.setImageDrawable(null)
                card.previewImage.setImageDrawable(null)
                source.recycle()
            }
        }
    }

    @Test
    fun overviewUsesPhotographsPerspectiveAndRaisedReadableText() = scene { s ->
        s.onMain {
            val text = holder(0)
            assertFalse(manager.hasSelection())
            assertEquals(0f, text.presentation.expansionProgress, 0f)
            assertTrue(text.previewImage.drawable != null)
            assertEquals(text.preview.width, text.previewImage.width)
            assertEquals(text.preview.height, text.previewImage.height)
            assertEquals(text.previewSurface.height, text.preview.height)
            assertEquals(ImageView.ScaleType.CENTER_CROP, text.previewImage.scaleType)
            assertTrue(text.preview.foreground is CardEdgeDrawable)
            assertEquals(View.GONE, text.previewScrim.visibility)
            assertEquals(0, Color.alpha(text.preview.cardBackgroundColor.defaultColor))
            assertNotNull(text.previewImage.colorFilter)
            assertNotNull(text.previewImage.background)
            assertNull(text.previewImage.foreground)
            assertSame(text.previewSurface, text.previewText.parent)
            assertSame(text.previewSurface, text.preview.parent)
            assertTrue(text.previewText.paddingStart + text.previewText.translationX < text.preview.left)
            assertTrue(text.previewText.translationZ > text.preview.translationZ)
            assertTrue(text.previewTitle is TimescapeTextView)
            assertTrue(text.previewSnippet is TimescapeTextView)
            assertEquals(0f, text.itemView.rotationX, 0f)
            assertEquals(0f, text.itemView.rotationY, 0f)
            for (index in listOf(0, 3, 5, 6)) assertEquals(1f, holder(index).itemView.alpha, 0f)
            val next = holder(1).itemView
            assertTrue(next.alpha < 1f)
            assertEquals(manager.geometry.maximumPitchDegrees * (1f - manager.geometry.pitchDepthVariation),
                next.rotationX, .001f)
            assertEquals(manager.geometry.maximumYawDegrees / 4f, next.rotationY, .001f)
            assertTrue(next.left + next.width / 2f < text.itemView.left + text.itemView.width / 2f)
            assertTrue(next.rotation < 0f)
            assertScreenFacingCardLettering(text.presentation)
            assertEquals(
                (text.previewText.height - text.previewText.paddingBottom).toFloat(),
                text.previewText.pivotY, 0.001f
            )
            assertNotNull(text.previewText.animationMatrix)
            assertTrue(text.previewTitle.shadowRadius > 0f)
            assertEquals(4, text.previewSnippet.maxLines)
            assertTrue(text.previewTime.bottom <= text.preview.height)
            assertFalse(text.collapseButton.isShown)
            assertEquals(View.INVISIBLE, text.presentation.getChildAt(0).visibility)
            assertTrue(holder(1).itemView.width * holder(1).itemView.scaleX < text.itemView.width * text.itemView.scaleX)
            assertTrue(holder(1).itemView.translationZ < text.itemView.translationZ)
        }
    }

    @Test
    fun cardOuterEdgesTwistProgressivelyOnBothSidesOfTheReadingCard() = scene { s ->
        fun sideRatio(index: Int): Float {
            val points = projectedCorners(s.holder(index).itemView)
            val left = kotlin.math.hypot(points[6] - points[0], points[7] - points[1])
            val right = kotlin.math.hypot(points[4] - points[2], points[5] - points[3])
            return right / left
        }
        var perspectiveRatios = emptyList<Float>()
        s.onMain {
            adapter.submitList((0..16).map {
                CardItem(100L + it, "Card $it", "A short thought.",
                    bg = BgImage.Res(R.drawable.bg_2))
            }) { manager.restoreState(8, true) }
        }
        s.await {
            s.manager.isFocused(8) && (3..14).all {
                s.recycler.findViewHolderForAdapterPosition(it) != null
            }
        }
        capturePerspective(s, "progressive-edges", 3..14)
        s.onMain {
            fun edgeAngle(index: Int): Float {
                val points = projectedCorners(holder(index).itemView)
                if (index < 8) return Math.toDegrees(kotlin.math.atan2(
                    (points[3] - points[1]).toDouble(),
                    (points[2] - points[0]).toDouble())).toFloat()
                return Math.toDegrees(kotlin.math.atan2(
                    (points[5] - points[7]).toDouble(),
                    (points[4] - points[6]).toDouble())).toFloat()
            }
            val upper = (1..5).map { edgeAngle(8 - it) }
            val lower = (1..6).map { -edgeAngle(8 + it) }
            Log.i("CardEdgePerspective", "upper top angles=$upper; lower bottom magnitudes=$lower")
            assertEquals("The open reading card must remain level", 0f, edgeAngle(8), .01f)
            for (angles in listOf(upper, lower)) {
                assertTrue("Upper/lower edges must tilt in opposite directions: $angles",
                    angles.all { it > 0f })
                assertTrue("Consecutive outer edges remained nearly parallel: $angles",
                    angles.zipWithNext().all { (near, far) -> far - near >= .8f })
                assertTrue("The card band has too little accumulated twist: $angles",
                    angles.last() - angles.first() >= 5f)
            }
            perspectiveRatios = listOf(3, 13).map(::sideRatio)
            assertScreenFacingCardLettering(holder(7).presentation)
            assertScreenFacingCardLettering(holder(9).presentation)
            manager.geometry = manager.geometry.copy(maximumYawDegrees = 0f)
        }
        s.await { listOf(3, 13).all { s.holder(it).itemView.rotationY == 0f } }
        s.onMain {
            for ((offset, index) in listOf(3, 13).withIndex()) {
                val withoutYaw = sideRatio(index)
                assertTrue("Yaw did not change card $index's trapezoid: " +
                    "${perspectiveRatios[offset]} vs $withoutYaw",
                    abs(perspectiveRatios[offset] - withoutYaw) >= .01f)
            }
        }
    }

    @Test
    fun differentCardHeightsKeepClearlySeparatedProjectedOuterEdges() = scene { s ->
        s.onMain {
            val portrait = checkNotNull(adapter.getItemAt(1)?.image)
            val landscape = checkNotNull(adapter.getItemAt(2)?.image)
            adapter.submitList((0..16).map { index ->
                when (index % 4) {
                    0 -> CardItem(200L + index, "Long card $index",
                        "Four lines of preview text should not flatten the edge angle. ".repeat(12),
                        bg = BgImage.Res(R.drawable.bg_2))
                    1 -> CardItem(200L + index, "Short card $index", "A short thought.",
                        bg = BgImage.Res(R.drawable.bg_2))
                    2 -> CardItem(200L + index, "Portrait $index", "", image = portrait)
                    else -> CardItem(200L + index, "Landscape $index", "", image = landscape)
                }
            }) { manager.restoreState(8, true) }
        }
        s.await {
            s.manager.isFocused(8) && (2..14).all {
                val holder = s.recycler.findViewHolderForAdapterPosition(it) as? CardsAdapter.VH
                holder != null && (it % 4 < 2 || holder.previewImage.drawable is BitmapDrawable)
            }
        }
        capturePerspective(s, "variable-height-edges", 2..14)
        s.onMain {
            val heights = (2..14).map { holder(it).itemView.height }
            assertTrue("The fixture must exercise different face heights", heights.max() >= heights.min() * 2)
            for (side in listOf(-1, 1)) {
                val angles = (1..6).map { distance ->
                    val index = 8 + side * distance
                    val points = projectedCorners(holder(index).itemView)
                    val left = if (side < 0) 0 else 6
                    val right = if (side < 0) 2 else 4
                    -side * Math.toDegrees(kotlin.math.atan2(
                        (points[right + 1] - points[left + 1]).toDouble(),
                        (points[right] - points[left]).toDouble())).toFloat()
                }
                Log.i("CardEdgePerspective", "variable-height side=$side angles=$angles; heights=$heights")
                assertTrue("Different-height cards still formed parallel groups: $angles",
                    angles.zipWithNext().all { (near, far) -> far - near >= .8f })
                for ((offset, actual) in angles.withIndex()) {
                    val expected = 12f * kotlin.math.tanh((offset + 1) / 8f)
                    assertEquals("Height/aspect changed the actual projected outer edge", expected, actual, .05f)
                }
            }
        }
    }

    @Test
    fun upperCardsMirrorLowerShapesAndMotionWithoutFlippingTheirContents() = scene { s ->
        s.onMain {
            val portrait = checkNotNull(adapter.getItemAt(1)?.image)
            val landscape = checkNotNull(adapter.getItemAt(2)?.image)
            adapter.submitList((0..16).map { index ->
                val distance = abs(index - 8)
                when (distance % 3) {
                    1 -> CardItem(300L + index, "Portrait $index", "", image = portrait)
                    2 -> CardItem(300L + index, "Landscape $index", "", image = landscape)
                    else -> CardItem(300L + index, "Card $index",
                        if (distance == 0) "A short thought."
                        else "A longer preview exercises different card heights. ".repeat(8),
                        bg = BgImage.Res(R.drawable.bg_2))
                }
            }) { manager.restoreState(8, true) }
        }
        fun assertMirror(upper: FloatArray, lower: FloatArray, axisY: Float) {
            for (corner in 0..3) {
                val mirrored = 3 - corner
                assertEquals("Mirrored motion changed horizontal position", lower[mirrored * 2],
                    upper[corner * 2], 1f)
                assertEquals("The upper quadrilateral did not mirror the lower one",
                    2f * axisY - lower[mirrored * 2 + 1], upper[corner * 2 + 1], 1f)
            }
        }
        for (focused in listOf(true, false)) {
            s.onMain { manager.restoreState(8, focused) }
            s.await {
                s.manager.isFocused(8) == focused && (2..14).all {
                    val holder = s.recycler.findViewHolderForAdapterPosition(it) as? CardsAdapter.VH
                    holder != null && (abs(it - 8) % 3 == 0 || holder.previewImage.drawable is BitmapDrawable)
                } && s.holder(8).presentation.expansionProgress == if (focused) 1f else 0f
            }
            capturePerspective(s, "mirrored-${if (focused) "reading" else "overview"}", 2..14)
            s.onMain {
                val focus = projectedCorners(holder(8).itemView)
                val axisY = (focus[1] + focus[5]) / 2f
                for (distance in 1..6) {
                    val upper = holder(8 - distance).itemView
                    val lower = holder(8 + distance).itemView
                    assertMirror(projectedCorners(upper), projectedCorners(lower), axisY)
                    assertEquals(lower.width, upper.width)
                    assertEquals(lower.height, upper.height)
                    assertEquals(lower.scaleX, upper.scaleX, .0001f)
                    assertEquals(lower.scaleY, upper.scaleY, .0001f)
                    assertEquals(-lower.rotationX, upper.rotationX, .001f)
                    assertEquals(lower.rotationY, upper.rotationY, .001f)
                    assertEquals(lower.alpha, upper.alpha, .001f)
                    assertEquals(lower.translationZ, upper.translationZ, .001f)
                }
                assertScreenFacingCardLettering(holder(5).presentation)
                assertScreenFacingCardLettering(holder(11).presentation)
            }
        }
        s.onMain { manager.restoreState(8, true) }
        s.await { s.manager.isFocused(8) && s.holder(8).presentation.expansionProgress == 1f }
        var axisY = 0f
        s.onMain {
            val focus = projectedCorners(holder(8).itemView)
            axisY = (focus[1] + focus[5]) / 2f
        }
        fun sample(delta: Int, indices: IntRange): List<FloatArray> {
            s.onMain { manager.restoreState(8, true) }
            s.await { s.manager.isFocused(8) && s.holder(8).presentation.expansionProgress == 1f }
            s.onMain { recycler.scrollBy(0, delta) }
            awaitPresented(s)
            var points = emptyList<FloatArray>()
            s.onMain { points = indices.map { projectedCorners(holder(it).itemView) } }
            return points
        }
        for (step in 1..3) {
            val delta = s.dp(138) * step / 4
            val lower = sample(delta, 9..12)
            val upper = sample(-delta, 4..7).reversed()
            for (index in lower.indices) assertMirror(upper[index], lower[index], axisY)
        }
    }

    @Test
    fun upperCardsMirrorLowerPerspectiveWhileLetteringStaysUpright() = scene { s ->
        s.onMain { manager.restoreState(5, false) }
        s.await { (3..7).all { s.recycler.findViewHolderForAdapterPosition(it) != null } }
        s.onMain {
            for (index in listOf(3, 4, 6, 7)) {
                val card = holder(index)
                assertTrue("Card $index has the wrong mirrored pitch",
                    card.itemView.rotationX * (index - 5) > 0f)
                val corners = projectedCorners(card.itemView)
                val top = kotlin.math.hypot(corners[2] - corners[0], corners[3] - corners[1])
                val bottom = kotlin.math.hypot(corners[4] - corners[6], corners[5] - corners[7])
                assertTrue("Card $index did not mirror its opposite edge lengths",
                    if (index < 5) top > bottom else top < bottom)
                if (!card.presentation.isMediaPreview) {
                    assertScreenFacingCardLettering(card.presentation)
                    assertEquals((card.previewText.height - card.previewText.paddingBottom).toFloat(),
                        card.previewText.pivotY, .001f)
                    assertEquals(manager.geometry.cameraDistance(card.previewText.width, card.previewText.height,
                        root.resources.displayMetrics.densityDpi, .5f, 1f), card.previewText.cameraDistance, .001f)
                }
            }
            assertTrue(kotlin.math.abs(holder(7).itemView.rotationX) > kotlin.math.abs(holder(6).itemView.rotationX))
        }
        capturePerspective(s, "standing-overview", 3..7)
        s.onMain {
            manager.geometry = manager.geometry.copy(letteringFacingPitchDegrees = 6f, letteringLiftDp = 12f)
        }
        s.await {
            val corners = projectedCardLetteringCorners(s.holder(6).presentation)
            kotlin.math.abs((corners[2] - corners[0]) - (corners[4] - corners[6])) > .5f &&
                s.holder(6).previewText.translationZ == 12f * s.root.resources.displayMetrics.density
        }
        s.onMain {
            assertEquals(12f * root.resources.displayMetrics.density, holder(6).previewText.translationZ, .001f)
            assertEquals(5, manager.nearestIndex())
        }
    }

    @Test
    fun standingLetteringFacesTheScreenAtEveryScrollPositionWithoutRewrapping() = scene { s ->
        s.onMain { manager.restoreState(5, false) }
        s.await { (3..7).all { s.recycler.findViewHolderForAdapterPosition(it) != null } }
        lateinit var original: FloatArray
        var layout: android.text.Layout? = null
        s.onMain {
            original = FloatArray(9).also { checkNotNull(holder(6).previewText.animationMatrix).getValues(it) }
            layout = holder(6).previewSnippet.layout
            val card = holder(6)
            assertTrue(card.previewTitle.text.isEmpty())
            assertNaturalCardLetteringScale(card.presentation)
        }
        for (step in 0..12) {
            s.onMain {
                if (step > 0) recycler.scrollBy(0, dp(138) / 12)
                for (index in 0 until recycler.childCount) {
                    val card = recycler.getChildAt(index) as TimescapeCardLayout
                    if (!card.isMediaPreview) {
                        assertScreenFacingCardLettering(card)
                        assertNaturalCardLetteringScale(card)
                    }
                }
                assertSame("Facing the user must not rewrap the summary", layout, holder(6).previewSnippet.layout)
            }
        }
        s.onMain {
            val updated = FloatArray(9).also { checkNotNull(holder(6).previewText.animationMatrix).getValues(it) }
            assertFalse("The local lettering correction remained fixed during scrolling", original.contentEquals(updated))
            assertFalse(manager.hasSelection())
        }
        capturePerspective(s, "screen-facing-scrolled", 5..7)
    }

    @Test
    fun uprightLetteringKeepsNaturalScaleAtExtremePerspectiveAndLargeFonts() = scene { s ->
        s.onMain {
            adapter.setBodyTextSize(26f)
            manager.geometry = manager.geometry.copy(maximumPitchDegrees = 60f,
                maximumYawDegrees = -30f, maximumRollDegrees = 12f,
                cameraDistanceInCardWidths = .6f, distantScaleY = .8f,
                letteringLiftDp = 24f)
            manager.restoreState(5, false)
        }
        s.await { (3..7).all { s.recycler.findViewHolderForAdapterPosition(it) != null } }
        s.onMain {
            for (index in 0 until recycler.childCount) {
                val card = recycler.getChildAt(index) as TimescapeCardLayout
                if (!card.isMediaPreview) {
                    assertScreenFacingCardLettering(card)
                    assertNaturalCardLetteringScale(card)
                }
            }
        }
        capturePerspective(s, "screen-facing-extreme", 5..7)
    }

    @Test
    fun raisedLetteringPaintsOutsideTheCardIncludingDuringWindFades() = scene { s ->
        s.onMain {
            recycler.clipChildren = false
            adapter.setBodyTextSize(26f)
            manager.geometry = manager.geometry.copy(maximumPitchDegrees = 60f,
                maximumYawDegrees = -30f, cameraDistanceInCardWidths = .6f,
                distantScaleY = .8f, letteringLiftDp = 24f)
            adapter.submitList((0 until 17).map {
                CardItem(100L + it, "Raised title $it",
                    "Long raised lettering must remain readable beyond the card edges. ".repeat(20),
                    bg = BgImage.Res(R.drawable.bg_2))
            }) { manager.restoreState(8, false) }
        }
        s.await {
            (7..10).all { s.recycler.findViewHolderForAdapterPosition(it) != null } &&
                s.holder(9).previewSnippet.layout?.lineCount == 4 &&
                s.holder(9).previewImage.drawable != null && !s.recycler.isLayoutRequested
        }
        lateinit var face: FloatArray
        lateinit var ink: FloatArray
        s.onMain {
            for (index in 0 until recycler.childCount) {
                val card = recycler.getChildAt(index) as TimescapeCardLayout
                assertScreenFacingCardLettering(card)
                assertNaturalCardLetteringScale(card)
            }
            val card = holder(9).presentation
            assertNull(card.clipBounds)
            card.setRenderCaching(true)
            face = floatArrayOf(0f, 0f, card.width.toFloat(), 0f,
                card.width.toFloat(), card.height.toFloat(), 0f, card.height.toFloat())
            card.mapCornerMorph(face)
            val global = Matrix()
            card.renderedMatrixToAncestor(null, global)
            global.mapPoints(face)
            ink = projectedCardLetteringCorners(card, ink = true)
            for (index in 0 until recycler.childCount) {
                val child = recycler.getChildAt(index)
                if (child !== card) child.alpha = 0f
            }
        }
        capturePerspective(s, "screen-facing-overflow", 7..10)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (opacity in listOf(1f, .45f)) {
            s.onMain {
                holder(9).presentation.setWindAlpha(opacity)
                holder(9).previewText.alpha = 0f
            }
            awaitPresented(s)
            val background = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                s.onMain { holder(9).previewText.alpha = 1f }
                awaitPresented(s)
                val lettering = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    var paintedOutside = 0
                    for (y in ink[1].toInt().coerceAtLeast(0) until
                        ink[5].toInt().coerceAtMost(lettering.height)) {
                        for (x in ink[0].toInt().coerceAtLeast(0) until
                            ink[2].toInt().coerceAtMost(lettering.width)) {
                            val outside = (0..3).any { edge ->
                                val next = (edge + 1) % 4
                                val dx = face[next * 2] - face[edge * 2]
                                val dy = face[next * 2 + 1] - face[edge * 2 + 1]
                                dx * (y - face[edge * 2 + 1]) - dy * (x - face[edge * 2]) <
                                    -2f * kotlin.math.hypot(dx, dy)
                            }
                            if (!outside) continue
                            val before = background.getPixel(x, y)
                            val after = lettering.getPixel(x, y)
                            if (abs(Color.red(before) - Color.red(after)) +
                                abs(Color.green(before) - Color.green(after)) +
                                abs(Color.blue(before) - Color.blue(after)) > 30) {
                                paintedOutside++
                            }
                        }
                    }
                    assertTrue("Card-edge clipping hid raised ink at wind opacity $opacity ($paintedOutside pixels)",
                        paintedOutside > 50)
                } finally {
                    lettering.recycle()
                }
            } finally {
                background.recycle()
            }
        }
        capturePerspective(s, "screen-facing-overflow", 7..10)
    }

    @Test
    fun previewBodyShowsUpToFourLinesWithoutChangingItsFontOrFullText() = scene { s ->
        lateinit var card: CardsAdapter.VH
        var size = 0f
        var fullText = ""
        var twoLineHeight = 0
        s.onMain {
            card = holder(0)
            size = card.previewSnippet.textSize
            fullText = card.previewSnippet.text.toString()
            card.previewSnippet.maxLines = 2
        }
        s.await { card.previewSnippet.layout?.lineCount == 2 }
        s.onMain {
            twoLineHeight = card.previewSnippet.height
            card.previewSnippet.maxLines = 4
        }
        s.await { card.previewSnippet.layout?.lineCount == 4 }
        s.onMain {
            assertEquals(4, card.previewSnippet.maxLines)
            assertEquals(size, card.previewSnippet.textSize, 0f)
            assertEquals(fullText, card.previewSnippet.text.toString())
            assertTrue(card.previewSnippet.height > twoLineHeight * 1.8f)
            assertTrue(card.previewSnippet.height <= twoLineHeight * 2f + 1f)
            assertTrue(card.previewText.bottom <= card.previewSurface.height)
        }
    }

    @Test
    fun overviewStackSlightlyOverlapsDifferentCardHeightsAndBendsOnlyOnce() = scene { s ->
        s.await { s.recycler.findViewHolderForAdapterPosition(6) != null }
        s.onMain {
            fun center(index: Int) = holder(index).itemView.let {
                floatArrayOf(it.left + it.width / 2f, it.top + it.height / 2f)
            }
            val centers = (0..6).map(::center)
            val gaps = centers.zipWithNext { a, b -> b[1] - a[1] }
            val faces = (0..6).map { boundsInAncestor(holder(it).preview, recycler) }
            faces.zipWithNext().forEachIndexed { index, (a, b) ->
                val overlap = (a.bottom - b.top) / minOf(a.height(), b.height())
                assertTrue("Cards $index/${index + 1} overlap by $overlap ($a / $b)",
                    overlap in .015f..0.28f)
            }
            assertTrue(centers[1][0] < centers[0][0])
            assertTrue(centers[2][0] < centers[1][0])
            assertTrue((2 until centers.lastIndex).all { centers[it + 1][0] > centers[it][0] })
            assertTrue(centers.last()[0] > centers.first()[0])
            assertTrue(gaps.all { it > 0f })
            assertFalse(manager.hasSelection())
        }
    }

    @Test
    fun detachedAutoMeasureRetainsResourceScaledCompactSpacing() = scene { s ->
        s.onMain {
            val detached = RecyclerView(root.context)
            val detachedManager = RightRailFlowLayoutManager(dp(340), dp(360), dp(138), dp(8))
            detached.layoutManager = detachedManager
            detached.adapter = adapter
            try {
                detachedManager.restoreState(0, false)
                detached.measure(
                    View.MeasureSpec.makeMeasureSpec(dp(340), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(800), View.MeasureSpec.EXACTLY)
                )
                detached.layout(0, 0, detached.measuredWidth, detached.measuredHeight)
                assertFalse(detached.isAttachedToWindow)
                val first = boundsInAncestor(checkNotNull(detachedManager.findViewByPosition(0)), detached)
                val second = boundsInAncestor(checkNotNull(detachedManager.findViewByPosition(1)), detached)
                assertTrue("Detached auto-measure lost its density-scaled base pitch",
                    second.centerY() - first.centerY() > dp(80))
                assertEquals(0, detachedManager.nearestIndex())
            } finally {
                detached.adapter = null
            }
        }
    }

    @Test
    fun heightAwareCompactSpacingKeepsItsFocusAnchorWhenTheAttachedWindowChanges() = scene { s ->
        val windows = mutableSetOf<IntRange>()
        for (viewport in listOf(340, 1200, 340)) {
            s.onMain {
                recycler.layoutParams.height = dp(viewport)
                recycler.requestLayout()
                manager.restoreState(5, false)
            }
            s.await {
                s.recycler.height == s.dp(viewport) &&
                    s.recycler.findViewHolderForAdapterPosition(5) != null && !s.recycler.isLayoutRequested
            }
            s.onMain {
                val positions = (0 until recycler.childCount).map {
                    recycler.getChildAdapterPosition(recycler.getChildAt(it))
                }
                windows += positions.min()..positions.max()
                val focus = boundsInAncestor(holder(5).itemView, recycler)
                assertEquals("Compact focus moved with the attached window",
                    recycler.height * .25f, focus.centerY(), .01f)
                assertEquals(5, manager.nearestIndex())
                assertFalse(manager.hasSelection())
            }
        }
        assertTrue("The regression never exercised a changed attached window", windows.size > 1)
    }

    @Test
    fun compressedStackStillCoversTallViewportsWithoutAttachingTheWholeFlow() = scene { s ->
        s.onMain {
            recycler.layoutParams.height = dp(1200)
            recycler.requestLayout()
            adapter.submitList((0 until 32).map {
                CardItem(100L + it, "Card $it", "A compact card in the receding stack.")
            }) { manager.restoreState(0, false) }
        }
        s.await { s.adapter.itemCount == 32 && s.recycler.height == s.dp(1200) }
        s.onMain {
            val cards = (0 until recycler.childCount).map { recycler.getChildAt(it) }
            val last = cards.maxBy { recycler.getChildAdapterPosition(it) }
            val lastBounds = boundsInAncestor(last, recycler)
            assertTrue("Compressed window stops before the viewport ends",
                lastBounds.centerY() >= recycler.height + dp(138) / 2f)
            assertTrue("Attaching the entire flow defeats bounded rendering", cards.size < 24)
            assertTrue(cards.map { boundsInAncestor(it, recycler).centerY() }
                .sorted().zipWithNext().all { (a, b) -> b > a })
            assertFalse(manager.hasSelection())
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
                    assertEquals(4, card.previewSnippet.maxLines)
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
    fun overviewAndReadingUseBoundedPerspectiveAndNeutralFocus() = scene { s ->
        fun assertPerspective() = s.onMain {
            for (i in 0 until recycler.childCount) {
                val card = recycler.getChildAt(i)
                assertTrue(kotlin.math.abs(card.rotationX) <= manager.geometry.maximumPitchDegrees)
                assertTrue(kotlin.math.abs(card.rotationY) <= kotlin.math.abs(manager.geometry.maximumYawDegrees))
                assertTrue(kotlin.math.abs(card.rotation) <= manager.geometry.maximumRollDegrees)
                val screenDistance = card.cameraDistance * 72f / card.resources.displayMetrics.densityDpi
                assertTrue(screenDistance >= maxOf(card.width, card.height) * 1.1f - .01f)
                val corners = projectedCorners(card)
                assertTrue(corners.all { it.isFinite() })
                for (corner in 0..3) {
                    val a = corner * 2
                    val b = ((corner + 1) % 4) * 2
                    val c = ((corner + 2) % 4) * 2
                    val cross = (corners[b] - corners[a]) * (corners[c + 1] - corners[b + 1]) -
                        (corners[b + 1] - corners[a + 1]) * (corners[c] - corners[b])
                    assertTrue("Perspective inverted or folded card ${recycler.getChildAdapterPosition(card)} " +
                        "(${card.width}x${card.height}, camera=$screenDistance): ${corners.toList()}", cross > 0f)
                }
            }
        }
        for (index in listOf(0, 3, 7)) {
            s.onMain { manager.restoreState(index, false) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertPerspective()
        }
        s.onMain { manager.restoreState(3, true) }
        s.await { s.holder(3).presentation.expansionProgress == 1f }
        assertPerspective()
        repeat(3) {
            s.onMain { recycler.scrollBy(0, dp(138) / 4) }
            assertPerspective()
        }
    }

    @Test
    fun perspectiveChangesBothPairsOfOppositeEdgesAndRecoversARectangleAtFocus() = scene { s ->
        s.onMain { manager.restoreState(5, false) }
        s.await { s.recycler.findViewHolderForAdapterPosition(6) != null }
        s.onMain {
            fun edge(corners: FloatArray, from: Int, to: Int): Float =
                kotlin.math.hypot(corners[to * 2] - corners[from * 2],
                    corners[to * 2 + 1] - corners[from * 2 + 1])
            val behind = projectedCorners(holder(6).itemView)
            val top = edge(behind, 0, 1)
            val bottom = edge(behind, 3, 2)
            val left = edge(behind, 0, 3)
            val right = edge(behind, 1, 2)
            assertTrue("Upper/lower edges still only undergo affine scaling: $top / $bottom",
                kotlin.math.abs(top / bottom - 1f) > .02f)
            assertTrue("Left/right edges lack yaw perspective: $left / $right",
                kotlin.math.abs(left / right - 1f) > .003f)
        }
        for (reading in listOf(false, true)) {
            s.onMain { manager.restoreState(6, reading) }
            s.await {
                s.manager.offsetTo(6) == 0 && s.holder(6).itemView.rotationX == 0f &&
                    s.holder(6).presentation.expansionProgress == if (reading) 1f else 0f
            }
            s.onMain {
                val card = holder(6).itemView
                val corners = projectedCorners(card)
                assertEquals(corners[1], corners[3], .001f)
                assertEquals(corners[5], corners[7], .001f)
                assertEquals(corners[0], corners[6], .001f)
                assertEquals(corners[2], corners[4], .001f)
                assertEquals(0f, card.rotationX, 0f)
                assertEquals(0f, card.rotationY, 0f)
                assertEquals(0f, card.rotation, 0f)
            }
        }
    }

    @Test
    fun allFourCornersRetraceDuringAReversedReadingDragAcrossSelectionMidpoint() = scene { s ->
        s.onMain { manager.restoreState(0, true) }
        s.await { s.holder(0).presentation.expansionProgress == 1f }
        val samples = mutableListOf<List<FloatArray>>()
        val deltas = (1..6).map { s.dp(138) * it / 8 - s.dp(138) * (it - 1) / 8 }
        s.onMain { samples += (0..1).map { projectedCorners(holder(it).itemView) } }
        capturePerspective(s, "reading-0")
        for ((step, delta) in deltas.withIndex()) {
            s.onMain {
                recycler.scrollBy(0, delta)
                samples += (0..1).map { projectedCorners(holder(it).itemView) }
            }
            capturePerspective(s, "reading-${step + 1}")
        }
        s.onMain {
            fun corners() = (0..1).map { projectedCorners(holder(it).itemView) }
            assertEquals(1, manager.currentSelectionIndex())
            assertTrue(holder(0).itemView.rotationX < 0f)
            assertTrue(holder(1).itemView.rotationX > 0f)
            for (step in deltas.indices.reversed()) {
                recycler.scrollBy(0, -deltas[step])
                val reversed = corners()
                for (card in 0..1) for (coordinate in 0..7) {
                    assertEquals("Corner $coordinate on card $card did not retrace at step $step",
                        samples[step][card][coordinate], reversed[card][coordinate], 1f)
                }
            }
            assertEquals(0, manager.currentSelectionIndex())
        }
    }

    @Test
    fun perspectiveHitTestingTracksTheProjectedQuadrilateral() = scene { s ->
        s.onMain { manager.restoreState(5, false) }
        s.await { s.recycler.findViewHolderForAdapterPosition(6) != null }
        s.onMain {
            val card = holder(6).itemView
            val point = floatArrayOf(card.width / 2f, card.height / 2f)
            card.matrix.mapPoints(point)
            assertTrue(manager.hasCardUnder(point[0] + card.left, point[1] + card.top))
            assertFalse(manager.hasCardUnder(-dp(100).toFloat(), -dp(100).toFloat()))
        }
    }

    @Test
    fun geometryCanBeRetunedWithoutResettingScrollSelectionOrFaceLayout() = scene { s ->
        s.onMain { manager.restoreState(5, true) }
        s.await { s.holder(5).presentation.expansionProgress == 1f }
        var width = 0
        var height = 0
        s.onMain {
            width = holder(6).itemView.width
            height = holder(6).itemView.height
            manager.geometry = manager.geometry.copy(maximumPitchDegrees = 42f,
                cameraDistanceInCardWidths = 2f, distantPivotY = .9f, pitchDepthVariation = 0f)
        }
        s.await { kotlin.math.abs(s.holder(6).itemView.rotationX - 42f) < .001f }
        s.onMain {
            val next = holder(6).itemView
            assertEquals(5, manager.currentSelectionIndex())
            assertEquals(0, manager.offsetTo(5))
            assertEquals(width, next.width)
            assertEquals(height, next.height)
            assertEquals(next.height * .9f, next.pivotY, .001f)
            assertEquals(1f, holder(5).presentation.expansionProgress, 0f)
            assertEquals(0f, holder(5).itemView.rotationX, 0f)
        }
    }

    @Test
    fun cameraTuningChangesPerspectiveWithoutChangingCardSizeOrTilt() = scene { s ->
        s.onMain { manager.restoreState(5, false) }
        val expectedPitch = s.manager.geometry.maximumPitchDegrees * (1f - s.manager.geometry.pitchDepthVariation)
        s.await {
            s.recycler.findViewHolderForAdapterPosition(6) != null &&
                kotlin.math.abs(s.holder(6).itemView.rotationX - expectedPitch) < .001f
        }
        fun skew(card: View): Float {
            val corners = projectedCorners(card)
            val top = kotlin.math.hypot(corners[2] - corners[0], corners[3] - corners[1])
            val bottom = kotlin.math.hypot(corners[4] - corners[6], corners[5] - corners[7])
            return kotlin.math.abs(top / bottom - 1f)
        }
        var strong = 0f
        var width = 0
        var scale = 0f
        var yaw = 0f
        s.onMain {
            val card = holder(6).itemView
            strong = skew(card)
            width = card.width
            scale = card.scaleX
            yaw = card.rotationY
            manager.geometry = manager.geometry.copy(cameraDistanceInCardWidths = 3f)
        }
        s.await {
            s.holder(6).itemView.let {
                kotlin.math.abs(it.cameraDistance - it.width * 3f * it.resources.displayMetrics.densityDpi / 72f) < .1f
            }
        }
        s.onMain {
            val card = holder(6).itemView
            assertTrue("Increasing camera distance did not weaken the actual projection", skew(card) < strong * .65f)
            assertEquals(width, card.width)
            assertEquals(scale, card.scaleX, 0f)
            assertEquals(expectedPitch, card.rotationX, .001f)
            assertEquals(yaw, card.rotationY, 0f)
        }
    }

    @Test
    fun extremeLegalAnglesAndAnchorsKeepProjectedCornersConvex() = scene { s ->
        s.onMain {
            manager.geometry = manager.geometry.copy(maximumPitchDegrees = 60f, maximumYawDegrees = 30f,
                maximumRollDegrees = 12f, cameraDistanceInCardWidths = .6f,
                focusPivotX = 0f, focusPivotY = 0f, distantPivotX = 0f, distantPivotY = 0f,
                pitchDepthVariation = 0f)
            manager.restoreState(3, false)
        }
        s.await { s.recycler.findViewHolderForAdapterPosition(4) != null && s.holder(4).itemView.rotationX == 60f }
        s.onMain {
            for (index in 1..4) {
                val corners = projectedCorners(holder(index).itemView)
                assertTrue(corners.all { it.isFinite() })
                for (corner in 0..3) {
                    val a = corner * 2
                    val b = ((corner + 1) % 4) * 2
                    val c = ((corner + 2) % 4) * 2
                    val cross = (corners[b] - corners[a]) * (corners[c + 1] - corners[b + 1]) -
                        (corners[b + 1] - corners[a + 1]) * (corners[c] - corners[b])
                    assertTrue("Legal tuning folded card $index", cross > 0f)
                }
            }
        }
    }

    @Test
    fun incomingLowerEdgeExpandsWithoutRecedingBeforeFocus() = scene { s ->
        for (reading in listOf(false, true)) for (index in listOf(3, 6)) {
            s.onMain { manager.restoreState(index - 1, reading) }
            s.await {
                s.recycler.findViewHolderForAdapterPosition(index) != null &&
                    s.holder(index - 1).presentation.expansionProgress == if (reading) 1f else 0f
            }
            val widths = mutableListOf<Float>()
            for (step in 0..32) {
                s.onMain {
                    if (step > 0) recycler.scrollBy(0, dp(138) * step / 32 - dp(138) * (step - 1) / 32)
                    val corners = projectedCorners(holder(index).itemView)
                    widths += kotlin.math.hypot(corners[4] - corners[6], corners[5] - corners[7])
                }
                if (index == 3 && step % 16 == 0) {
                    capturePerspective(s, "incoming-$reading-$index-$step", index..index)
                }
            }
            var widest = widths.first()
            for (step in 1 until widths.size) {
                assertTrue("Incoming lower edge receded before growing ($reading/$index/$step): $widths",
                    widths[step] >= widest - 1f)
                widest = maxOf(widest, widths[step])
            }
            s.onMain {
                assertEquals(index, manager.nearestIndex())
                assertEquals(if (reading) 1f else 0f, holder(index).presentation.expansionProgress, 0f)
            }
        }
    }

    @Test
    fun staggeredCornersExpandWithDifferentVelocitiesInOverviewAndReading() = scene { s ->
        for (reading in listOf(false, true)) {
            s.onMain { manager.restoreState(5, reading) }
            s.await { s.holder(5).presentation.expansionProgress == if (reading) 1f else 0f }
            var before = FloatArray(8)
            var after = FloatArray(8)
            s.onMain {
                recycler.scrollBy(0, dp(138) * 2 / 5)
                before = projectedCorners(holder(6).itemView)
                recycler.scrollBy(0, dp(138) / 10)
                after = projectedCorners(holder(6).itemView)
                val localCorners = floatArrayOf(0f, 0f, holder(6).itemView.width.toFloat(), 0f,
                    holder(6).itemView.width.toFloat(), holder(6).itemView.height.toFloat(),
                    0f, holder(6).itemView.height.toFloat())
                holder(6).presentation.mapCornerMorph(localCorners)
                assertTrue("Upper edge remains aligned", kotlin.math.abs(localCorners[3] - localCorners[1]) > .1f)
                assertTrue("Lower edge remains aligned", kotlin.math.abs(localCorners[7] - localCorners[5]) > .1f)
            }
            val distances = (0..3).map { corner ->
                kotlin.math.hypot(after[corner * 2] - before[corner * 2],
                    after[corner * 2 + 1] - before[corner * 2 + 1])
            }
            for (first in 0..2) for (second in first + 1..3) {
                assertTrue("Corners $first and $second still move at the same rate: $distances",
                    kotlin.math.abs(distances[first] - distances[second]) > .25f)
            }
        }
    }

    @Test
    fun cornerMorphRejectsEmptyCornersAndTransformsTapsWithoutMutatingTheEvent() = scene { s ->
        s.onMain {
            adapter.submitList(listOf(adapter.currentList.first())) { manager.restoreState(0, false) }
        }
        s.await { s.adapter.itemCount == 1 && s.recycler.childCount == 1 }
        s.onMain {
            val card = holder(0)
            val pose = CardFlowPose()
            manager.geometry.sample(-.5f, 0f, pose)
            card.presentation.setCornerMorph(pose.corners)
            val empty = floatArrayOf(0f, 0f)
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, empty[0], empty[1], 0)
            val originalX = event.x
            val originalY = event.y
            try {
                assertFalse(card.presentation.containsCardPoint(empty[0], empty[1]))
                assertFalse(card.presentation.dispatchTouchEvent(event))
                assertEquals(originalX, event.x, 0f)
                assertEquals(originalY, event.y, 0f)
            } finally {
                event.recycle()
            }
            card.itemView.matrix.mapPoints(empty)
            assertFalse(manager.hasCardUnder(empty[0] + card.itemView.left, empty[1] + card.itemView.top))
            val point = floatArrayOf(
                card.previewSurface.left + card.preview.left + card.preview.width / 2f,
                card.previewSurface.top + card.preview.top + card.preview.height / 2f
            )
            card.presentation.mapCornerMorph(point)
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val tap = MotionEvent.obtain(now, now + 50, action, point[0], point[1], 0)
                val tapX = tap.x
                val tapY = tap.y
                try {
                    assertTrue(card.presentation.dispatchTouchEvent(tap))
                    assertEquals(tapX, tap.x, 0f)
                    assertEquals(tapY, tap.y, 0f)
                } finally {
                    tap.recycle()
                }
            }
        }
        s.await { s.manager.isFocused(0) }
    }

    @Test
    fun cornerMorphWarpsTheDrawnSurfaceAndResetsAtTheFocus() = scene { s ->
        s.onMain {
            val card = holder(0)
            card.previewSurface.setBackgroundColor(Color.MAGENTA)
            card.preview.visibility = View.INVISIBLE
            card.previewText.visibility = View.INVISIBLE
            val pose = CardFlowPose()
            val geometry = manager.geometry.copy(depthShrink = .5f, departingShrink = .25f,
                cornerWidthLead = .65f, cornerHeightLead = .4f)
            geometry.sample(-.5f, 0f, pose)
            card.presentation.setCornerMorph(pose.corners)
            val frame = Bitmap.createBitmap(card.itemView.width, card.itemView.height, Bitmap.Config.ARGB_8888)
            try {
                card.presentation.draw(Canvas(frame))
                assertEquals("Drawing still fills the unwarped rectangle", 0,
                    Color.alpha(frame.getPixel(0, 0)))
                assertEquals(Color.MAGENTA, frame.getPixel(frame.width / 2, frame.height / 2))
                geometry.sample(.5f, 0f, pose)
                card.presentation.setCornerMorph(pose.corners)
                card.previewSurface.layout(0, 0, frame.width, frame.height * 2)
                frame.eraseColor(Color.TRANSPARENT)
                card.presentation.draw(Canvas(frame))
                assertEquals("Overflowing content escaped the morph's slanted lower edge", 0,
                    Color.alpha(frame.getPixel(frame.width / 2, frame.height - 1)))
                geometry.sample(0f, 1f, pose)
                card.presentation.setCornerMorph(pose.corners)
                frame.eraseColor(Color.TRANSPARENT)
                card.presentation.draw(Canvas(frame))
                assertEquals(Color.MAGENTA,
                    frame.getPixel((frame.width * .02f).toInt(), (frame.height * .02f).toInt()))
            } finally {
                frame.recycle()
            }
        }
    }

    @Test
    fun shortTextFitsItsContentsOnFirstOpeningWithoutTurningTheCard() = scene { s ->
        s.longText(7)
        s.await {
            s.holder(7).presentation.expansionProgress == 1f &&
                s.holder(7).textScroll.canScrollVertically(1) &&
                !s.holder(7).presentation.isLayoutRequested
        }
        s.onMain { manager.restoreState(7, false) }
        s.await { s.holder(7).presentation.expansionProgress == 0f }
        s.onMain {
            val item = checkNotNull(adapter.getItemAt(7))
            item.snippet = "First line.\nSecond line."
            item.backSnippet = "The reverse face."
            adapter.notifyItemChanged(7)
        }
        s.await {
            s.holder(7).snippet.text.toString() == "First line.\nSecond line." &&
                !s.holder(7).presentation.isLayoutRequested
        }
        for (index in listOf(7, 0, 7, 1, 7, 8, 7)) {
            s.onMain { manager.restoreState(index, false) }
            s.await {
                s.recycler.findViewHolderForAdapterPosition(index) != null &&
                    s.holder(index).presentation.expansionProgress == 0f
            }
            s.onMain { manager.focus(index) }
            s.await {
                s.recycler.findViewHolderForAdapterPosition(index) != null &&
                    s.holder(index).presentation.expansionProgress == 1f &&
                    !s.holder(index).presentation.isLayoutRequested
            }
            if (index != 7) continue
            s.onMain {
                val card = holder(index)
                val text = boundsInAncestor(card.snippet, card.card)
                val textBottom = text.top + card.snippet.totalPaddingTop + checkNotNull(card.snippet.layout).height
                assertTrue("First opening reserved empty space: card=${card.card.height}, textBottom=$textBottom",
                    card.card.height <= textBottom + card.cardContent.paddingBottom + 1)
            }
        }
        var firstHeight = 0
        s.onMain { firstHeight = holder(7).card.height }
        capturePerspective(s, "first-open-short-text", 7..7)
        for (face in listOf(HandwritingFace.BACK, HandwritingFace.FRONT)) {
            s.onMain { assertTrue(holder(7).flip.flip()) }
            s.await { s.adapter.currentFaceFor(7) == face && !s.holder(7).flip.isFlipping }
        }
        s.onMain { assertEquals("Turning twice changed the original text height", firstHeight, holder(7).card.height) }
    }

    @Test
    fun firstOpeningAndRebindingImagesPreserveTheActualPicturePixels() = scene { s ->
        for (index in listOf(1, 2, 8, 1, 2)) {
            lateinit var source: CardImage
            s.onMain {
                val item = checkNotNull(adapter.getItemAt(index))
                source = checkNotNull(item.image)
                item.image = null
                item.snippet = "A previously expanded long article. ".repeat(120)
                adapter.notifyItemChanged(index)
                manager.restoreState(index, true)
            }
            s.await { s.holder(index).textScroll.canScrollVertically(1) }
            s.onMain {
                checkNotNull(adapter.getItemAt(index)).image = source
                adapter.notifyItemChanged(index)
            }
            s.onMain { manager.restoreState(index, false) }
            s.await {
                s.recycler.findViewHolderForAdapterPosition(index) != null &&
                    s.holder(index).presentation.expansionProgress == 0f
            }
            s.onMain { manager.focus(index) }
            s.await {
                val card = s.recycler.findViewHolderForAdapterPosition(index) as? CardsAdapter.VH
                card != null && card.presentation.expansionProgress == 1f &&
                    card.imageCard.drawable != null && !card.presentation.isLayoutRequested
            }
            s.onMain {
                val card = holder(index)
                val image = boundsInAncestor(card.imageCard, card.card)
                assertTrue("The expanded image has no visible area: $image", image.width() > 0 && image.height() > 0)
                val frame = Bitmap.createBitmap(card.card.width, card.card.height, Bitmap.Config.ARGB_8888)
                try {
                    card.card.draw(Canvas(frame))
                    val x = frame.width * 3 / 4
                    val y = frame.height / 2
                    val pixel = frame.getPixel(x, y)
                    assertEquals("Expanded card lost the blue picture pixels at $index",
                        Color.rgb(32, 113, 185), pixel)
                } finally {
                    frame.recycle()
                }
            }
            awaitPresented(s)
            val native = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                s.onMain {
                    val image = boundsInAncestor(holder(index).card, root)
                    val location = IntArray(2)
                    root.getLocationOnScreen(location)
                    val x = (location[0] + image.left + image.width() * .75f).toInt()
                    val y = (location[1] + image.centerY()).toInt()
                    val pixel = native.getPixel(x, y)
                    assertTrue("Native expanded card lost its blue image pixels at $index: $pixel",
                        Color.blue(pixel) - Color.red(pixel) >= 100 &&
                            Color.blue(pixel) - Color.green(pixel) >= 40)
                    val orange = native.getPixel(
                        (location[0] + image.left + image.width() * .25f).toInt(),
                        (location[1] + image.top + image.height() * .25f).toInt()
                    )
                    assertTrue("Native expanded card lost its orange picture region at $index: $orange",
                        Color.red(orange) - Color.green(orange) >= 50 &&
                            Color.green(orange) - Color.blue(orange) >= 50)
                }
            } finally {
                native.recycle()
            }
            capturePerspective(s, "first-open-image-$index", index..index)
            s.onMain { adapter.notifyItemChanged(index) }
            s.await {
                val card = s.holder(index)
                card.imageCard.drawable != null && !card.presentation.isLayoutRequested
            }
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
                assertTrue(image.preview.foreground is CardEdgeDrawable)
                assertTrue(image.card.foreground is CardEdgeDrawable)
                assertTrue(image.previewImage.colorFilter == null)
                assertTrue(boundsInAncestor(image.preview, image.presentation).height() < recycler.height / 2)
            }
        }
    }

    @Test
    fun imageAndHandwritingReadingScaleTheEntireCardIncludingItsControls() = scene { s ->
        for ((index, expectedAspect) in listOf(1 to .5f, 2 to 2f, 4 to .5f, 8 to .125f)) {
            s.onMain { manager.restoreState(index, true) }
            s.await {
                val card = s.recycler.findViewHolderForAdapterPosition(index) as? CardsAdapter.VH
                val image = if (index == 4) card?.handwriting else card?.imageCard
                val drawable = image?.drawable
                card != null && card.presentation.expansionProgress == 1f && image != null && drawable != null &&
                    kotlin.math.abs(drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight - expectedAspect) < .005f &&
                    kotlin.math.abs(image.width.toFloat() / image.height - expectedAspect) < .005f
            }
            s.onMain {
                val card = holder(index)
                assertTrue(card.card.foreground is CardEdgeDrawable)
                val image = if (index == 4) card.handwriting else card.imageCard
                val drawable = checkNotNull(image.drawable)
                val aspect = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                assertEquals(aspect, image.width.toFloat() / image.height, .005f)
                assertEquals(card.card.width, image.width)
                val reader = if (index == 4) card.handwritingScroll else card.imageScroll
                if (index == 4 || index == 8) {
                    assertTrue(reader.usesWidthFitViewport)
                    assertTrue(image.height >= card.card.height)
                    assertEquals(card.card.height, reader.height)
                } else assertEquals(card.card.height, image.height)
                val surface = card.presentation.getChildAt(0)
                assertEquals(surface.scaleX, surface.scaleY, 0f)
                val frame = boundsInAncestor(card.card, card.presentation)
                val picture = boundsInAncestor(image, card.presentation)
                assertEquals(frame.left, picture.left, 1f)
                assertEquals(frame.top, picture.top, 1f)
                assertEquals(frame.right, picture.right, 1f)
                if (index == 4 || index == 8) {
                    assertTrue(picture.bottom >= frame.bottom - 1f)
                    assertEquals(card.presentation.width.toFloat(), frame.width(), 1.5f)
                } else assertEquals(frame.bottom, picture.bottom, 1f)
                assertTrue(frame.height() <= card.presentation.readingHeightCap + 1f)
                assertTrue(frame.bottom <= card.presentation.height + 1f)
                val controls = boundsInAncestor(card.stickyNotesButton, card.presentation)
                assertTrue(frame.contains(controls))
                assertEquals(card.stickyNotesButton.width * surface.scaleX, controls.width(), 1f)
                assertTrue(card.presentation.containsCardPoint(frame.centerX(), frame.centerY()))
                if (frame.left > 1f) {
                    assertFalse(card.presentation.containsCardPoint(frame.left / 2f, frame.centerY()))
                }
            }
        }
    }

    @Test
    fun mediaOpeningKeepsWholePreviewProportionsAndAlignsTheReadingFrame() = scene { s ->
        for ((index, direction) in listOf(1 to 1, 4 to 1, 8 to -1)) {
            s.onMain { manager.restoreState(index, true) }
            s.await {
                val card = s.recycler.findViewHolderForAdapterPosition(index) as? CardsAdapter.VH
                val expectedAspect = if (index == 8) .125f else .5f
                val drawable = card?.previewImage?.drawable
                val original = if (index == 4) card?.handwriting else card?.imageCard
                card != null && card.presentation.expansionProgress == 1f && drawable != null &&
                    kotlin.math.abs(drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight - expectedAspect) < .005f &&
                    original != null && kotlin.math.abs(original.width.toFloat() / original.height - expectedAspect) < .005f
            }
            repeat(3) {
                s.onMain { recycler.scrollBy(0, direction * dp(138) / 4) }
                s.onMain {
                    val card = holder(index)
                    assertTrue(card.presentation.expansionProgress in 0.001f..0.999f)
                    val drawable = checkNotNull(card.previewImage.drawable)
                    val aspect = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                    val preview = boundsInAncestor(card.preview, card.presentation)
                    val reading = boundsInAncestor(card.card, card.presentation)
                    if (index == 4 || index == 8) {
                        assertTrue(preview.width() / preview.height() >= aspect)
                        assertEquals(ImageView.ScaleType.MATRIX, card.previewImage.scaleType)
                    } else assertEquals(aspect, preview.width() / preview.height(), .005f)
                    assertEquals(card.previewSurface.scaleX, card.previewSurface.scaleY, 0f)
                    assertEquals(reading.left, preview.left, 1.5f)
                    assertEquals(reading.top, preview.top, 1.5f)
                    assertEquals(reading.right, preview.right, 1.5f)
                    assertEquals(reading.bottom, preview.bottom, 1.5f)
                    assertTrue(preview.bottom <= card.presentation.height + 1f)
                }
            }
        }
    }

    @Test
    fun longImageScrollsInsideItsFrameAndAFreshEdgeSwipeBrowsesTheFlow() = scene { s ->
        s.onMain {
            val items = (0 until adapter.itemCount).map { checkNotNull(adapter.getItemAt(it)) }
            adapter.submitList(items + CardItem(10, "After the image", "Next card"))
        }
        s.await { s.adapter.itemCount == 10 }
        s.onMain { manager.restoreState(8, true) }
        s.await {
            val card = s.recycler.findViewHolderForAdapterPosition(8) as? CardsAdapter.VH
            card?.imageScroll?.usesWidthFitViewport == true && card.presentation.expansionProgress == 1f &&
                card.imageScroll.canScrollVertically(1)
        }
        s.onMain {
            val card = holder(8)
            val frame = boundsInAncestor(card.imageScroll, recycler)
            val controls = boundsInAncestor(card.stickyNotesButton, recycler)
            swipe(recycler, frame.centerX(), frame.top + frame.height() * .75f,
                frame.centerX(), frame.top + frame.height() * .35f, MotionEvent.ACTION_CANCEL)
            assertTrue(card.imageScroll.scrollY > dp(40))
            assertEquals(8, manager.currentSelectionIndex())
            assertEquals(1f, card.presentation.expansionProgress, 0f)
            assertEquals(controls, boundsInAncestor(card.stickyNotesButton, recycler))
            val bottom = card.imageCard.height - card.imageScroll.height
            card.imageScroll.scrollTo(0, bottom - dp(40))
            swipe(recycler, frame.centerX(), frame.top + frame.height() * .75f,
                frame.centerX(), frame.top + frame.height() * .35f, MotionEvent.ACTION_CANCEL)
            assertEquals(bottom, card.imageScroll.scrollY)
            assertEquals(8, manager.currentSelectionIndex())
            assertFalse(card.imageScroll.canScrollVertically(1))
            assertTrue(card.imageScroll.canScrollVertically(-1))
            val rendered = Bitmap.createBitmap(card.card.width, card.card.height, Bitmap.Config.ARGB_8888)
            try {
                card.card.draw(Canvas(rendered))
                assertEquals(Color.rgb(32, 113, 185), rendered.getPixel(rendered.width / 4, rendered.height / 2))
            } finally {
                rendered.recycle()
            }
            swipe(recycler, frame.centerX(), frame.top + frame.height() * .75f,
                frame.centerX(), frame.top + frame.height() * .25f)
        }
        s.await { s.manager.currentSelectionIndex() == 9 && s.manager.isFocused(9) }
        s.onMain { manager.restoreState(8, true) }
        s.await { s.manager.isFocused(8) && s.holder(8).imageScroll.usesWidthFitViewport }
        s.onMain {
            val card = holder(8)
            card.imageScroll.scrollTo(0, 0)
            val frame = boundsInAncestor(card.imageScroll, recycler)
            swipe(recycler, frame.centerX(), frame.top + frame.height() * .25f,
                frame.centerX(), frame.top + frame.height() * .75f)
        }
        s.await {
            val selected = s.manager.currentSelectionIndex()
            selected != null && selected < 8 && s.manager.isFocused(selected)
        }
    }

    @Test
    fun longImageHorizontalTurnsAndRebindingPreserveTheReadingOffset() = scene { s ->
        s.onMain { manager.restoreState(8, true) }
        s.await {
            val card = s.recycler.findViewHolderForAdapterPosition(8) as? CardsAdapter.VH
            card?.imageScroll?.usesWidthFitViewport == true && card.presentation.expansionProgress == 1f &&
                card.imageScroll.canScrollVertically(1)
        }
        var offset = 0
        s.onMain {
            val card = holder(8)
            card.imageScroll.scrollTo(0, card.imageCard.height - card.imageScroll.height)
            offset = card.imageScroll.scrollY
            val frame = boundsInAncestor(card.imageScroll, recycler)
            swipe(recycler, frame.left + frame.width() * .1f, frame.centerY(),
                frame.right - frame.width() * .1f, frame.centerY())
        }
        s.await { s.adapter.currentFaceFor(8) == HandwritingFace.BACK && !s.holder(8).flip.isFlipping }
        s.onMain { assertTrue(holder(8).flip.flip()) }
        s.await {
            s.adapter.currentFaceFor(8) == HandwritingFace.FRONT && !s.holder(8).flip.isFlipping &&
                s.holder(8).imageScroll.scrollY == offset
        }
        s.onMain { adapter.notifyItemChanged(8) }
        s.await {
            val card = s.holder(8)
            card.imageScroll.scrollY == offset && card.imageScroll.usesWidthFitViewport &&
                !card.imageScroll.isLayoutRequested
        }
        s.onMain { manager.restoreState(8, false) }
        s.await { s.holder(8).presentation.expansionProgress == 0f }
        s.onMain { manager.focus(8) }
        s.await { s.manager.isFocused(8) && s.holder(8).imageScroll.scrollY == offset }
        s.onMain {
            adapter.submitList((0 until adapter.itemCount).map {
                val item = checkNotNull(adapter.getItemAt(it))
                if (it == 8) item.copy(id = 100) else item
            })
        }
        s.await {
            val card = s.recycler.findViewHolderForAdapterPosition(8) as? CardsAdapter.VH
            card != null && card.itemView.getTag(R.id.tag_card_id) == 100L &&
                card.imageScroll.usesWidthFitViewport && !card.imageScroll.isLayoutRequested
        }
        s.onMain { assertEquals(0, holder(8).imageScroll.scrollY) }
    }

    @Test
    fun handwrittenPagesScrollToTheirBottomBeforeAFreshSwipeBrowsesTheFlow() = scene { s ->
        s.onMain {
            val pages = longHandwriting()
            adapter.submitList((0 until adapter.itemCount).map {
                val item = checkNotNull(adapter.getItemAt(it))
                if (it == 4) item.copy(handwriting = pages) else item
            })
            manager.restoreState(4, true)
        }
        s.await {
            val card = s.holder(4)
            card.presentation.expansionProgress == 1f && card.handwritingScroll.canScrollVertically(1) &&
                card.handwriting.drawable?.intrinsicHeight == 2400
        }
        s.onMain {
            val card = holder(4)
            assertFalse(card.faceLoadFailed)
            val frame = boundsInAncestor(card.handwritingScroll, recycler)
            val surface = boundsInAncestor(card.card, card.presentation)
            assertEquals(card.presentation.width.toFloat(), surface.width(), 1.5f)
            assertEquals(card.card.height, card.handwritingScroll.height)
            assertTrue(card.handwriting.height > card.card.height)
            val controls = boundsInAncestor(card.stickyNotesButton, recycler)
            swipe(recycler, frame.centerX(), frame.top + frame.height() * .75f,
                frame.centerX(), frame.top + frame.height() * .35f, MotionEvent.ACTION_CANCEL)
            assertTrue(card.handwritingScroll.scrollY > dp(40))
            assertEquals(4, manager.currentSelectionIndex())
            assertEquals(controls, boundsInAncestor(card.stickyNotesButton, recycler))
            val bottom = card.handwriting.height - card.handwritingScroll.height
            card.handwritingScroll.scrollTo(0, bottom - dp(40))
            swipe(recycler, frame.centerX(), frame.top + frame.height() * .75f,
                frame.centerX(), frame.top + frame.height() * .35f, MotionEvent.ACTION_CANCEL)
            assertEquals(bottom, card.handwritingScroll.scrollY)
            assertEquals(4, manager.currentSelectionIndex())
            val rendered = Bitmap.createBitmap(card.card.width, card.card.height, Bitmap.Config.ARGB_8888)
            try {
                card.card.draw(Canvas(rendered))
                assertEquals(Color.rgb(32, 113, 185), rendered.getPixel(rendered.width / 4, rendered.height / 2))
            } finally {
                rendered.recycle()
            }
            swipe(recycler, frame.centerX(), frame.top + frame.height() * .75f,
                frame.centerX(), frame.top + frame.height() * .25f)
        }
        s.await {
            val selected = s.manager.currentSelectionIndex()
            selected != null && selected > 4 && s.manager.isFocused(selected)
        }
        s.onMain { manager.restoreState(4, true) }
        s.await { s.manager.isFocused(4) && s.holder(4).handwritingScroll.usesWidthFitViewport }
        s.onMain {
            val card = holder(4)
            card.handwritingScroll.scrollTo(0, 0)
            val frame = boundsInAncestor(card.handwritingScroll, recycler)
            swipe(recycler, frame.centerX(), frame.top + frame.height() * .25f,
                frame.centerX(), frame.top + frame.height() * .75f)
        }
        s.await {
            val selected = s.manager.currentSelectionIndex()
            selected != null && selected < 4 && s.manager.isFocused(selected)
        }
    }

    @Test
    fun handwrittenHorizontalTurnsKeepSeparateFrontAndBackReadingOffsets() = scene { s ->
        s.onMain {
            val pages = longHandwriting()
            adapter.submitList((0 until adapter.itemCount).map {
                val item = checkNotNull(adapter.getItemAt(it))
                if (it == 4) item.copy(handwriting = pages) else item
            })
            manager.restoreState(4, true)
        }
        s.await {
            s.holder(4).handwriting.drawable?.intrinsicHeight == 2400 &&
                s.holder(4).presentation.expansionProgress == 1f
        }
        var frontOffset = 0
        var backOffset = 0
        s.onMain {
            val card = holder(4)
            card.handwritingScroll.scrollTo(0, dp(160))
            frontOffset = card.handwritingScroll.scrollY
            val frame = boundsInAncestor(card.handwritingScroll, recycler)
            swipe(recycler, frame.left + frame.width() * .1f, frame.centerY(),
                frame.right - frame.width() * .1f, frame.centerY())
        }
        s.await { s.adapter.currentFaceFor(4) == HandwritingFace.BACK && !s.holder(4).flip.isFlipping }
        s.onMain {
            assertFalse(holder(4).faceLoadFailed)
            assertEquals(0, holder(4).handwritingScroll.scrollY)
            holder(4).handwritingScroll.scrollTo(0, dp(260))
            backOffset = holder(4).handwritingScroll.scrollY
            assertTrue(holder(4).flip.flip())
        }
        s.await {
            s.adapter.currentFaceFor(4) == HandwritingFace.FRONT && !s.holder(4).flip.isFlipping &&
                s.holder(4).handwritingScroll.scrollY == frontOffset
        }
        s.onMain { adapter.notifyItemChanged(4) }
        s.await {
            s.holder(4).handwritingScroll.scrollY == frontOffset &&
                !s.holder(4).handwritingScroll.isLayoutRequested
        }
        s.onMain { assertTrue(holder(4).flip.flip()) }
        s.await {
            s.adapter.currentFaceFor(4) == HandwritingFace.BACK && !s.holder(4).flip.isFlipping &&
                s.holder(4).handwritingScroll.scrollY == backOffset
        }
        s.onMain { manager.restoreState(4, false) }
        s.await { s.holder(4).presentation.expansionProgress == 0f }
        s.onMain { manager.focus(4) }
        s.await { s.manager.isFocused(4) && s.holder(4).handwritingScroll.scrollY == backOffset }
    }

    @Test
    fun imageCardHandwrittenReverseScrollsWithoutChangingItsOriginalImageFit() = scene { s ->
        s.onMain {
            val back = longHandwriting().back
            adapter.submitList((0 until adapter.itemCount).map {
                val item = checkNotNull(adapter.getItemAt(it))
                if (it == 2) item.copy(imageHandwriting = back) else item
            })
            manager.restoreState(2, true)
        }
        s.await { s.manager.isFocused(2) && s.adapter.canFlipCardAt(2) }
        s.onMain { assertTrue(holder(2).flip.flip()) }
        s.await {
            s.adapter.currentFaceFor(2) == HandwritingFace.BACK && !s.holder(2).flip.isFlipping &&
                s.holder(2).handwriting.drawable?.intrinsicHeight == 2400
        }
        s.onMain {
            val card = holder(2)
            assertFalse(card.faceLoadFailed)
            assertTrue(card.handwritingScroll.usesWidthFitViewport)
            val frame = boundsInAncestor(card.handwritingScroll, recycler)
            swipe(recycler, frame.centerX(), frame.top + frame.height() * .75f,
                frame.centerX(), frame.top + frame.height() * .35f, MotionEvent.ACTION_CANCEL)
            assertTrue(card.handwritingScroll.scrollY > dp(40))
            assertEquals(2, manager.currentSelectionIndex())
            assertTrue(card.flip.flip())
        }
        s.await { s.adapter.currentFaceFor(2) == HandwritingFace.FRONT && !s.holder(2).flip.isFlipping }
        s.onMain {
            val card = holder(2)
            assertFalse(card.imageScroll.usesWidthFitViewport)
            assertEquals(card.card.height, card.imageCard.height)
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
                assertTrue(holder(0).itemView.rotationX < 0f)
                assertTrue(holder(1).itemView.rotationX > 0f)
                for (index in 0..1) {
                    val card = holder(index)
                    if (!card.presentation.isMediaPreview) assertScreenFacingCardLettering(card.presentation)
                    if (card.presentation.isMediaPreview) {
                        assertEquals(card.itemView.height, card.itemView.clipBounds?.bottom)
                    } else assertNull(card.itemView.clipBounds)
                    assertEquals(1f, card.preview.alpha, 0f)
                    if (!card.presentation.isMediaPreview) {
                        assertEquals(card.itemView.height, card.previewSurface.bottom)
                    }
                    val readingBounds = boundsInAncestor(card.card, card.presentation)
                    val previewBounds = boundsInAncestor(card.previewSurface, card.presentation)
                    assertEquals(previewBounds.top, readingBounds.top, 1.5f)
                    if (!card.presentation.isMediaPreview) {
                        assertEquals(
                            (card.previewSurface.left + card.preview.left).toFloat(),
                            readingBounds.left, .5f
                        )
                    }
                    assertEquals(1f, card.previewText.alpha, 0f)
                    if (card.presentation.expansionProgress <= 0.85f) {
                        assertEquals(0f, card.collapseButton.alpha, 0f)
                    }
                }
                previous = departing
                next = arriving
            }
        }
        var settled = false
        s.onMain { manager.settleScrollIfNeeded { settled = true } }
        s.await {
            settled && s.manager.isFocused(1) && s.holder(1).presentation.expansionProgress == 1f &&
                s.holder(0).presentation.expansionProgress == 0f
        }
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
        var oldCorners = emptyList<FloatArray>()
        s.onMain {
            recycler.scrollBy(0, dp(138) / 2 - 2)
            oldTop = holder(1).itemView.top
            oldZ = holder(1).itemView.translationZ
            oldCorners = (0..1).map { projectedCorners(holder(it).itemView) }
            recycler.scrollBy(0, 4)
        }
        s.onMain {
            assertTrue(kotlin.math.abs(holder(1).itemView.top - oldTop) < dp(16))
            assertTrue(kotlin.math.abs(holder(1).itemView.translationZ - oldZ) < dp(2))
            for (index in 0..1) {
                val corners = projectedCorners(holder(index).itemView)
                assertTrue("A projected corner jumped when selection changed",
                    corners.indices.all { kotlin.math.abs(corners[it] - oldCorners[index][it]) < dp(16) })
            }
            assertTrue(holder(0).presentation.expansionProgress > 0.4f)
            assertTrue(holder(1).presentation.expansionProgress > 0.4f)
        }
    }

    @Test
    fun firstAndLastReadingCardsStayOpenAtBoundaries() = scene { s ->
        for ((index, direction) in listOf(0 to -1, (s.adapter.itemCount - 1) to 1)) {
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
    fun readingEndpointsPullWithIncreasingResistanceWithoutChangingTheirGeometry() = scene { s ->
        for ((index, direction) in listOf(0 to -1, (s.adapter.itemCount - 1) to 1)) {
            s.openCard(index)
            s.onMain {
                val card = holder(index).presentation
                val original = projectedCorners(card)
                val measures = card.measurePassCount
                val width = card.width
                val height = card.height
                val textOffset = holder(index).textScroll.scrollY
                val imageOffset = holder(index).imageScroll.scrollY
                val distances = mutableListOf<Float>()
                repeat(3) {
                    recycler.scrollBy(0, direction * dp(80))
                    val corners = projectedCorners(card)
                    val distance = (corners[1] - original[1]) * -direction
                    distances.add(distance)
                    assertTrue(distance > dp(5))
                    assertTrue(distance < minOf(dp(96).toFloat(), recycler.height * .12f))
                    for (point in 0..3) {
                        assertEquals(original[point * 2], corners[point * 2], .01f)
                        assertEquals(corners[1] - original[1],
                            corners[point * 2 + 1] - original[point * 2 + 1], .01f)
                    }
                    assertEquals(0, manager.offsetTo(index))
                    assertEquals(index, manager.currentSelectionIndex())
                    assertEquals(1f, card.expansionProgress, 0f)
                }
                assertTrue(distances[0] > distances[1] - distances[0])
                assertTrue(distances[1] - distances[0] > distances[2] - distances[1])
                assertEquals(width, card.width)
                assertEquals(height, card.height)
                assertEquals(measures, card.measurePassCount)
                assertEquals(textOffset, holder(index).textScroll.scrollY)
                assertEquals(imageOffset, holder(index).imageScroll.scrollY)
                manager.settleScrollIfNeeded()
            }
            s.await { s.manager.isFocused(index) }
            s.onMain { assertEquals(0, manager.offsetTo(index)) }
        }
    }

    @Test
    fun reversingAnEdgePullRetracesExactlyBeforeBrowsingInward() = scene { s ->
        for ((index, direction) in listOf(0 to -1, (s.adapter.itemCount - 1) to 1)) {
            s.openCard(index)
            s.onMain {
                val card = holder(index).presentation
                val original = projectedCorners(card)
                recycler.scrollBy(0, direction * dp(160))
                val pulled = projectedCorners(card)
                recycler.scrollBy(0, -direction * dp(40))
                val returning = projectedCorners(card)
                assertTrue(abs(returning[1] - original[1]) < abs(pulled[1] - original[1]))
                assertEquals(0, manager.offsetTo(index))
                recycler.scrollBy(0, -direction * (dp(160) - dp(40)))
                assertArrayEquals(original, projectedCorners(card), .01f)
                assertEquals(0, manager.offsetTo(index))
                recycler.scrollBy(0, -direction * dp(138) / 3)
                assertTrue(card.expansionProgress < 1f)
                assertTrue(holder(index - direction).presentation.expansionProgress > 0f)
                manager.settleScrollIfNeeded()
            }
            s.await { s.manager.isFocused(index) }
        }
    }

    @Test
    fun overviewAndSingleCardPullsAreBoundedWithoutPhantomCards() = scene { s ->
        for (index in listOf(0, s.adapter.itemCount - 1)) {
            s.onMain { manager.restoreState(index, false) }
            s.await {
                s.recycler.findViewHolderForAdapterPosition(index) != null &&
                    s.holder(index).isFaceReady && !s.holder(index).itemView.isLayoutRequested &&
                    s.holder(index).presentation.expansionProgress == 0f
            }
            var original = FloatArray(0)
            s.onMain {
                val card = holder(index).presentation
                original = projectedCorners(card)
                val direction = if (index == 0) -1 else 1
                val children = recycler.childCount
                recycler.scrollBy(0, direction * dp(20_000))
                val distance = (projectedCorners(card)[1] - original[1]) * -direction
                assertTrue(distance > dp(60))
                assertTrue(distance <= dp(96) && distance <= recycler.height * .12f)
                assertEquals(children, recycler.childCount)
                assertEquals(index, manager.nearestIndex())
                assertFalse(manager.hasSelection())
                assertEquals(0f, card.expansionProgress, 0f)
                manager.settleScrollIfNeeded()
            }
            s.await { abs(projectedCorners(s.holder(index).presentation)[1] - original[1]) < .1f }
        }
        s.onMain {
            adapter.submitList(listOf(checkNotNull(adapter.getItemAt(5)))) { manager.restoreState(0, true) }
        }
        s.await { s.adapter.itemCount == 1 && s.manager.isFocused(0) }
        s.openCard(0)
        s.onMain {
            val original = projectedCorners(holder(0).presentation)
            for (direction in listOf(-1, 1)) {
                recycler.scrollBy(0, direction * dp(180))
                assertTrue((projectedCorners(holder(0).presentation)[1] - original[1]) * -direction > dp(10))
                recycler.scrollBy(0, -direction * dp(180))
                assertArrayEquals(original, projectedCorners(holder(0).presentation), .01f)
            }
            manager.settleScrollIfNeeded()
        }
        s.await { s.manager.isFocused(0) }
    }

    @Test
    fun realOutwardDragsAndCancellationReboundAtBothReadingEndpoints() = scene { s ->
        for ((index, direction) in listOf(0 to -1, (s.adapter.itemCount - 1) to 1)) {
            s.openCard(index)
            var original = FloatArray(0)
            s.onMain {
                original = projectedCorners(holder(index).presentation)
                val now = SystemClock.uptimeMillis()
                val x = dp(2).toFloat()
                val y = recycler.height / 2f
                assertFalse(manager.hasCardUnder(x, y))
                for (step in 0..7) {
                    val action = if (step == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
                    val event = MotionEvent.obtain(now, now + step * 30L, action,
                        x, y - direction * dp(150) * step / 7f, 0)
                    try {
                        recycler.dispatchTouchEvent(event)
                    } finally {
                        event.recycle()
                    }
                }
                assertEquals("Boundary $index did not accept the drag",
                    RecyclerView.SCROLL_STATE_DRAGGING, recycler.scrollState)
                assertTrue((projectedCorners(holder(index).presentation)[1] - original[1]) * -direction > dp(10))
                val event = MotionEvent.obtain(now, now + 250,
                    if (index == 0) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,
                    x, y - direction * dp(150), 0)
                try {
                    recycler.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            s.await {
                s.recycler.scrollState == RecyclerView.SCROLL_STATE_IDLE && s.manager.isFocused(index) &&
                    abs(projectedCorners(s.holder(index).presentation)[1] - original[1]) < .01f
            }
            s.onMain { assertArrayEquals(original, projectedCorners(holder(index).presentation), .01f) }
        }
    }

    @Test
    fun catchingAndRestoringAnEdgeReboundPreservePositionAndDiscardItsCallback() = scene { s ->
        s.openCard(0)
        var staleCallback = false
        s.onMain {
            recycler.scrollBy(0, -dp(240))
            manager.settleScrollIfNeeded { staleCallback = true }
            val caught = projectedCorners(holder(0).presentation)
            manager.onScrollStateChanged(RecyclerView.SCROLL_STATE_DRAGGING)
            assertArrayEquals(caught, projectedCorners(holder(0).presentation), 0f)
            recycler.scrollBy(0, -dp(40))
            assertTrue(projectedCorners(holder(0).presentation)[1] > caught[1])
            assertFalse(staleCallback)
            manager.settleScrollIfNeeded { staleCallback = true }
            manager.restoreState(5, true)
        }
        s.await { s.manager.isFocused(5) }
        SystemClock.sleep(600)
        s.onMain {
            assertFalse(staleCallback)
            assertEquals(0, manager.offsetTo(5))
            assertEquals(1f, holder(5).presentation.expansionProgress, 0f)
        }
    }

    @Test
    fun outwardFlingsReturnToTheSameOpenBoundaryCard() = scene { s ->
        for ((index, direction) in listOf(0 to -1, (s.adapter.itemCount - 1) to 1)) {
            s.openCard(index)
            var original = FloatArray(0)
            var moved = false
            val listener = ViewTreeObserver.OnPreDrawListener {
                if (abs(projectedCorners(s.holder(index).presentation)[1] - original[1]) > s.dp(1)) moved = true
                true
            }
            s.onMain {
                original = projectedCorners(holder(index).presentation)
                root.viewTreeObserver.addOnPreDrawListener(listener)
                assertTrue(recycler.fling(0, direction * dp(2400)))
            }
            try {
                s.await {
                    moved && s.recycler.scrollState == RecyclerView.SCROLL_STATE_IDLE && s.manager.isFocused(index) &&
                        abs(projectedCorners(s.holder(index).presentation)[1] - original[1]) < .01f
                }
                s.onMain {
                    assertArrayEquals(original, projectedCorners(holder(index).presentation), .01f)
                    assertEquals(0, manager.offsetTo(index))
                }
            } finally {
                s.onMain { root.viewTreeObserver.removeOnPreDrawListener(listener) }
            }
        }
    }

    @Test
    fun detachingDuringAReboundDoesNotLeaveAReattachedCardShiftedOrUnfocused() = scene { s ->
        s.openCard(0)
        var original = FloatArray(0)
        var staleCallback = false
        s.onMain {
            original = projectedCorners(holder(0).presentation)
            recycler.scrollBy(0, -dp(240))
            manager.settleScrollIfNeeded { staleCallback = true }
            root.removeView(recycler)
            root.addView(recycler, FrameLayout.LayoutParams(-1, -1))
        }
        s.await {
            s.manager.isFocused(0) && s.recycler.findViewHolderForAdapterPosition(0) != null &&
                abs(projectedCorners(s.holder(0).presentation)[1] - original[1]) < .01f
        }
        s.onMain {
            assertFalse(staleCallback)
            assertEquals(0, manager.offsetTo(0))
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
        var startY = 0f
        s.onMain { startY = boundsInAncestor(holder(0).time, recycler).centerY() }
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
            assertNotNull(holder(0).previewImage.colorFilter)
            assertNotNull(holder(0).card.foreground)
            assertEquals(View.GONE, holder(0).textScrim.visibility)
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

    @Test
    fun sharedTextNeverDisappearsAtTheOldFadeThroughBoundaryAndSurplusFades() = scene { s ->
        s.onMain { manager.restoreState(0, true) }
        s.await { s.holder(0).presentation.expansionProgress == 1f }
        s.onMain {
            val card = holder(0)
            val renderer = CardTextTransition(card.presentation)
            val frame = Bitmap.createBitmap(card.itemView.width, card.itemView.height, Bitmap.Config.ARGB_8888)
            try {
                var previousInk = Long.MAX_VALUE
                for (progress in listOf(.9f, .5f, .18f, .08f)) {
                    frame.eraseColor(Color.TRANSPARENT)
                    assertTrue(renderer.prepare(progress, false))
                    renderer.draw(Canvas(frame), progress)
                    if (InstrumentationRegistry.getArguments().getString("capturePerspective") == "true") {
                        File(root.context.cacheDir, "card-text-only-$progress.png").outputStream().use {
                            assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    }
                    assertTrue(renderer.sharedRunCount > 0)
                    var peak = 0
                    var ink = 0L
                    for (y in 0 until frame.height) for (x in 0 until frame.width) {
                        val alpha = Color.alpha(frame.getPixel(x, y))
                        peak = maxOf(peak, alpha)
                        ink += alpha
                    }
                    assertTrue("Shared lettering fades through a blank frame at $progress", peak > 230)
                    assertTrue("Surplus text did not fade as the card collapsed", ink < previousInk)
                    previousInk = ink
                }
                renderer.clear()
                assertEquals(1f, card.snippet.alpha, 0f)
                assertEquals(1f, card.previewSnippet.alpha, 0f)
            } finally {
                renderer.clear()
                frame.recycle()
            }
        }
    }

    @Test
    fun surplusTextStaysOnItsReadingPlaneWhileOnlySummaryGlyphsMove() = scene { s ->
        s.longText()
        s.await { s.holder(5).presentation.expansionProgress == 1f &&
            s.holder(5).textScroll.canScrollVertically(1) }
        s.onMain {
            val card = holder(5)
            val body = boundsInAncestor(card.textScroll, card.presentation)
            val x = body.left.roundToInt()
            val y = (body.top + body.height() * .55f).roundToInt()
            val width = body.width().roundToInt()
            val height = dp(48)
            val renderer = CardTextTransition(card.presentation)
            val frame = Bitmap.createBitmap(card.presentation.width, card.presentation.height,
                Bitmap.Config.ARGB_8888)
            fun pixels(progress: Float): IntArray {
                frame.eraseColor(Color.TRANSPARENT)
                assertTrue(renderer.prepare(progress, false))
                assertTrue(renderer.sharedRunCount > 0)
                renderer.draw(Canvas(frame), progress)
                return IntArray(width * height).also {
                    frame.getPixels(it, 0, width, x, y, width, height)
                }
            }
            try {
                val original = pixels(.7f)
                assertTrue(original.any { Color.alpha(it) > 100 })
                val snapshots = renderer.snapshotCount
                card.previewText.scaleX = .7f
                card.previewText.scaleY = .7f
                card.previewText.translationX -= dp(40)
                card.previewText.translationY -= dp(60)
                assertArrayEquals("Surplus paragraphs inherited the summary's shrink/translation",
                    original, pixels(.7f))
                assertEquals(snapshots, renderer.snapshotCount)
                var previousInk = Long.MAX_VALUE
                for (progress in listOf(.85f, .6f, .35f, .1f)) {
                    val ink = pixels(progress).sumOf { Color.alpha(it).toLong() }
                    assertTrue("Surplus paragraphs stopped fading at $progress", ink < previousInk)
                    previousInk = ink
                }
            } finally {
                renderer.clear()
                frame.recycle()
            }
        }
    }

    @Test
    fun movingSharedTextClearsSurplusInkInsteadOfOverprintingIt() = scene { s ->
        s.collisionText(Color.BLUE, truncate = true)
        s.onMain {
            val card = holder(7)
            val source = textRangeBounds(card.snippet, card.presentation, 0, 6)
            val surplus = textRangeBounds(card.snippet, card.presentation, 7, 13)
            val target = textRangeBounds(card.previewSnippet, card.presentation, 0, 6)
            val advance = card.snippet.paint.measureText("M")
            card.previewText.translationX += 2f * (surplus.centerX() + advance / 2f) -
                source.centerX() - target.centerX()
            card.previewText.translationY += 2f * surplus.centerY() - source.centerY() - target.centerY()
            val region = RectF(surplus).apply {
                inset(advance * 1.5f, height() * .25f)
            }
            val renderer = CardTextTransition(card.presentation)
            val frame = Bitmap.createBitmap(card.presentation.width, card.presentation.height,
                Bitmap.Config.ARGB_8888)
            try {
                assertTrue(renderer.prepare(.5f, false))
                assertEquals(1, renderer.sharedRunCount)
                renderer.draw(Canvas(frame), .5f)
                checkTextReference(frame, "overlap-moving")
                val blue = countTextPixels(frame, region) { Color.blue(it) > 24 }
                assertEquals("Surplus ink remains under the moving foreground: $blue pixels", 0, blue)
                assertEquals("An occluded surplus line must fade as a whole, not get sliced", 0,
                    countTextPixels(frame, surplus) { Color.blue(it) > 24 })
                assertTrue(countTextPixels(frame, region) { Color.red(it) > 128 } > 20)
            } finally {
                renderer.clear()
                frame.recycle()
            }
        }
    }

    @Test
    fun reflowingSharedRunsOccludeInsteadOfMixingTheirGlyphs() = scene { s ->
        s.collisionText(Color.GREEN, truncate = false)
        s.onMain {
            val card = holder(7)
            val source = textRangeBounds(card.snippet, card.presentation, 0, 6)
            val target = textRangeBounds(card.previewSnippet, card.presentation, 0, 6)
            card.previewText.translationX += source.left - target.left
            card.previewText.translationY += source.top - target.top
            val renderer = CardTextTransition(card.presentation)
            val frame = Bitmap.createBitmap(card.presentation.width, card.presentation.height,
                Bitmap.Config.ARGB_8888)
            try {
                assertTrue(renderer.prepare(.35f, false))
                assertEquals(2, renderer.sharedRunCount)
                renderer.draw(Canvas(frame), .35f)
                checkTextReference(frame, "overlap-reflow")
                val bounds = RectF(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
                val mixed = countTextPixels(frame, bounds) { Color.red(it) > 24 && Color.green(it) > 24 }
                assertEquals("Crossing shared runs overprint each other: $mixed mixed pixels", 0, mixed)
                assertTrue(countTextPixels(frame, bounds) { Color.red(it) > 128 } > 20)
                assertEquals("The rear fragment must retreat whole instead of showing chopped glyphs", 0,
                    countTextPixels(frame, bounds) { Color.green(it) > 24 })
                frame.eraseColor(Color.TRANSPARENT)
                assertTrue(renderer.prepare(.99f, false))
                renderer.draw(Canvas(frame), .99f)
                assertTrue("Separated glyphs must remain visible near the reading endpoint",
                    countTextPixels(frame, bounds) { Color.green(it) > 230 } > 20)
            } finally {
                renderer.clear()
                frame.recycle()
            }
        }
    }

    @Test
    fun scrolledUnmatchedPreviewClearsOnlyTheReadingTextBehindIt() = scene { s ->
        s.collisionText(Color.BLUE, truncate = true, lines = 60)
        s.onMain {
            val card = holder(7)
            val layout = checkNotNull(card.snippet.layout)
            card.textScroll.scrollTo(0, layout.getLineTop(7))
            val visibleStart = layout.getLineStart(7)
            val surplus = textRangeBounds(card.snippet, card.presentation, visibleStart, visibleStart + 6)
            val untouched = textRangeBounds(card.snippet, card.presentation, visibleStart + 7, visibleStart + 13)
            val target = textRangeBounds(card.previewSnippet, card.presentation, 0, 6)
            val advance = card.snippet.paint.measureText("M")
            card.previewText.translationX += surplus.left + advance / 2f - target.left
            card.previewText.translationY += surplus.top - target.top
            val region = RectF(surplus).apply { inset(advance * 1.5f, height() * .25f) }
            val renderer = CardTextTransition(card.presentation)
            val frame = Bitmap.createBitmap(card.presentation.width, card.presentation.height,
                Bitmap.Config.ARGB_8888)
            try {
                assertTrue(renderer.prepare(.5f, false))
                assertEquals(0, renderer.sharedRunCount)
                renderer.draw(Canvas(frame), .5f)
                checkTextReference(frame, "overlap-scrolled")
                assertEquals("Unmatched preview overprints the scrolled reading window", 0,
                    countTextPixels(frame, region) { Color.blue(it) > 24 })
                assertTrue(countTextPixels(frame, region) { Color.red(it) > 64 } > 20)
                assertTrue("Uncovered reading text must keep fading at its own position",
                    countTextPixels(frame, untouched) { Color.blue(it) > 64 } > 20)
            } finally {
                renderer.clear()
                frame.recycle()
            }
        }
    }

    @Test
    fun hardwareTextOcclusionSeparatesGlyphsWithoutErasingTheCardFace() = scene { s ->
        s.collisionText(Color.GREEN, truncate = false)
        lateinit var renderer: CardTextTransition
        lateinit var overlay: View
        val region = RectF()
        val blank = IntArray(2)
        s.onMain {
            val card = holder(7)
            val source = textRangeBounds(card.snippet, card.presentation, 0, 6)
            val target = textRangeBounds(card.previewSnippet, card.presentation, 0, 6)
            card.previewText.translationX += source.left - target.left
            card.previewText.translationY += source.top - target.top
            renderer = CardTextTransition(card.presentation)
            assertTrue(renderer.prepare(.35f, false))
            val position = IntArray(2)
            card.presentation.getLocationOnScreen(position)
            region.set(position[0].toFloat(), position[1].toFloat(),
                (position[0] + card.presentation.width).toFloat(),
                (position[1] + card.presentation.height).toFloat())
            blank[0] = (region.left + source.left + card.snippet.paint.measureText("M") * 2.5f).toInt()
            blank[1] = (region.top + source.top + 2f).toInt()
            val inRoot = IntArray(2)
            root.getLocationOnScreen(inRoot)
            overlay = object : View(root.context) {
                override fun onDraw(canvas: Canvas) = renderer.draw(canvas, .35f)
            }
            overlay.layoutParams = FrameLayout.LayoutParams(card.presentation.width, card.presentation.height).apply {
                leftMargin = position[0] - inRoot[0]
                topMargin = position[1] - inRoot[1]
            }
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var before: Bitmap? = null
        var after: Bitmap? = null
        try {
            awaitPresented(s)
            val underlay = checkNotNull(automation.takeScreenshot()).also { before = it }
            s.onMain { root.addView(overlay) }
            awaitPresented(s)
            val frame = checkNotNull(automation.takeScreenshot()).also { after = it }
            if (InstrumentationRegistry.getArguments().getString("capturePerspective") == "true") {
                File(s.root.context.cacheDir, "card-text-hardware-overlap.png").outputStream().use {
                    assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            assertEquals("Hardware blending mixed foreground and rear glyphs", 0,
                countTextPixels(frame, region) { Color.red(it) > 24 && Color.green(it) > 24 && Color.blue(it) < 24 })
            assertTrue("Foreground glyphs disappeared on the hardware canvas",
                countTextPixels(frame, region) { Color.red(it) > 128 && Color.green(it) < 24 && Color.blue(it) < 24 } > 20)
            assertEquals("Hardware occlusion leaked into the card face",
                underlay.getPixel(blank[0], blank[1]), frame.getPixel(blank[0], blank[1]))
        } finally {
            s.onMain {
                root.removeView(overlay)
                renderer.clear()
            }
            before?.recycle()
            after?.recycle()
        }
    }

    private fun Scene.collisionText(secondColor: Int, truncate: Boolean, lines: Int = 2) {
        onMain { manager.restoreState(7, true) }
        await { holder(7).presentation.expansionProgress == 1f }
        onMain {
            val card = holder(7)
            val source = List(lines) { "MMMMMM" }.joinToString("\n")
            fun colored(text: String) = SpannableString(text).apply {
                setSpan(ForegroundColorSpan(Color.RED), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(secondColor), 7, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            for (view in listOf(card.title, card.previewTitle, card.time, card.previewTime, card.previewHeading)) {
                view.visibility = View.GONE
            }
            for (view in listOf(card.snippet, card.previewSnippet)) {
                (view as TimescapeTextView).depthAmount = 0f
                view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                view.typeface = Typeface.MONOSPACE
                view.textSize = 24f
            }
            card.snippet.text = colored(source)
            card.previewSnippet.text = if (truncate) source.replace('\n', ' ')
                else colored(source.replace('\n', ' '))
            card.previewSnippet.setTextColor(Color.RED)
            card.previewSnippet.maxLines = 1
            card.previewSnippet.ellipsize = TextUtils.TruncateAt.END
            if (truncate) {
                card.previewSnippet.layoutParams = card.previewSnippet.layoutParams.apply {
                    width = (card.previewSnippet.paint.measureText("M") * 7f).roundToInt()
                }
            }
        }
        await {
            val card = holder(7)
            card.snippet.layout?.lineCount == lines && card.previewSnippet.layout?.lineCount == 1 &&
                !card.presentation.isLayoutRequested
        }
    }

    private fun textRangeBounds(view: TextView, ancestor: View, start: Int, end: Int): RectF {
        val layout = checkNotNull(view.layout)
        val path = android.graphics.Path()
        layout.getSelectionPath(start, end, path)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        bounds.offset((view.compoundPaddingLeft - view.scrollX).toFloat(),
            (view.baseline - layout.getLineBaseline(0) - view.scrollY).toFloat())
        val matrix = Matrix()
        view.renderedMatrixToAncestor(ancestor, matrix)
        matrix.mapRect(bounds)
        return bounds
    }

    private fun countTextPixels(frame: Bitmap, bounds: RectF, matches: (Int) -> Boolean): Int {
        var count = 0
        for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(frame.height)) {
            for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(frame.width)) {
                val pixel = frame.getPixel(x, y)
                if (Color.alpha(pixel) > 24 && matches(pixel)) count++
            }
        }
        return count
    }

    @Test
    fun ellipsizedWordSuffixFadesOnTheReadingPlaneInsteadOfFollowingItsPrefix() = scene { s ->
        s.onMain {
            val item = checkNotNull(adapter.getItemAt(7))
            item.title = ""
            item.snippet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(12)
            adapter.notifyItemChanged(7)
            manager.restoreState(7, true)
        }
        s.await { s.holder(7).presentation.expansionProgress == 1f }
        s.onMain {
            val card = holder(7)
            val preview = checkNotNull(card.previewSnippet.layout)
            val lastLine = preview.lineCount - 1
            assertTrue(preview.getEllipsisCount(lastLine) > 0)
            val end = preview.getLineStart(lastLine) + preview.getEllipsisStart(lastLine)
            val matches = matchedCardTextOffsets(card.snippet.text, card.previewSnippet.text, end)
            val sourceEnd = matches[matches.size - 2] + 1
            val layout = checkNotNull(card.snippet.layout)
            val line = layout.getLineForOffset(sourceEnd)
            val tailStart = sourceEnd + 2
            val tailEnd = layout.getLineEnd(line) - 2
            assertTrue("Fixture must truncate within a reading word", tailEnd > tailStart)
            val origin = boundsInAncestor(card.snippet, card.presentation)
            val x = (origin.left + card.snippet.compoundPaddingLeft +
                layout.getPrimaryHorizontal(tailStart)).roundToInt()
            val width = (layout.getPrimaryHorizontal(tailEnd) - layout.getPrimaryHorizontal(tailStart))
                .roundToInt()
            val y = (origin.top + card.snippet.totalPaddingTop + layout.getLineTop(line) + dp(10))
                .roundToInt()
            val height = layout.getLineBottom(line) - layout.getLineTop(line) - dp(14)
            assertTrue(width > 0 && height > 0)
            val renderer = CardTextTransition(card.presentation)
            val frame = Bitmap.createBitmap(card.presentation.width, card.presentation.height,
                Bitmap.Config.ARGB_8888)
            fun pixels(): IntArray {
                frame.eraseColor(Color.TRANSPARENT)
                assertTrue(renderer.prepare(.95f, false))
                renderer.draw(Canvas(frame), .95f)
                return IntArray(width * height).also {
                    frame.getPixels(it, 0, width, x, y, width, height)
                }
            }
            try {
                val original = pixels()
                assertTrue("Unshared suffix disappeared at the reading anchor",
                    original.any { Color.alpha(it) > 220 })
                card.previewText.translationX -= dp(60)
                card.previewText.translationY -= dp(60)
                card.previewText.scaleX = .7f
                assertArrayEquals("Unshared suffix followed the moving shared prefix", original, pixels())
            } finally {
                renderer.clear()
                frame.recycle()
            }
        }
    }

    @Test
    fun datesFadeAtTheirOwnAnchorsInsteadOfFlyingThroughTheParagraph() = scene { s ->
        s.onMain { manager.restoreState(0, true) }
        s.await { s.holder(0).presentation.expansionProgress == 1f }
        s.onMain {
            val card = holder(0)
            for (text in listOf(card.title, card.snippet, card.previewTitle, card.previewSnippet)) {
                text.visibility = View.GONE
            }
            val anchors = listOf(card.time, card.previewTime).map {
                boundsInAncestor(it, card.presentation).apply { inset(-dp(4).toFloat(), -dp(4).toFloat()) }
            }
            val renderer = CardTextTransition(card.presentation)
            val frame = Bitmap.createBitmap(card.itemView.width, card.itemView.height, Bitmap.Config.ARGB_8888)
            try {
                for (progress in listOf(.9f, .65f, .5f, .35f, .1f)) {
                    frame.eraseColor(Color.TRANSPARENT)
                    assertTrue(renderer.prepare(progress, false))
                    renderer.draw(Canvas(frame), progress)
                    var ink = 0
                    for (y in 0 until frame.height) for (x in 0 until frame.width) {
                        if (Color.alpha(frame.getPixel(x, y)) == 0) continue
                        ink++
                        assertTrue("Date crossed the paragraph at $progress: $x/$y",
                            anchors.any { it.contains(x.toFloat(), y.toFloat()) })
                    }
                    if (progress != .5f) assertTrue("Date failed to fade in at its own anchor", ink > 0)
                }
            } finally {
                renderer.clear()
                frame.recycle()
            }
        }
    }

    @Test
    fun textTransitionReusesGlyphSnapshotsAndSupportsOffsetDatesAndCustomColors() = scene { s ->
        for (index in listOf(0, 6)) {
            s.onMain { manager.restoreState(index, true) }
            s.await { s.holder(index).presentation.expansionProgress == 1f }
            s.onMain {
                val card = holder(index)
                val renderer = CardTextTransition(card.presentation)
                try {
                    assertTrue(renderer.prepare(.8f, false))
                    val count = renderer.snapshotCount
                    val shared = renderer.sharedRunCount
                    assertTrue(shared > 0)
                    for (progress in listOf(.6f, .4f, .2f, .4f, .6f)) {
                        assertTrue(renderer.prepare(progress, false))
                        assertEquals(count, renderer.snapshotCount)
                        assertEquals(shared, renderer.sharedRunCount)
                    }
                    val custom = Color.rgb(220, 80, 105)
                    card.snippet.setTextColor(custom)
                    assertTrue(renderer.prepare(.5f, false))
                    assertTrue(renderer.snapshotCount > count)
                    assertEquals(custom, card.snippet.currentTextColor)
                    assertFalse(renderer.prepare(1f, false))
                    assertEquals(1f, card.snippet.alpha, 0f)
                    assertFalse(renderer.prepare(0f, false))
                    assertEquals(1f, card.previewSnippet.alpha, 0f)
                    assertFalse(renderer.prepare(.5f, true))
                } finally {
                    renderer.clear()
                }
            }
        }
    }

    @Test
    fun sharedGlyphFramesRetraceExactlyWhenTheDragReverses() = scene { s ->
        s.onMain { manager.restoreState(0, true) }
        s.await { s.holder(0).presentation.expansionProgress == 1f }
        s.onMain {
            val card = holder(0)
            val renderer = CardTextTransition(card.presentation)
            val bitmap = Bitmap.createBitmap(card.itemView.width, card.itemView.height, Bitmap.Config.ARGB_8888)
            fun pixels(progress: Float): IntArray {
                bitmap.eraseColor(Color.TRANSPARENT)
                assertTrue(renderer.prepare(progress, false))
                renderer.draw(Canvas(bitmap), progress)
                checkTextReference(bitmap, "shared-$progress")
                return IntArray(bitmap.width * bitmap.height).also {
                    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                }
            }
            try {
                val progress = listOf(.9f, .6f, .3f, .18f, .08f)
                val frames = progress.map { pixels(it) }
                val snapshots = renderer.snapshotCount
                for (index in progress.indices.reversed()) {
                    assertArrayEquals(frames[index], pixels(progress[index]))
                }
                assertEquals(snapshots, renderer.snapshotCount)
                card.previewText.rotationX = -22f
                card.previewText.rotationY = 4f
                pixels(.46f)
            } finally {
                renderer.clear()
                bitmap.recycle()
            }
        }
    }

    @Test
    fun textTransitionUsesTheLatestTransformsWithoutPreparingSnapshotsAgain() = scene { s ->
        s.onMain { manager.restoreState(0, true) }
        s.await { s.holder(0).presentation.expansionProgress == 1f }
        s.onMain {
            val card = holder(0)
            val renderer = CardTextTransition(card.presentation)
            val frame = Bitmap.createBitmap(card.itemView.width, card.itemView.height, Bitmap.Config.ARGB_8888)
            fun pixels(): IntArray {
                frame.eraseColor(Color.TRANSPARENT)
                renderer.draw(Canvas(frame), .46f)
                return IntArray(frame.width * frame.height).also {
                    frame.getPixels(it, 0, frame.width, 0, 0, frame.width, frame.height)
                }
            }
            try {
                assertTrue(renderer.prepare(.46f, false))
                val original = pixels()
                val snapshots = renderer.snapshotCount
                card.previewText.rotationX = -29f
                card.previewText.rotationY = 7f
                card.previewText.translationY -= dp(8)
                val transformed = pixels()
                assertFalse("The renderer kept stale text transforms", original.contentEquals(transformed))
                assertTrue(renderer.prepare(.46f, false))
                assertArrayEquals(transformed, pixels())
                assertEquals(snapshots, renderer.snapshotCount)
            } finally {
                renderer.clear()
                frame.recycle()
            }
        }
    }

    @Test
    fun wrappedChineseEmojiAndArabicShareTheirShapedGlyphs() = scene { s ->
        s.onMain {
            val items = (0 until adapter.itemCount).map { checkNotNull(adapter.getItemAt(it)) }.toMutableList()
            items[7] = items[7].copy(title = "清晨 🌄", snippet = "山间清晨 🌄 مرحبا بالعالم\n".repeat(12))
            adapter.submitList(items) { manager.restoreState(7, true) }
        }
        s.await {
            (s.recycler.findViewHolderForAdapterPosition(7) as? CardsAdapter.VH)
                ?.presentation?.expansionProgress == 1f
        }
        s.onMain {
            val card = holder(7)
            val renderer = CardTextTransition(card.presentation)
            val frame = Bitmap.createBitmap(card.itemView.width, card.itemView.height, Bitmap.Config.ARGB_8888)
            try {
                var snapshots: Int? = null
                val pixels = IntArray(frame.width * frame.height)
                for (progress in listOf(.99f, .75f, .5f, .18f, .01f)) {
                    frame.eraseColor(Color.TRANSPARENT)
                    assertTrue(renderer.prepare(progress, false))
                    assertTrue(renderer.sharedRunCount > 0)
                    if (snapshots == null) snapshots = renderer.snapshotCount
                    assertEquals(snapshots, renderer.snapshotCount)
                    renderer.draw(Canvas(frame), progress)
                    if (progress == .18f) checkTextReference(frame, "unicode")
                    frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                    assertTrue("Shaped lettering disappeared at $progress", pixels.any { Color.alpha(it) > 230 })
                }
            } finally {
                renderer.clear()
                frame.recycle()
            }
        }
    }

    private fun checkTextReference(frame: Bitmap, label: String) {
        val mode = InstrumentationRegistry.getArguments().getString("textReferenceMode") ?: return
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "card-text-reference-$label.png")
        when (mode) {
            "record" -> file.outputStream().use {
                assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            "compare" -> {
                val reference = checkNotNull(BitmapFactory.decodeFile(file.path)) {
                    "Missing same-device text reference: ${file.name}"
                }
                try {
                    assertTrue("Rendering changed at $label", reference.sameAs(frame))
                } finally {
                    reference.recycle()
                }
            }
            else -> error("Unknown text reference mode: $mode")
        }
    }

    private fun scene(allActions: Boolean = false, test: (Scene) -> Unit) {
        ActivityScenario.launch(TimescapeFlowTestActivity::class.java).use { scenario ->
            lateinit var scene: Scene
            scenario.onActivity { activity ->
                scene = Scene(activity, allActions)
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

    private fun boundsInAncestor(view: View, ancestor: View): RectF {
        val bounds = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        var descendant = view
        while (descendant !== ancestor) {
            descendant.matrix.mapRect(bounds)
            val parent = descendant.parent as View
            bounds.offset((descendant.left - parent.scrollX).toFloat(), (descendant.top - parent.scrollY).toFloat())
            descendant = parent
        }
        return bounds
    }

    private fun projectedCorners(view: View): FloatArray {
        val corners = floatArrayOf(
            0f, 0f, view.width.toFloat(), 0f,
            view.width.toFloat(), view.height.toFloat(), 0f, view.height.toFloat()
        )
        (view as? TimescapeCardLayout)?.mapCornerMorph(corners)
        view.matrix.mapPoints(corners)
        for (point in 0..3) {
            corners[point * 2] += view.left
            corners[point * 2 + 1] += view.top
        }
        return corners
    }

    private fun capturePerspective(scene: Scene, label: String, indices: IntRange = 0..1) {
        if (InstrumentationRegistry.getArguments().getString("capturePerspective") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        awaitPresented(scene)
        val frame = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(scene.root.context.cacheDir, "card-perspective-$label.png").outputStream().use {
                assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            frame.recycle()
        }
        scene.onMain {
            val cards = JSONArray()
            for (index in indices) {
                val card = holder(index).itemView
                cards.put(JSONObject().apply {
                    put("index", index)
                    put("opening", holder(index).presentation.expansionProgress)
                    put("textSnapshots", holder(index).presentation.textTransitionSnapshotCount)
                    put("sharedTextRuns", holder(index).presentation.sharedTextRunCount)
                    put("readingTextAlpha", holder(index).snippet.alpha)
                    put("previewTextAlpha", holder(index).previewSnippet.alpha)
                    put("rotationX", card.rotationX)
                    put("rotationY", card.rotationY)
                    put("rotationZ", card.rotation)
                    put("layoutWidth", card.width)
                    put("layoutHeight", card.height)
                    put("scaleX", card.scaleX)
                    put("scaleY", card.scaleY)
                    put("cameraDistance", card.cameraDistance)
                    put("pivotX", card.pivotX)
                    put("pivotY", card.pivotY)
                    put("corners", JSONArray(projectedCorners(card).toList()))
                })
            }
            File(root.context.cacheDir, "card-perspective-$label.json").writeText(cards.toString())
        }
    }

    private fun awaitPresented(scene: Scene) {
        val displayed = CountDownLatch(1)
        scene.onMain { root.postOnAnimation { root.postOnAnimation { displayed.countDown() } } }
        assertTrue("Transition frame was not presented", displayed.await(5, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private class Scene(activity: TimescapeFlowTestActivity, allActions: Boolean) {
        val root = FrameLayout(activity)
        val recycler = RecyclerView(activity)
        val manager = RightRailFlowLayoutManager(dp(340), dp(360), dp(138), dp(8))
        val files = mutableListOf<File>()
        val actionClicks = mutableListOf<Pair<String, Long>>()
        val adapter = CardsAdapter(
            TintStyle.None,
            onItemClick = { manager.restoreState(it, false); manager.focus(it) },
            onItemDoubleClick = { _, _ -> },
            onItemLongPress = { _, _ -> false },
            onStickyNotesClick = { actionClicks += "sticky" to it.id },
            onCollectionClick = if (allActions) {
                { item, _ -> actionClicks += "collection" to item.id }
            } else null,
            onReceiptClick = if (allActions) {
                { item, _ -> actionClicks += "receipt" to item.id }
            } else null,
            onTitleSpeakClick = if (allActions) {
                { item -> actionClicks += "speak" to item.id }
            } else null,
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
            val longImage = photo(300, 2400)
            val handwrittenPortrait = File.createTempFile("timescape-handwritten-", ".png", activity.filesDir)
            portrait.copyTo(handwrittenPortrait, overwrite = true)
            files += handwrittenPortrait
            val options = HandwritingOptions(Color.WHITE, Color.BLACK, 2f, 300, 600,
                HandwritingFormat.PNG, HandwritingPaperStyle.PLAIN, HandwritingPenType.ROUND,
                10f, HandwritingEraserType.ROUND)
            recycler.layoutManager = manager
            recycler.itemAnimator = null
            recycler.overScrollMode = RecyclerView.OVER_SCROLL_NEVER
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
                CardItem(5, "Handwriting", "", handwriting = HandwritingContent(handwrittenPortrait.name, options)),
                CardItem(6, "Afternoon", "Light through the trees.", bg = BgImage.Res(R.drawable.bg_3)),
                CardItem(7, "", "A title-free thought.", bg = BgImage.Res(R.drawable.bg_2)),
                CardItem(8, "Plain text", "No photograph attached."),
                CardItem(9, "Long image", "", image = CardImage(Uri.fromFile(longImage)),
                    backSnippet = "Long image reverse")
            )) { manager.restoreState(0, false) }
        }

        fun longHandwriting(): HandwritingContent {
            val source = File(checkNotNull(adapter.getItemAt(8)?.image?.uri?.path))
            fun copyPage(): File {
                val file = File.createTempFile("timescape-handwritten-", ".png", root.context.filesDir)
                source.copyTo(file, overwrite = true)
                files += file
                return file
            }
            val options = checkNotNull(adapter.getItemAt(4)?.handwriting).options.copy(canvasHeight = 2400)
            return HandwritingContent(copyPage().name, options,
                back = HandwritingSide(copyPage().name, options.copy()))
        }

        fun longText(position: Int = 5) {
            onMain {
                val item = checkNotNull(adapter.getItemAt(position))
                fun paragraphs(face: String) = (1..100).joinToString("\n\n") {
                    "$face $it · 清晨的城市慢慢醒来。Read this paragraph, then continue down the same card."
                }
                item.snippet = paragraphs("Front")
                item.backSnippet = paragraphs("Back")
                adapter.notifyItemChanged(position)
                manager.restoreState(position, true)
            }
        }

        fun dp(value: Int) = (value * root.resources.displayMetrics.density).toInt()
        fun holder(position: Int) = recycler.findViewHolderForAdapterPosition(position) as CardsAdapter.VH
        fun openCard(index: Int) {
            onMain { manager.restoreState(index, true) }
            await {
                manager.isFocused(index) && recycler.findViewHolderForAdapterPosition(index) != null &&
                    holder(index).isFaceReady && !holder(index).itemView.isLayoutRequested &&
                    holder(index).presentation.expansionProgress == 1f
            }
        }
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

        fun swipe(view: View, x: Float, y: Float, endX: Float, endY: Float,
                  endAction: Int = MotionEvent.ACTION_UP) {
            val now = SystemClock.uptimeMillis()
            for (step in 0..9) {
                val action = when (step) {
                    0 -> MotionEvent.ACTION_DOWN
                    9 -> endAction
                    else -> MotionEvent.ACTION_MOVE
                }
                val fraction = minOf(step, 8) / 8f
                val event = MotionEvent.obtain(now, now + step * 30L, action,
                    x + (endX - x) * fraction, y + (endY - y) * fraction, 0)
                try {
                    view.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
    }
}
