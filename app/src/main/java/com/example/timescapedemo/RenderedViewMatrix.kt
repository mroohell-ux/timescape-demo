package com.example.timescapedemo

import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import android.widget.TextView

internal fun View.postRenderedTransform(target: Matrix) {
    // View.matrix excludes the native animation matrix, which is applied before it.
    target.postConcat(matrix)
    animationMatrix?.let { target.postConcat(it) }
}

internal fun TextView.renderedGlyphBounds(ancestor: View, bounds: RectF, transform: Matrix) {
    val textLayout = checkNotNull(layout)
    var left = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    for (line in 0 until textLayout.lineCount) {
        left = minOf(left, textLayout.getLineLeft(line))
        right = maxOf(right, textLayout.getLineRight(line))
    }
    val top = baseline - textLayout.getLineBaseline(0) - scrollY
    bounds.set(left + compoundPaddingLeft - scrollX,
        (top + textLayout.getLineTop(0)).toFloat(),
        right + compoundPaddingLeft - scrollX,
        (top + textLayout.getLineBottom(textLayout.lineCount - 1)).toFloat())
    renderedMatrixToAncestor(ancestor, transform)
    transform.mapRect(bounds)
}

internal fun View.renderedMatrixToAncestor(ancestor: View?, target: Matrix) {
    target.reset()
    var descendant = this
    while (descendant !== ancestor) {
        descendant.postRenderedTransform(target)
        val parent = descendant.parent as? View
        target.postTranslate((descendant.left - (parent?.scrollX ?: 0)).toFloat(),
            (descendant.top - (parent?.scrollY ?: 0)).toFloat())
        if (parent == null) {
            check(ancestor == null) { "View is not attached to the requested ancestor" }
            return
        }
        descendant = parent
    }
}
