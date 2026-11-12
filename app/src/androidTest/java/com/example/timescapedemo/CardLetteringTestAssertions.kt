package com.example.timescapedemo

import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout
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
}

internal fun assertNaturalCardLetteringScale(card: TimescapeCardLayout) {
    val text = card.findViewById<View>(R.id.previewText)
    val parent = text.parent as View
    val natural = Matrix()
    parent.renderedMatrixToAncestor(card, natural)
    natural.preTranslate((text.left - parent.scrollX).toFloat(),
        (text.top - parent.scrollY).toFloat())
    val horizontal = (card.getChildAt(0) as LinearLayout).orientation == LinearLayout.HORIZONTAL
    val footY = if (horizontal) text.height / 2f else (text.height - text.paddingBottom).toFloat()
    val fit = card.width.toFloat() / text.width
    natural.preScale(fit, fit, 0f, footY)
    val baseline = floatArrayOf(0f, footY, text.width.toFloat(), footY)
    natural.mapPoints(baseline)
    card.mapCornerMorph(baseline)
    val global = Matrix()
    card.renderedMatrixToAncestor(null, global)
    global.mapPoints(baseline)
    val points = projectedCardLetteringCorners(card)
    assertEquals("Card containment must not shrink raised lettering",
        hypot(baseline[2] - baseline[0], baseline[3] - baseline[1]),
        points[2] - points[0], .5f)
}
