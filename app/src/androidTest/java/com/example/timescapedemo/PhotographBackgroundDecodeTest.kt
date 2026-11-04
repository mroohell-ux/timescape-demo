package com.example.timescapedemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhotographBackgroundDecodeTest {
    @Test fun preparationKeepsFineDetailAndDoesNotChangeTheSource() {
        val source = checkerboard()
        val original = checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
        val photograph = BackgroundImageLoader.preparePhotograph(source)
        try {
            assertTrue(variance(source) > 10_000)
            assertTrue("Fine photograph detail was blurred", photograph.sameAs(source))
            assertTrue(source.sameAs(original))
        } finally {
            source.recycle()
            original.recycle()
            if (photograph !== source) photograph.recycle()
        }
    }

    @Test fun preparationKeepsPhotographShapesRecognizable() {
        val source = checkerboard(16)
        val photograph = BackgroundImageLoader.preparePhotograph(source)
        try {
            assertTrue("Photograph shapes were blurred away",
                variance(photograph) == variance(source))
        } finally {
            source.recycle()
            if (photograph !== source) photograph.recycle()
        }
    }

    @Test fun preparationBoundsMemoryAndKeepsOriginalColorAndAspectRatio() {
        val color = Color.rgb(214, 128, 62)
        val source = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val photograph = BackgroundImageLoader.preparePhotograph(source)
        try {
            assertEquals(384, photograph.width)
            assertEquals(768, photograph.height)
            assertTrue(photograph.allocationByteCount <= 768 * 768 * 4)
            assertEquals(color, photograph.getPixel(192, 384))
            assertEquals(color, source.getPixel(200, 400))
        } finally {
            source.recycle()
            if (photograph !== source) photograph.recycle()
        }
    }

    @Test fun transparentEdgesDoNotIntroduceDarkColorFringes() {
        val source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        Canvas(source).drawRect(0f, 0f, 64f, 128f, Paint().apply { color = Color.RED })
        val photograph = BackgroundImageLoader.preparePhotograph(source)
        try {
            val edge = photograph.getPixel(63, 64)
            assertEquals(255, Color.alpha(edge))
            assertEquals(255, Color.red(edge))
            assertEquals(0, Color.green(edge))
            assertEquals(0, Color.blue(edge))
            assertEquals(0, Color.alpha(photograph.getPixel(64, 64)))
            assertEquals(0, Color.alpha(photograph.getPixel(127, 64)))
        } finally {
            source.recycle()
            if (photograph !== source) photograph.recycle()
        }
    }

    @Test fun sharedPhotoCacheNeverReplacesOriginalMediaOrChangesTheFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("photo-cache-", ".png", context.cacheDir)
        val source = checkerboard()
        try {
            file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val original = file.readBytes()
            val uri = Uri.fromFile(file)
            val media = load(uri, false)
            val photograph = load(uri, true)
            assertNotSame(media, photograph)
            assertTrue(variance(media) > 10_000)
            assertTrue(media.sameAs(photograph))
            assertSame(photograph, load(uri, true))
            assertSame(media, load(uri, false))
            assertArrayEquals(original, file.readBytes())
            BackgroundImageLoader.invalidate(uri)
        } finally {
            source.recycle()
            assertTrue(file.delete())
        }
    }

    @Test fun tallPhotoDecodeBoundsTheLongEdgeWithoutDistortingOrMutatingIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("photo-tall-", ".png", context.cacheDir)
        val source = Bitmap.createBitmap(128, 4096, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(214, 128, 62))
        }
        val uri = Uri.fromFile(file)
        try {
            file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val original = file.readBytes()
            val photograph = load(uri, true)
            assertEquals(24, photograph.width)
            assertEquals(768, photograph.height)
            assertEquals(source.getPixel(64, 2048), photograph.getPixel(12, 384))
            assertArrayEquals(original, file.readBytes())
        } finally {
            BackgroundImageLoader.invalidate(uri)
            source.recycle()
            assertTrue(file.delete())
        }
    }

    private fun load(uri: Uri, photograph: Boolean): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        var result: BackgroundImageLoader.Result? = null
        instrumentation.runOnMainSync {
            val view = ImageView(instrumentation.targetContext)
            BackgroundImageLoader.load(
                instrumentation.targetContext, view, uri, 320, 320, photographicBackground = photograph
            ) {
                result = it
                done.countDown()
            }
        }
        assertTrue("Background decode timed out", done.await(10, TimeUnit.SECONDS))
        assertTrue("Background decode failed: $result", result is BackgroundImageLoader.Result.Success)
        return (result as BackgroundImageLoader.Result.Success).bitmap
    }

    private fun checkerboard(cellSize: Int = 4): Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(width * height) { index ->
            if ((index % width / cellSize + index / width / cellSize) % 2 == 0) Color.BLACK else Color.WHITE
        }
        setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun variance(bitmap: Bitmap): Double {
        val values = mutableListOf<Int>()
        for (y in 16 until bitmap.height - 16) for (x in 16 until bitmap.width - 16) {
            values += Color.red(bitmap.getPixel(x, y))
        }
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }
}
