package com.example.timescapedemo

import org.junit.Assert.*
import org.junit.Test

class CardFlipSpringTest {
    private fun spring() = CardFlipSpring(34f, .9f, .0006f).apply {
        snap(.3f)
        target = 1f
        velocity = 4f
    }

    @Test fun releaseRetainsVelocity() {
        val spring = spring()
        assertEquals(4f, spring.velocity, 0f)
        spring.advance(.00001f)
        assertEquals(.30004f, spring.value, .000001f)
        assertTrue(spring.velocity > 4f)
    }

    @Test fun trajectoryIsIndependentOfFrameRate() {
        val regular = spring()
        val uneven = spring()
        repeat(24) { regular.advance(1f / 240f) }
        uneven.advance(.013f)
        uneven.advance(.061f)
        uneven.advance(.026f)
        assertEquals(regular.value, uneven.value, .00001f)
        assertEquals(regular.velocity, uneven.velocity, .0001f)
    }

    @Test fun settlingHasOnlySmallOvershoot() {
        val spring = spring()
        var peak = 0f
        repeat(480) {
            spring.advance(1f / 240f)
            peak = maxOf(peak, spring.value)
        }
        assertTrue(peak <= 1.02f)
        assertTrue(spring.isAtRest)
        assertEquals(1f, spring.value, .0006f)
    }

    @Test fun retargetingDoesNotResetMomentum() {
        val spring = spring()
        spring.target = 0f
        assertEquals(4f, spring.velocity, 0f)
        spring.advance(.0001f)
        assertTrue(spring.value > .3f)
        repeat(480) { spring.advance(1f / 240f) }
        assertTrue(spring.isAtRest)
        assertEquals(0f, spring.value, .0006f)
    }

    @Test fun fastReleaseAtFaceCannotProduceLargeVisualRebound() {
        for (start in listOf(.9f, 1f)) {
            val spring = CardFlipSpring(34f, .9f, .0006f).apply {
                snap(start)
                target = 1f
                velocity = 9f
            }
            repeat(480) {
                spring.advance(1f / 240f)
                assertTrue(cardFlipAngle(spring.value) <= 182f)
            }
        }
        assertEquals(90f, cardFlipAngle(.5f), .00001f)
        assertEquals(-90f, cardFlipAngle(-.5f), .00001f)
        assertEquals(180f, cardFlipAngle(1f), 0f)
        assertEquals(180f, cardFlipAngle(1.0001f), .001f)
    }
}
