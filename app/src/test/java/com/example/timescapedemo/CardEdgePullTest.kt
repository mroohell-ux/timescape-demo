package com.example.timescapedemo

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CardEdgePullTest {
    @Test fun pullIsSymmetricContinuousAndBoundedWithoutAHardStop() {
        val limit = 96f
        assertEquals(0f, cardEdgeDisplacement(0f, limit), 0f)
        var previous = 0f
        for (pull in 1..10_000) {
            val actual = cardEdgeDisplacement(pull.toFloat(), limit)
            assertTrue(actual > previous)
            assertTrue(actual < limit)
            assertEquals(-actual, cardEdgeDisplacement(-pull.toFloat(), limit), 0f)
            previous = actual
        }
    }

    @Test fun equalFingerDistancesMeetIncreasingResistance() {
        val first = cardEdgeDisplacement(80f, 96f)
        val second = cardEdgeDisplacement(160f, 96f)
        val third = cardEdgeDisplacement(240f, 96f)
        assertTrue(first > second - first)
        assertTrue(second - first > third - second)
        assertEquals(.45f, cardEdgeDisplacement(.001f, 96f) / .001f, .00001f)
    }

    @Test fun responseScalesWithDensityAndRemainsFiniteForLargePulls() {
        assertEquals(cardEdgeDisplacement(180f, 96f) * 2.625f,
            cardEdgeDisplacement(180f * 2.625f, 96f * 2.625f), .0001f)
        assertTrue(cardEdgeDisplacement(Float.MAX_VALUE, 96f).isFinite())
    }

    @Test fun reboundConvergesUnderUnevenFramesWithoutALargeOvershoot() {
        for (sign in listOf(-1f, 1f)) {
            val spring = CardFlipSpring(26f, .95f, .25f).apply {
                snap(sign * 1200f)
                target = 0f
            }
            for (seconds in listOf(.016f, .05f, .008f, .1f, .04f, .2f, .1f, .1f)) {
                spring.advance(seconds)
                assertTrue(sign * cardEdgeDisplacement(spring.value, 96f) > -.5f)
            }
            assertTrue(spring.isAtRest)
            assertTrue(abs(spring.value) < .25f)
        }
    }
}
