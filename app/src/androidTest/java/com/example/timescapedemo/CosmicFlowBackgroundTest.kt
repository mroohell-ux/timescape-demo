package com.example.timescapedemo

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.widget.ImageView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import org.junit.Assert.*
import org.junit.Test

class CosmicFlowBackgroundTest {
    private val context get() = ContextThemeWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_TimescapeDemo
    )

    private fun wallpaper() = checkNotNull(ContextCompat.getDrawable(context, R.drawable.bg_app_cosmic_flow))

    private fun render(drawable: Drawable, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    @Test fun portraitLandscapeAndResizedBoundsStayFullyCovered() {
        val drawable = wallpaper()
        for (bounds in listOf(Rect(12, 18, 312, 718), Rect(12, 18, 712, 318), Rect(12, 18, 412, 418))) {
            val output = Bitmap.createBitmap(bounds.right + 8, bounds.bottom + 8, Bitmap.Config.ARGB_8888)
            try {
                output.eraseColor(Color.MAGENTA)
                drawable.bounds = bounds
                drawable.draw(Canvas(output))
                assertEquals(Color.MAGENTA, output.getPixel(0, 0))
                for (y in bounds.top until bounds.bottom step 7) {
                    for (x in bounds.left until bounds.right step 7) {
                        val pixel = output.getPixel(x, y)
                        assertEquals(255, Color.alpha(pixel))
                        assertTrue(Color.blue(pixel) >= Color.red(pixel))
                    }
                }
                for (y in listOf(bounds.top, bounds.bottom - 1)) {
                    for (x in bounds.left until bounds.right) {
                        val pixel = output.getPixel(x, y)
                        assertEquals(255, Color.alpha(pixel))
                        assertTrue(maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) <= 18)
                    }
                }
            } finally {
                output.recycle()
            }
        }
    }

    @Test fun ribbonsHaveCyanHighlightsAndShadowDepthWithoutAnimation() {
        val drawable = wallpaper()
        assertFalse(drawable is Animatable)
        val output = render(drawable, 540, 1200)
        try {
            var minimum = 1.0
            var maximum = 0.0
            for (y in 500 until 1080 step 2) {
                for (x in 0 until output.width step 2) {
                    val pixel = output.getPixel(x, y)
                    val luminance = ColorUtils.calculateLuminance(pixel)
                    minimum = minOf(minimum, luminance)
                    maximum = maxOf(maximum, luminance)
                }
            }
            assertTrue("Ribbon highlight/shadow contrast: ${maximum - minimum}", maximum - minimum >= .35)
            assertTrue(ColorUtils.calculateLuminance(output.getPixel(10, 10)) < .12)
        } finally {
            output.recycle()
        }
    }

    @Test fun nearBlackEndsFrameTheBrightCentralRibbons() {
        val output = render(wallpaper(), 540, 1200)
        try {
            var edgePeak = 0.0
            var ribbonPeak = 0.0
            var edgeSum = 0.0
            var ribbonSum = 0.0
            var edgeCount = 0
            var ribbonCount = 0
            for (y in 0 until output.height step 2) {
                for (x in 0 until output.width step 2) {
                    val luminance = ColorUtils.calculateLuminance(output.getPixel(x, y))
                    if (y < output.height * .12f || y > output.height * .88f) {
                        edgePeak = maxOf(edgePeak, luminance)
                        edgeSum += luminance
                        edgeCount++
                    } else if (y in 504..912) {
                        ribbonPeak = maxOf(ribbonPeak, luminance)
                        ribbonSum += luminance
                        ribbonCount++
                    }
                }
            }
            assertTrue("End-space luminance: $edgePeak", edgePeak <= .015)
            assertTrue("Central ribbon contrast: ${ribbonPeak - edgePeak}", ribbonPeak - edgePeak >= .35)
            assertTrue(ribbonSum / ribbonCount >= 10 * edgeSum / edgeCount)
        } finally {
            output.recycle()
        }
    }

    @Test fun startupRootAndDrawerPreviewShareTheDefaultAndLightChrome() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val themed = context
            val attributes = themed.obtainStyledAttributes(intArrayOf(
                android.R.attr.windowBackground,
                android.R.attr.windowLightStatusBar,
                android.R.attr.windowLightNavigationBar
            ))
            try {
                assertEquals(R.drawable.bg_app_cosmic_flow, attributes.getResourceId(0, 0))
                assertFalse(attributes.getBoolean(1, true))
                assertFalse(attributes.getBoolean(2, true))
            } finally {
                attributes.recycle()
            }
            val activity = themed.packageManager.getActivityInfo(ComponentName(themed, MainActivity::class.java), 0)
            assertEquals(R.style.Theme_TimescapeDemo, activity.theme)
            val root = LayoutInflater.from(themed).inflate(R.layout.activity_main, null)
            assertTrue(root.findViewById<MaterialToolbar>(R.id.toolbar).navigationIconTint == Color.WHITE)
            val drawer = LayoutInflater.from(themed).inflate(R.layout.drawer_backgrounds, null)
            val expected = render(wallpaper(), 160, 340)
            try {
                val drawables = listOf(
                    root.findViewById<android.view.View>(R.id.rootLayout).background,
                    drawer.findViewById<ImageView>(R.id.imageAppBackgroundPreview).drawable
                )
                for (drawable in drawables) {
                    val actual = render(checkNotNull(drawable), 160, 340)
                    try {
                        assertTrue(expected.sameAs(actual))
                    } finally {
                        actual.recycle()
                    }
                }
            } finally {
                expected.recycle()
            }
        }
    }

    @Test fun alphaIsAppliedToTheEntireComposition() {
        val drawable = wallpaper()
        drawable.alpha = 128
        val output = render(drawable, 270, 600)
        try {
            for ((x, y) in listOf(10 to 10, 135 to 350, 250 to 580)) {
                assertEquals(128, Color.alpha(output.getPixel(x, y)))
            }
        } finally {
            output.recycle()
        }
    }
}
