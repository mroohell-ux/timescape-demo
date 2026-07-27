package com.example.timescapedemo

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalReceiptSizingTest {
    @Test
    fun preservesReceiptAspectRatio() {
        assertEquals(560, receiptDisplayHeight(itemWidth = 360, canvasWidth = 900, canvasHeight = 1400))
    }

    @Test
    fun toleratesInvalidPersistedDimensions() {
        assertEquals(360, receiptDisplayHeight(itemWidth = 360, canvasWidth = 0, canvasHeight = 1))
        assertEquals(1, receiptDisplayHeight(itemWidth = 0, canvasWidth = 1, canvasHeight = 0))
    }
}
