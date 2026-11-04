package com.example.timescapedemo

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FlowWindMotionTest {
    private fun sample(position: Float, id: Long = 1L, row: Float = .5f, rtl: Boolean = false) =
        FlowWindPose().also { FlowWindMotion.sample(position, id, row, rtl, it) }

    private fun values(pose: FlowWindPose) = floatArrayOf(
        pose.x, pose.y, pose.pitch, pose.yaw, pose.roll, pose.scale, pose.alpha, pose.pivotX, pose.pivotY
    )

    @Test fun landingIsExactlyTheOriginalCardPose() {
        for (id in listOf(0L, 1L, 53L, Long.MAX_VALUE)) for (rtl in listOf(false, true)) {
            val pose = sample(0f, id, .2f, rtl)
            assertArrayEquals(FloatArray(5), values(pose).copyOfRange(0, 5), 0f)
            assertEquals(1f, pose.scale, 0f)
            assertEquals(1f, pose.alpha, 0f)
            for (position in listOf(-1f, 1f, -3f, 3f)) {
                val away = sample(position, id, .7f, rtl)
                assertEquals(0f, away.alpha, .000001f)
                assertTrue(values(away).all { it.isFinite() })
                assertTrue(away.scale in .75f..1f)
            }
        }
    }

    @Test fun reversingTheFingerRetracesEveryCardWithoutRandomness() {
        val positions = (0..100).map { it / 100f }
        for (id in 1L..8L) {
            val frames = positions.map { values(sample(it, id)) }
            for (step in positions.indices.reversed()) {
                assertArrayEquals(frames[step], values(sample(positions[step], id)), 0f)
            }
        }
    }

    @Test fun differentCardsHaveDifferentCurvesRotationsAndPivots() {
        val poses = (1L..8L).map { sample(.55f, it) }
        for (first in poses.indices) for (second in first + 1 until poses.size) {
            assertTrue(abs(poses[first].x - poses[second].x) +
                abs(poses[first].y - poses[second].y) > .005f)
            assertTrue(abs(poses[first].yaw - poses[second].yaw) +
                abs(poses[first].roll - poses[second].roll) > .1f)
        }
        assertTrue(abs(sample(.55f, row = .1f).y - sample(.55f, row = .9f).y) > .005f)
    }

    @Test fun swipeDirectionAndRtlMirrorTheWindWithoutChangingItsTiming() {
        for (step in 0..100) {
            val forward = sample(step / 100f)
            val reverse = sample(-step / 100f)
            val rtl = sample(step / 100f, rtl = true)
            assertEquals(-forward.x, reverse.x, 0f)
            assertEquals(-forward.y, reverse.y, 0f)
            assertEquals(-forward.yaw, reverse.yaw, 0f)
            assertEquals(-forward.roll, reverse.roll, 0f)
            assertEquals(forward.alpha, reverse.alpha, 0f)
            assertArrayEquals(values(reverse), values(rtl), 0f)
        }
    }

    @Test fun motionRemainsContinuousAndOpacityNeverRebounds() {
        for (id in 0L..20L) for (row in listOf(-1f, .5f, 2f)) {
            var previous = sample(0f, id, row)
            for (step in 1..1000) {
                val pose = sample(step / 1000f, id, row)
                assertTrue(pose.alpha <= previous.alpha + .000001f)
                assertTrue(pose.alpha in 0f..1f)
                assertTrue(pose.x >= previous.x)
                assertTrue(abs(pose.x - previous.x) < .005f)
                assertTrue(abs(pose.yaw - previous.yaw) < .4f)
                previous = pose
            }
        }
    }

    @Test fun invalidProgressFailsExplicitly() {
        assertThrows(IllegalArgumentException::class.java) { sample(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { sample(Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { sample(.5f, row = Float.NaN) }
    }
}
