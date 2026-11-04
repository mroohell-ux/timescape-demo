package com.example.timescapedemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardEdgeDrawableTest {
    @Test
    fun glassHasLitRimsAndSideReflectionsButKeepsItsCenterTransparent() {
        val bitmap = Bitmap.createBitmap(480, 280, Bitmap.Config.ARGB_8888)
        try {
            val edge = CardEdgeDrawable(4f, 8f, glass = true)
            edge.setBounds(0, 0, bitmap.width, bitmap.height)
            edge.draw(Canvas(bitmap))
            val upper = bitmap.getPixel(120, 0)
            val lower = bitmap.getPixel(240, 279)
            assertTrue(Color.alpha(upper) in 140..230)
            assertTrue(Color.alpha(lower) in 120..230)
            for (color in listOf(upper, lower)) {
                assertTrue(Color.red(color) >= 220)
                assertTrue(Color.green(color) >= 240)
                assertTrue(Color.blue(color) >= 245)
            }
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
            for (y in 7 until bitmap.height - 7) for (x in 90 until bitmap.width - 90) {
                assertEquals("Glass reflection washed over the clear center at $x,$y",
                    Color.TRANSPARENT, bitmap.getPixel(x, y))
            }
            assertTrue(Color.alpha(bitmap.getPixel(20, 140)) in 1..32)
            assertTrue(Color.alpha(bitmap.getPixel(460, 140)) in 1..32)
            edge.alpha = 0
            assertEquals(0, edge.alpha)
            bitmap.eraseColor(Color.TRANSPARENT)
            edge.draw(Canvas(bitmap))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(20, 140))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(120, 0))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun bottomLipHasAVisibleDarkSideAndFineLitRim() {
        val bitmap = Bitmap.createBitmap(240, 140, Bitmap.Config.ARGB_8888)
        try {
            val edge = CardEdgeDrawable(1f, 2f)
            edge.setBounds(0, 0, bitmap.width, bitmap.height)
            edge.draw(Canvas(bitmap))
            val side = bitmap.getPixel(120, 138)
            val rim = bitmap.getPixel(120, 139)
            assertTrue("Missing shallow dark side", Color.alpha(side) >= 120)
            assertTrue("Missing bright bottom rim", ColorUtils.calculateLuminance(rim) >
                ColorUtils.calculateLuminance(side) + .15)
            assertTrue(Color.blue(side) > Color.red(side))
            assertTrue(Color.alpha(bitmap.getPixel(120, 1)) < Color.alpha(side))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun edgeLeavesTheEntireFaceTransparentAndHonorsRoundedCornersAfterResize() {
        val edge = CardEdgeDrawable(2f, 40f)
        for ((width, height) in listOf(480 to 280, 720 to 440)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                edge.setBounds(0, 0, width, height)
                edge.draw(Canvas(bitmap))
                assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
                for (y in 48 until height - 48) {
                    for (x in 8 until width - 8) {
                        assertEquals("Edge painted the readable face at $x,$y",
                            Color.TRANSPARENT, bitmap.getPixel(x, y))
                    }
                }
                assertTrue(Color.alpha(bitmap.getPixel(width / 2, height - 2)) > 80)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun edgeAlphaDoesNotChangeGeometryOrLeaveOpaqueBacking() {
        val bitmap = Bitmap.createBitmap(240, 140, Bitmap.Config.ARGB_8888)
        try {
            val edge = CardEdgeDrawable(1f, 2f)
            edge.setBounds(0, 0, 240, 140)
            edge.alpha = 0
            edge.draw(Canvas(bitmap))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(120, 139))
            edge.alpha = 128
            edge.draw(Canvas(bitmap))
            val dimmed = Color.alpha(bitmap.getPixel(120, 139))
            bitmap.eraseColor(Color.TRANSPARENT)
            edge.alpha = 255
            edge.draw(Canvas(bitmap))
            assertTrue(Color.alpha(bitmap.getPixel(120, 139)) > dimmed)
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(120, 70))
        } finally {
            bitmap.recycle()
        }
    }
}
