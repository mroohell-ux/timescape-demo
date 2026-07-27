package com.example.timescapedemo

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalReceiptSizingTest {
    @Test
    fun appliesSelectedHeightToFullCardWidth() {
        assertEquals(450, receiptDisplayHeight(itemWidth = 360, heightPercent = 125))
    }

    @Test
    fun clampsInvalidPersistedHeight() {
        assertEquals(180, receiptDisplayHeight(itemWidth = 360, heightPercent = 0))
        assertEquals(2, receiptDisplayHeight(itemWidth = 0, heightPercent = 999))
    }
}
