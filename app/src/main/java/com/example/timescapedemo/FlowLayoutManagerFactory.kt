package com.example.timescapedemo

import android.content.Context
import kotlin.math.roundToInt

object FlowLayoutManagerFactory {
    fun create(context: Context): RightRailFlowLayoutManager {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val horizontalInsetPx = (32 * density).roundToInt()
        val minSidePx = (320 * density).roundToInt()
        val availableWidth = (metrics.widthPixels - horizontalInsetPx).coerceAtLeast(minSidePx)
        val baseSide = availableWidth
        val focusSide = availableWidth
        val pitch = (availableWidth * 0.26f).roundToInt()
        return RightRailFlowLayoutManager(
            baseSidePx = baseSide,
            focusSidePx = focusSide,
            itemPitchPx = pitch,
            rightInsetPx = (8 * density).roundToInt()
        )
    }
}
