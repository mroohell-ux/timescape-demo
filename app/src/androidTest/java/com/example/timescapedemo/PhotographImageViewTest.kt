package com.example.timescapedemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class PhotographImageViewTest {
    @Test fun glassPhotoFillsTheWholeRectangleWithCenterCrop() {
        val source = Bitmap.createBitmap(720, 240, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.GREEN)
            drawRect(0f, 0f, 240f, 240f, Paint().apply { color = Color.RED })
            drawRect(480f, 0f, 720f, 240f, Paint().apply { color = Color.BLUE })
        }
        val rendered = renderGlass(source, 128, 192)
        try {
            for (y in 0 until rendered.height) for (x in 0 until rendered.width) {
                assertTrue("Photo coverage/transparency changed at $x,$y",
                    abs(Color.alpha(rendered.getPixel(x, y)) - glassAlpha(x, rendered.width)) <= 2)
            }
            for (x in listOf(0, 12, 64, 115, 127)) {
                val color = rendered.getPixel(x, 12)
                assertTrue("CenterCrop exposed a side band", Color.green(color) > Color.red(color) + 100)
            }
        } finally {
            rendered.recycle()
            source.recycle()
        }
    }

    @Test fun glassPhotoKeepsVividColorWithSlightGlassSoftening() {
        val colors = intArrayOf(Color.rgb(220, 70, 45), Color.BLUE,
            Color.rgb(64, 64, 64), Color.rgb(224, 224, 224))
        val source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(128 * 128) { colors[it / 128 / 32] }, 0, 128, 0, 0, 128, 128)
        }
        val rendered = renderGlass(source, 128, 128, glassBase = false)
        try {
            val red = rendered.getPixel(64, 16)
            val chromaRatio = (Color.red(red) - Color.green(red)) / 150f
            assertTrue("Expected 90% saturation and 96% contrast: $chromaRatio", chromaRatio in .85f..0.88f)
            val contrast = Color.red(rendered.getPixel(64, 112)) - Color.red(rendered.getPixel(64, 80))
            assertTrue("Expected 96% contrast, got $contrast", contrast in 152..155)
        } finally {
            rendered.recycle()
            source.recycle()
        }
    }

    @Test fun glassPhotoFadesOnlySideBandsWithoutAMilkyVerticalGradient() {
        val source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(128 * 128) {
                if (it % 128 / 32 % 2 == 0) Color.rgb(240, 240, 240) else Color.rgb(16, 16, 16)
            }, 0, 128, 0, 0, 128, 128)
        }
        val rendered = renderGlass(source, 128, 128)
        try {
            fun detail(y: Int) = Color.red(rendered.getPixel(80, y)) - Color.red(rendered.getPixel(48, y))
            val top = detail(12)
            val middle = detail(64)
            val bottom = detail(115)
            assertTrue("Photo detail was washed away: $top", top > 180)
            assertEquals(top, middle)
            assertEquals(top, bottom)
            for (y in 0 until rendered.height) for (x in 0 until rendered.width) {
                val pixel = rendered.getPixel(x, y)
                // Translucent 8-bit premultiplied colors can differ by two RGB levels.
                assertColorNear(rendered.getPixel(x, 64), pixel, 2)
                assertTrue("Photo side transparency is incorrect at $x,$y",
                    abs(Color.alpha(pixel) - glassAlpha(x, rendered.width)) <= 2)
            }
        } finally {
            rendered.recycle()
            source.recycle()
        }
    }

    @Test fun glassPhotoSoftensTheCenterByTwoDpInSoftwareSnapshots() {
        val source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
            Canvas(this).drawRect(64f, 0f, 65f, 128f, Paint().apply { color = Color.WHITE })
        }
        val rendered = renderGlass(source, 128, 128, glassBase = false)
        try {
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val radius = sqrt(horizontalVariance(rendered, 64, 64, 16)).toFloat()
            assertEquals("The central softening must be 2dp", 2f * density, radius, .25f)
        } finally {
            rendered.recycle()
            source.recycle()
        }
    }

    @Test fun glassPhotoSoftensBothCenterAndSidesWithoutChangingTheirOpacity() {
        val source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(128 * 128) {
                if (it % 128 / 2 % 2 == 0) Color.BLACK else Color.WHITE
            }, 0, 128, 0, 0, 128, 128)
        }
        val sharp = renderGlass(source, 256, 128, glassBase = false, soften = false)
        val softened = renderGlass(source, 256, 128, glassBase = false)
        try {
            fun detail(bitmap: Bitmap, range: IntRange) = range.sumOf {
                abs(Color.red(bitmap.getPixel(it + 1, 64)) - Color.red(bitmap.getPixel(it, 64)))
            }
            val edge = detail(softened, 4..40)
            val original = detail(sharp, 4..40)
            assertTrue("Side detail was not softened: $edge / $original", edge < original * .9f)
            assertTrue("The central detail was not softened",
                detail(softened, 104..150) < detail(sharp, 104..150) * .95f)
            for (x in 0 until softened.width) {
                assertTrue("Softening changed opacity beyond 8-bit rounding at $x",
                    abs(Color.alpha(sharp.getPixel(x, 64)) - Color.alpha(softened.getPixel(x, 64))) <= 1)
            }
            assertTrue(Color.alpha(softened.getPixel(3, 64)) < Color.alpha(softened.getPixel(30, 64)))
            assertTrue(Color.alpha(softened.getPixel(30, 64)) < Color.alpha(softened.getPixel(80, 64)))
        } finally {
            sharp.recycle()
            softened.recycle()
            source.recycle()
        }
    }

    @Test fun transparentPhotoPixelsRevealTheGlassWithoutChangingTheSource() {
        val source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        Canvas(source).drawRect(0f, 0f, 64f, 128f, Paint().apply { color = Color.RED })
        val rendered = renderGlass(source, 128, 128)
        try {
            assertColorNear(0x283B8EBD, rendered.getPixel(96, 64), 2)
            assertTrue(Color.alpha(rendered.getPixel(32, 64)) in 194..196)
            assertEquals(Color.RED, source.getPixel(32, 64))
            assertEquals(Color.TRANSPARENT, source.getPixel(96, 64))
        } finally {
            rendered.recycle()
            source.recycle()
        }
    }

    @Test fun glassPhotoHardwareMatchesSoftwareFlipSnapshots() = scene { s ->
        s.onMain {
            photo.imageAlpha = 255
            photo.alpha = 1f
            TextCardPhotoStyle.applyTo(photo)
            photo.layoutParams = photo.layoutParams.apply { height = width * 3 / 2 }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        lateinit var snapshot: Bitmap
        val location = IntArray(2)
        s.onMain {
            photo.getLocationInWindow(location)
            snapshot = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
            photo.draw(Canvas(snapshot))
        }
        val frame = s.capture()
        val blackBlue = Color.blue(s.pixel(frame, s.photo, .25f))
        try {
            for (fx in listOf(.01f, .15f, .5f, .85f, .99f)) {
                for (fy in listOf(.01f, .25f, .5f, .75f, .99f)) {
                    val x = (snapshot.width * fx).roundToInt()
                    val y = (snapshot.height * fy).roundToInt()
                    assertColorNear(ColorUtils.compositeColors(snapshot.getPixel(x, y), Color.BLACK),
                        frame.getPixel(location[0] + x, location[1] + y), 3)
                }
            }
        } finally {
            frame.recycle()
            snapshot.recycle()
        }
        s.onMain { (photo.parent as View).setBackgroundColor(Color.BLUE) }
        val blueBackdrop = s.capture()
        try {
            val alpha = .72f + (40f / 255f) * (1f - .72f)
            val difference = Color.blue(s.pixel(blueBackdrop, s.photo, .25f)) -
                blackBlue
            assertTrue("The underlying flow is not showing through: $difference",
                abs(difference - 255f * (1f - alpha)) <= 3f)
        } finally {
            blueBackdrop.recycle()
        }
    }

    @Test fun patternedSofteningMatchesHardwareAndSoftwareSnapshots() = scene { s ->
        s.onMain {
            source.setPixels(IntArray(source.width * source.height) {
                if (it % source.width / 3 % 2 == 0) Color.BLACK else Color.WHITE
            }, 0, source.width, 0, 0, source.width, source.height)
            photo.imageAlpha = 255
            photo.alpha = 1f
            TextCardPhotoStyle.applyTo(photo)
            photo.invalidate()
        }
        val frame = s.capture()
        lateinit var snapshot: Bitmap
        val location = IntArray(2)
        s.onMain {
            photo.getLocationInWindow(location)
            snapshot = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
            photo.draw(Canvas(snapshot))
        }
        try {
            for (fraction in listOf(.03f, .10f, .20f, .5f, .80f, .90f, .97f)) {
                val x = (snapshot.width * fraction).roundToInt()
                val y = snapshot.height / 2
                assertColorNear(ColorUtils.compositeColors(snapshot.getPixel(x, y), Color.BLACK),
                    frame.getPixel(location[0] + x, location[1] + y), 6)
            }
        } finally {
            frame.recycle()
            snapshot.recycle()
        }
    }

    @Test fun glassPhotoSoftensTheCenterByTwoDpOnHardware() = scene { s ->
        s.onMain {
            source.eraseColor(Color.BLACK)
            Canvas(source).drawRect(48f, 0f, 49f, source.height.toFloat(),
                Paint().apply { color = Color.WHITE })
            for (image in listOf(photo, sharp)) {
                image.imageAlpha = 255
                image.alpha = 1f
                TextCardPhotoStyle.applyTo(image)
                image.invalidate()
            }
        }
        val frame = s.capture()
        try {
            s.onMain {
                val density = photo.resources.displayMetrics.density
                fun variance(image: ImageView): Double {
                    val location = IntArray(2)
                    image.getLocationInWindow(location)
                    return horizontalVariance(frame, location[0] + image.width / 2,
                        location[1] + image.height / 2, (12 * density).roundToInt())
                }
                val addedVariance = variance(photo) - variance(sharp)
                assertTrue("The hardware center has no additional softening", addedVariance > 0)
                assertEquals("The hardware central softening must be 2dp", 2f * density,
                    sqrt(addedVariance).toFloat(), .25f)
            }
        } finally {
            frame.recycle()
        }
    }

    private fun horizontalVariance(bitmap: Bitmap, x: Int, y: Int, radius: Int): Double {
        val baseline = Color.red(bitmap.getPixel(x - radius, y))
        var mass = 0.0
        var firstMoment = 0.0
        var secondMoment = 0.0
        for (offset in -radius..radius) {
            val weight = (Color.red(bitmap.getPixel(x + offset, y)) - baseline).coerceAtLeast(0)
            mass += weight
            firstMoment += weight * offset
            secondMoment += weight * offset.toDouble() * offset
        }
        assertTrue("The test impulse is not visible", mass > 100)
        val mean = firstMoment / mass
        return secondMoment / mass - mean * mean
    }

    private fun glassAlpha(x: Int, width: Int): Int {
        val distance = minOf(x + .5f, width - x - .5f) / width
        val photo = .72f * (.20f + .80f * (distance / .24f).coerceIn(0f, 1f))
        return ((photo + 40f / 255f * (1f - photo)) * 255f).roundToInt()
    }

    private fun renderGlass(
        source: Bitmap, width: Int, height: Int, glassBase: Boolean = true, soften: Boolean = true
    ): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync {
            val image = PhotographImageView(instrumentation.targetContext)
            image.setImageBitmap(source)
            TextCardPhotoStyle.applyTo(image)
            if (!soften) image.setSoftening(0f)
            if (!glassBase) image.background = null
            image.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            image.layout(0, 0, width, height)
            image.draw(Canvas(rendered))
            image.setImageDrawable(null)
        }
        return rendered
    }

    @Test fun hardwareSofteningPreservesCropColorAndTransparency() = scene { s ->
        val frame = s.capture()
        try {
            for (fraction in listOf(.2f, .8f)) {
                assertColorNear(s.pixel(frame, s.reference, fraction), s.pixel(frame, s.photo, fraction), 2)
            }
            for (offset in -7..7) {
                assertColorNear(
                    s.pixel(frame, s.reference, .5f, offset),
                    s.pixel(frame, s.photo, .5f, offset), 36
                )
            }
            assertTrue(
                s.pixel(frame, s.sharp, .5f, 3) != s.pixel(frame, s.photo, .5f, 3)
            )
        } finally {
            frame.recycle()
        }
    }

    @Test fun disabledSofteningAndSoftwareSnapshotsKeepOriginalImages() = scene { s ->
        s.onMain {
            val original = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
            val captured = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
            try {
                sharp.draw(Canvas(original))
                photo.draw(Canvas(captured))
                assertTrue("Software face snapshots changed", original.sameAs(captured))
            } finally {
                original.recycle()
                captured.recycle()
            }
            photo.setSoftening(0f)
        }
        val frame = s.capture()
        try {
            for (offset in -7..7) {
                assertColorNear(
                    s.pixel(frame, s.sharp, .5f, offset),
                    s.pixel(frame, s.photo, .5f, offset), 1
                )
            }
        } finally {
            frame.recycle()
        }
    }

    @Test fun fadingPhotographsKeepCenterDetailSharp() = scene { s ->
        s.onMain { photo.setFadingEdges(true) }
        val frame = s.capture()
        try {
            for (offset in -7..7) {
                assertColorNear(
                    s.pixel(frame, s.sharp, .5f, offset),
                    s.pixel(frame, s.photo, .5f, offset), 1
                )
            }
        } finally {
            frame.recycle()
        }
    }

    @Test fun sideFadingKeepsRectangularCoverageAndOriginalBrightCenter() = scene { s ->
        val faceColor = Color.rgb(214, 128, 62)
        s.onMain {
            source.eraseColor(faceColor)
            photo.colorFilter = null
            photo.imageAlpha = 255
            photo.alpha = 1f
            photo.setSoftening(0f)
            photo.setFadingEdges(true)
            photo.layoutParams = photo.layoutParams.apply { height = width * 3 / 2 }
            photo.invalidate()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        lateinit var snapshot: Bitmap
        s.onMain {
            snapshot = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
            photo.draw(Canvas(snapshot))
        }
        val frame = s.capture()
        try {
            val location = IntArray(2)
            s.onMain { photo.getLocationInWindow(location) }
            for ((fx, fy) in listOf(.5f to .5f, .25f to .5f, .03f to .5f, .5f to .03f,
                .03f to .03f, .97f to .97f, .5f to .97f)) {
                val x = (snapshot.width * fx).roundToInt()
                val y = (snapshot.height * fy).roundToInt()
                val faded = snapshot.getPixel(x, y)
                val hardware = frame.getPixel(location[0] + x, location[1] + y)
                val alpha = Color.alpha(faded) / 255f
                val composite = Color.rgb(
                    (Color.red(faded) * alpha).roundToInt(),
                    (Color.green(faded) * alpha).roundToInt(),
                    (Color.blue(faded) * alpha).roundToInt()
                )
                assertColorNear(composite, hardware, 3)
            }
            assertColorNear(faceColor, snapshot.getPixel(snapshot.width / 2, snapshot.height / 2), 1)
            assertColorNear(faceColor, snapshot.getPixel(snapshot.width / 2, 0), 1)
            assertColorNear(faceColor, snapshot.getPixel(snapshot.width / 2, snapshot.height - 1), 1)
            for (x in listOf(0, snapshot.width - 1)) {
                val sideAlpha = Color.alpha(snapshot.getPixel(x, snapshot.height / 2))
                assertTrue("Side should reveal the glass: $sideAlpha", sideAlpha in 51..55)
                for (y in listOf(0, snapshot.height / 4, snapshot.height - 1)) {
                    assertEquals("Side opacity varies vertically, making an oval",
                        sideAlpha, Color.alpha(snapshot.getPixel(x, y)))
                }
            }
            for (x in 0 until snapshot.width) {
                val position = (x + .5f) / snapshot.width
                val sideDistance = minOf(position, 1f - position)
                val expectedAlpha = ((.20f + .80f * (sideDistance / .24f).coerceIn(0f, 1f)) * 255f).roundToInt()
                val actualAlpha = Color.alpha(snapshot.getPixel(x, snapshot.height / 2))
                assertTrue("Unexpected side transition at pixel $x: $expectedAlpha vs $actualAlpha",
                    abs(expectedAlpha - actualAlpha) <= 1)
            }
        } finally {
            frame.recycle()
            snapshot.recycle()
        }
        s.onMain { photo.setFadingEdges(false) }
        val plain = s.capture()
        try {
            assertColorNear(faceColor, s.pixel(plain, s.photo, .03f), 1)
        } finally {
            plain.recycle()
        }
    }

    private fun assertColorNear(expected: Int, actual: Int, tolerance: Int) {
        for (channel in listOf<(Int) -> Int>(Color::red, Color::green, Color::blue, Color::alpha)) {
            assertTrue(
                "Expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
                abs(channel(expected) - channel(actual)) <= tolerance
            )
        }
    }

    private fun scene(test: (Scene) -> Unit) {
        ActivityScenario.launch(TimescapeFlowTestActivity::class.java).use { scenario ->
            lateinit var scene: Scene
            scenario.onActivity { scene = Scene(it) }
            try {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                test(scene)
            } finally {
                scenario.onActivity {
                    scene.photo.setImageDrawable(null)
                    scene.reference.setImageDrawable(null)
                    scene.sharp.setImageDrawable(null)
                    it.setContentView(LinearLayout(it))
                }
                scene.source.recycle()
            }
        }
    }

    private class Scene(val activity: TimescapeFlowTestActivity) {
        val source = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            Canvas(this).drawRect(48f, 0f, 96f, 48f, Paint().apply { color = Color.BLUE })
        }
        val photo = PhotographImageView(activity)
        val reference = ImageView(activity)
        val sharp = ImageView(activity)

        init {
            val density = activity.resources.displayMetrics.density
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.BLACK)
                setPadding((12 * density).roundToInt(), (24 * density).roundToInt(), 0, 0)
            }
            for (image in listOf(photo, reference, sharp)) {
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                image.setImageBitmap(source)
                image.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(.65f) })
                image.imageAlpha = 204
                image.alpha = .9f
                root.addView(image, LinearLayout.LayoutParams(
                    (96 * density).roundToInt(), (96 * density).roundToInt()
                ).apply { leftMargin = (4 * density).roundToInt() })
            }
            photo.setSoftening(.65f * density)
            reference.setRenderEffect(RenderEffect.createBlurEffect(
                .65f * density, .65f * density, Shader.TileMode.CLAMP
            ))
            activity.setContentView(root)
        }

        fun onMain(block: Scene.() -> Unit) =
            InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }

        fun capture(): Bitmap {
            SystemClock.sleep(120)
            lateinit var frame: Bitmap
            var result = -1
            val done = CountDownLatch(1)
            onMain {
                assertTrue(photo.isHardwareAccelerated)
                frame = Bitmap.createBitmap(
                    activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888
                )
                PixelCopy.request(activity.window, frame, {
                    result = it
                    done.countDown()
                }, Handler(Looper.getMainLooper()))
            }
            assertTrue("PixelCopy timed out", done.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            return frame
        }

        fun pixel(frame: Bitmap, image: ImageView, fraction: Float, offset: Int = 0): Int {
            val location = IntArray(2)
            var x = 0
            var y = 0
            onMain {
                image.getLocationInWindow(location)
                x = location[0] + (image.width * fraction).roundToInt() + offset
                y = location[1] + image.height / 2
            }
            return frame.getPixel(x, y)
        }
    }
}
