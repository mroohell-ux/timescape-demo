package com.example.timescapedemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class FlowWallpaperPresetTest {
    private val context get() = ContextThemeWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_TimescapeDemo
    )

    @Test fun palettesOnlyRemapColorWithoutChangingGeometryAlphaOrTheLightCore() {
        val peaks = mutableSetOf<Int>()
        for (preset in FlowWallpaperPreset.entries) {
            val drawable = preset.drawable(context)
            assertFalse(drawable is Animatable)
            val palette = drawable.colorFilter
            drawable.clearColorFilter()
            val blue = render(drawable)
            drawable.colorFilter = palette
            val frame = render(drawable)
            try {
                capture(frame, preset.key)
                for (y in 0 until frame.height step 3) for (x in 0 until frame.width step 3) {
                    val source = blue.getPixel(x, y)
                    val expected = recolored(source, preset)
                    val actual = frame.getPixel(x, y)
                    assertEquals(Color.alpha(source), Color.alpha(actual))
                    for (channel in listOf(Color::red, Color::green, Color::blue)) {
                        assertTrue("Unexpected ${preset.key} color at $x,$y: " +
                            "${Integer.toHexString(source)} -> ${Integer.toHexString(expected)}, " +
                            "got ${Integer.toHexString(actual)}",
                            abs(channel(expected) - channel(actual)) <= 2)
                    }
                }
                peaks.add(frame.getPixel(180, 400))
            } finally {
                frame.recycle()
                blue.recycle()
            }
        }
        assertEquals("Each preset must have a distinct palette", 5, peaks.size)
    }

    @Test fun portraitLandscapeAndSquarePalettesCoverEveryEdge() {
        for (preset in FlowWallpaperPreset.entries) {
            val drawable = preset.drawable(context)
            for ((width, height) in listOf(160 to 360, 360 to 160, 240 to 240)) {
                val frame = render(drawable, width, height)
                try {
                    for (y in 0 until height step 5) for (x in 0 until width step 5) {
                        assertEquals(255, Color.alpha(frame.getPixel(x, y)))
                    }
                    for (y in listOf(0, height - 1)) for (x in 0 until width) {
                        val pixel = frame.getPixel(x, y)
                        assertTrue(maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) <= 18)
                    }
                } finally {
                    frame.recycle()
                }
            }
        }
    }

    @Test fun changingAPresetNeverRecolorsOtherDrawableInstances() {
        val blueDrawable = FlowWallpaperPreset.BLUE.drawable(context)
        val before = render(blueDrawable)
        try {
            FlowWallpaperPreset.entries.forEach { preset ->
                val drawable = preset.drawable(context)
                drawable.alpha = 128
                val output = render(drawable)
                try {
                    assertEquals(128, Color.alpha(output.getPixel(180, 400)))
                } finally {
                    output.recycle()
                }
            }
            val after = render(blueDrawable)
            try {
                assertTrue(before.sameAs(after))
            } finally {
                after.recycle()
            }
        } finally {
            before.recycle()
        }
    }

    @Test fun defaultMatchesTheStartupResourceAndStableKeysRoundTrip() {
        val resource = render(requireNotNull(ContextCompat.getDrawable(context, R.drawable.bg_app_cosmic_flow)))
        val blue = render(FlowWallpaperPreset.BLUE.drawable(context))
        try {
            assertTrue(resource.sameAs(blue))
            assertEquals(5, FlowWallpaperPreset.entries.map { it.key }.toSet().size)
            for (preset in FlowWallpaperPreset.entries) {
                assertEquals(preset, FlowWallpaperPreset.fromKey(preset.key))
                assertTrue(context.getString(preset.titleRes).isNotBlank())
            }
            assertNull(FlowWallpaperPreset.fromKey("unknown"))
        } finally {
            resource.recycle()
            blue.recycle()
        }
    }

    private fun recolored(pixel: Int, preset: FlowWallpaperPreset): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return when (preset) {
            FlowWallpaperPreset.BLUE -> pixel
            FlowWallpaperPreset.GOLD -> Color.rgb(b, (.35f * r + .65f * g).roundToInt(), r)
            FlowWallpaperPreset.PURPLE -> Color.rgb((.3f * r + .7f * b).roundToInt(), r, b)
            FlowWallpaperPreset.GREEN -> Color.rgb(r, b, (.7f * r + .3f * g).roundToInt())
            FlowWallpaperPreset.ROSE -> Color.rgb(b, r, (.3f * r + .7f * g).roundToInt())
        }
    }

    private fun render(drawable: Drawable, width: Int = 360, height: Int = 800): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun capture(bitmap: Bitmap, key: String) {
        if (InstrumentationRegistry.getArguments().getString("captureFlowWallpapers") != "true") return
        File(context.cacheDir, "flow-wallpaper-$key.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
