package com.example.timescapedemo

import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.hypot

internal fun projectedCardLetteringCorners(card: TimescapeCardLayout, ink: Boolean = false): FloatArray {
    val text = card.findViewById<View>(R.id.previewText)
    val bounds = RectF(0f, 0f, text.width.toFloat(), text.height.toFloat())
    if (ink) {
        bounds.setEmpty()
        val glyphBounds = RectF()
        val transform = Matrix()
        for (id in listOf(R.id.previewTitle, R.id.previewSnippet)) {
            val glyphs = card.findViewById<TimescapeTextView>(id)
            if (glyphs.visibility == View.GONE || glyphs.text.isEmpty()) continue
            glyphs.renderedGlyphBounds(text, glyphBounds, transform)
            bounds.union(glyphBounds)
        }
    }
    val points = floatArrayOf(bounds.left, bounds.top, bounds.right, bounds.top,
        bounds.right, bounds.bottom, bounds.left, bounds.bottom)
    val local = Matrix()
    text.renderedMatrixToAncestor(card, local)
    local.mapPoints(points)
    card.mapCornerMorph(points)
    val global = Matrix()
    card.renderedMatrixToAncestor(null, global)
    global.mapPoints(points)
    return points
}

internal fun assertScreenFacingCardLettering(card: TimescapeCardLayout) {
    val points = projectedCardLetteringCorners(card)
    assertEquals("Top edge is not screen-horizontal", points[1], points[3], .5f)
    assertEquals("Bottom edge is not screen-horizontal", points[5], points[7], .5f)
    assertEquals("Left edge is not screen-vertical", points[0], points[6], .5f)
    assertEquals("Right edge is not screen-vertical", points[2], points[4], .5f)
    assertTrue("Lettering was mirrored or collapsed", points[2] > points[0] && points[7] > points[1])

    val face = floatArrayOf(0f, 0f, card.width.toFloat(), 0f,
        card.width.toFloat(), card.height.toFloat(), 0f, card.height.toFloat())
    card.mapCornerMorph(face)
    val global = Matrix()
    card.renderedMatrixToAncestor(null, global)
    global.mapPoints(face)
    val ink = projectedCardLetteringCorners(card, ink = true)
    for (edge in 0..3) {
        val next = (edge + 1) % 4
        val dx = face[next * 2] - face[edge * 2]
        val dy = face[next * 2 + 1] - face[edge * 2 + 1]
        for (corner in 0..3) {
            val margin = dx * (ink[corner * 2 + 1] - face[edge * 2 + 1]) -
                dy * (ink[corner * 2] - face[edge * 2])
            assertTrue("Upright lettering crosses card edge $edge", margin >= -hypot(dx, dy) * .5f)
        }
    }
}
