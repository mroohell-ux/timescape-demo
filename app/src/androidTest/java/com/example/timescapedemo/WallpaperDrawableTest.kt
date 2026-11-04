package com.example.timescapedemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WallpaperDrawableTest {
    @Test fun centeredCoverFillsPortraitAndLandscapeAndUpdatesAfterResize() {
        val source = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        val canvas = Canvas(source)
        for ((color, left, right) in listOf(
            Triple(Color.RED, 0f, 200f), Triple(Color.GREEN, 200f, 400f), Triple(Color.BLUE, 400f, 600f)
        )) {
            paint.color = color
            canvas.drawRect(left, 0f, right, 300f, paint)
        }
        val wallpaper = WallpaperDrawable(source)
        try {
            for (bounds in listOf(Rect(15, 20, 315, 620), Rect(15, 20, 615, 320), Rect(15, 20, 165, 320))) {
                val output = Bitmap.createBitmap(bounds.right + 10, bounds.bottom + 10, Bitmap.Config.ARGB_8888)
                try {
                    output.eraseColor(Color.MAGENTA)
                    wallpaper.bounds = bounds
                    wallpaper.draw(Canvas(output))
                    assertEquals(Color.MAGENTA, output.getPixel(0, 0))
                    assertEquals(Color.GREEN, output.getPixel(bounds.centerX(), bounds.centerY()))
                    if (bounds.height() > bounds.width()) {
                        for (x in listOf(bounds.left, bounds.right - 1)) {
                            for (y in listOf(bounds.top, bounds.bottom - 1)) {
                                assertEquals(Color.GREEN, output.getPixel(x, y))
                            }
                        }
                    } else {
                        assertEquals(Color.RED, output.getPixel(bounds.left, bounds.centerY()))
                        assertEquals(Color.BLUE, output.getPixel(bounds.right - 1, bounds.centerY()))
                    }
                } finally {
                    output.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }

    @Test fun screenshotMatteAndSmallSystemGlyphsAreRemovedOnlyFromDisplay() {
        val source = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLACK)
        val canvas = Canvas(source)
        val paint = Paint().apply { color = Color.CYAN }
        canvas.drawRect(0f, 50f, 320f, 600f, paint)
        paint.color = Color.WHITE
        canvas.drawRect(20f, 10f, 180f, 20f, paint)
        canvas.drawRect(50f, 623f, 270f, 627f, paint)
        val original = checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
        val wallpaper = WallpaperDrawable(source)
        val output = Bitmap.createBitmap(300, 600, Bitmap.Config.ARGB_8888)
        try {
            assertEquals(Rect(0, 50, 320, 600), WallpaperDrawable.contentBounds(source))
            assertEquals(320, wallpaper.bitmap.width)
            assertEquals(550, wallpaper.bitmap.height)
            assertTrue(source.sameAs(original))
            wallpaper.setBounds(0, 0, output.width, output.height)
            wallpaper.draw(Canvas(output))
            for (x in 0 until output.width) {
                assertEquals(Color.CYAN, output.getPixel(x, 0))
                assertEquals(Color.CYAN, output.getPixel(x, output.height - 1))
            }
        } finally {
            source.recycle()
            original.recycle()
            wallpaper.bitmap.recycle()
            output.recycle()
        }
    }

    @Test fun sideMatteIsRemovedButAllDarkAndBroadDarkImagesArePreserved() {
        val source = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.BLACK)
            assertEquals(Rect(0, 0, 400, 300), WallpaperDrawable.contentBounds(source))
            Canvas(source).drawRect(30f, 0f, 370f, 300f, Paint().apply { color = Color.GREEN })
            assertEquals(Rect(30, 0, 370, 300), WallpaperDrawable.contentBounds(source))
            source.eraseColor(Color.BLACK)
            Canvas(source).drawRect(0f, 100f, 400f, 200f, Paint().apply { color = Color.GREEN })
            assertEquals(Rect(0, 0, 400, 300), WallpaperDrawable.contentBounds(source))
        } finally {
            source.recycle()
        }
    }

    @Test fun albumDecodeHonorsOrientationAndBoundsMemoryWithoutChangingTheFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("wallpaper-orientation-", ".jpg", context.cacheDir)
        val source = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        try {
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val original = file.readBytes()
            val wallpaper = WallpaperDrawable.fromUri(context, Uri.fromFile(file), 150)
            try {
                assertEquals(50, wallpaper.bitmap.width)
                assertEquals(150, wallpaper.bitmap.height)
                assertArrayEquals(original, file.readBytes())
            } finally {
                wallpaper.bitmap.recycle()
            }
        } finally {
            source.recycle()
            assertTrue(file.delete())
        }
    }
}
