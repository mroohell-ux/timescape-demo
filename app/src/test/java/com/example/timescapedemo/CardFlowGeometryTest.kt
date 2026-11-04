package com.example.timescapedemo

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CardFlowGeometryTest {
    private val geometry = CardFlowGeometry()

    private fun pose(depth: Float, opening: Float = 0f) =
        CardFlowPose().also { geometry.sample(depth, opening, it) }

    @Test fun focusIsFrontFacingInBothModes() {
        for (opening in listOf(0f, .5f, 1f)) {
            val pose = pose(0f, opening)
            assertEquals(0f, pose.pitch, 0f)
            assertEquals(0f, pose.yaw, 0f)
            assertEquals(0f, pose.roll, 0f)
            assertEquals(1f, pose.depthScale, 0f)
            assertEquals(1f, pose.scaleY, 0f)
            assertEquals(.5f, pose.pivotX, 0f)
            assertEquals(.5f, pose.pivotY, 0f)
        }
    }

    @Test fun approachingFocusContinuouslyUnfoldsAndReversingRetracesIt() {
        var lastAngle = 31f
        var lastScale = 0f
        for (step in 0..100) {
            val depth = 1f - step / 100f
            val weight = 1f - depth
            val opening = weight * weight * (3f - 2f * weight)
            val pose = pose(depth, opening)
            assertTrue(abs(pose.pitch) <= lastAngle)
            assertTrue(pose.depthScale >= lastScale)
            val departing = pose(-depth, opening)
            assertEquals(pose.pitch, departing.pitch, 0f)
            assertEquals(-pose.yaw, departing.yaw, 0f)
            lastAngle = abs(pose.pitch)
            lastScale = pose.depthScale
        }
        assertEquals(0f, lastAngle, 0f)
        assertEquals(1f, lastScale, 0f)
    }

    @Test fun samplesDoNotJumpAtFocusOrInterpolationBoundaries() {
        for (boundary in listOf(-3f, -1f, 0f, .5f, 1f, 4.8f, 6f)) {
            val before = pose(boundary - .0001f)
            val after = pose(boundary + .0001f)
            assertTrue(abs(before.pitch - after.pitch) < .02f)
            assertTrue(abs(before.yaw - after.yaw) < .01f)
            assertTrue(abs(before.roll - after.roll) < .01f)
            assertTrue(abs(before.pivotY - after.pivotY) < .001f)
            assertTrue(abs(before.bend - after.bend) < .001f)
            assertTrue(abs(before.depthScale - after.depthScale) < .001f)
        }
    }

    @Test fun customizedScaleKeepsWindowAndPrefetchInverseConsistent() {
        for (geometry in listOf(geometry,
            geometry.copy(minimumScale = .35f, maximumScale = .9f, depthShrink = .16f),
            geometry.copy(departingShrink = 0f),
            geometry.copy(minimumScale = 1f))) {
            for (step in -100..200) {
                val depth = step / 10f
                val offset = geometry.ribbonOffset(depth, 124f)
                assertEquals(depth, geometry.depthAtRibbonOffset(offset, 124f), .0001f)
                assertTrue(geometry.scaleAtDepth(depth) in geometry.minimumScale..geometry.maximumScale)
            }
        }
    }

    @Test fun spacingOnBothSidesFollowsTheSizeOfTheCardPlane() {
        for (side in listOf(-1f, 1f)) {
            var previousGap = Float.MAX_VALUE
            for (distance in 0..24) {
                val near = geometry.ribbonOffset(side * distance, 112f)
                val far = geometry.ribbonOffset(side * (distance + 1), 112f)
                val gap = abs(far - near)
                assertTrue(gap > 0f && gap <= previousGap + .001f)
                previousGap = gap
            }
        }
    }

    @Test fun customAnglesAnchorsAndYScaleUseTheSameProgress() {
        val geometry = geometry.copy(maximumPitchDegrees = 40f, maximumYawDegrees = 8f,
            maximumRollDegrees = 3f, distantScaleY = .9f, distantPivotX = .2f, distantPivotY = .9f,
            pitchDepthVariation = 0f)
        val pose = CardFlowPose()
        geometry.sample(1f, .5f, pose)
        assertEquals(20f, pose.pitch, 0f)
        assertEquals(4f, pose.yaw, 0f)
        assertEquals(.95f, pose.scaleY, .0001f)
        assertEquals(.35f, pose.pivotX, .0001f)
        assertEquals(.7f, pose.pivotY, .0001f)
        assertTrue(abs(pose.roll) <= 1.5f)
    }

    @Test fun fourCornersHaveDistinctLeadAndLagInsteadOfAnAlignedRectangle() {
        val corners = pose(.5f, .5f).corners
        val topLeftLag = corners[0]
        val topRightLag = 1f - corners[2]
        val bottomLeftLag = corners[6]
        val bottomRightLag = 1f - corners[4]
        assertEquals(0f, topLeftLag, 0f)
        assertTrue(topRightLag > topLeftLag + .001f)
        assertTrue(bottomLeftLag > topRightLag + .001f)
        assertTrue(bottomRightLag > bottomLeftLag + .001f)
        assertTrue(corners[3] > corners[1] + .001f)
        assertTrue(corners[7] > corners[5] + .001f)
    }

    @Test fun delayedCornersOnlyGrowAsTheCardApproachesFocus() {
        for (settings in listOf(geometry, geometry.copy(cornerStagger = .2f,
            cornerWidthLead = .65f, cornerHeightLead = .4f, cornerDirectionBlendDepth = .05f))) {
            for (side in listOf(-1f, 1f)) {
                val previous = FloatArray(8)
                for (step in 0..1000) {
                    val pose = CardFlowPose()
                    settings.sample(side * (1f - step / 1000f), 0f, pose)
                    for (corner in 0..3) for (axis in 0..1) {
                        val coordinate = corner * 2 + axis
                        val farSide = if (axis == 0) corner == 1 || corner == 2 else corner >= 2
                        val inset = if (farSide) 1f - pose.corners[coordinate] else pose.corners[coordinate]
                        val extent = pose.depthScale * (1f - 2f * inset)
                        assertTrue("Corner $corner axis $axis shrank at $side/$step",
                            extent >= previous[coordinate] - .000001f)
                        previous[coordinate] = extent
                    }
                }
            }
        }
    }

    @Test fun bothSidesLieBackwardWithSmallPositionDependentPitchDifferences() {
        for (side in listOf(-1f, 1f)) {
            var previous = 0f
            for (distance in 1..6) {
                val pitch = pose(side * distance).pitch
                assertTrue(pitch > 0f)
                assertTrue(abs(pitch) > previous)
                assertTrue(abs(pitch) <= geometry.maximumPitchDegrees)
                if (distance > 1) assertTrue(abs(pitch) - previous < 2f)
                previous = abs(pitch)
            }
        }
        val uniform = geometry.copy(pitchDepthVariation = 0f)
        for (depth in listOf(-6f, -1f, 1f, 6f)) {
            val pose = CardFlowPose()
            uniform.sample(depth, 0f, pose)
            assertEquals(geometry.maximumPitchDegrees, pose.pitch, 0f)
        }
    }

    @Test fun approachingFromTheOtherSideReversesTheCornerOrder() {
        val symmetric = geometry.copy(departingShrink = geometry.depthShrink)
        for (step in 1..99) {
            val ahead = CardFlowPose().also { symmetric.sample(step / 100f, 0f, it) }.corners
            val behind = CardFlowPose().also { symmetric.sample(-step / 100f, 0f, it) }.corners
            for (corner in 0..3) {
                val mirrored = (corner + 2) % 4
                assertEquals(1f - ahead[mirrored * 2], behind[corner * 2], .00001f)
                assertEquals(1f - ahead[mirrored * 2 + 1], behind[corner * 2 + 1], .00001f)
            }
        }
    }

    @Test fun cornerMorphIsNeutralAtEndpointsAndCanBeDisabled() {
        val neutral = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        for (depth in listOf(-8f, -1f, 0f, 1f, 8f)) {
            assertArrayEquals(neutral, pose(depth).corners, 0f)
        }
        val disabled = geometry.copy(cornerStagger = 0f)
        for (step in -100..100) {
            val pose = CardFlowPose()
            disabled.sample(step / 100f, .4f, pose)
            assertArrayEquals(neutral, pose.corners, 0f)
        }
    }

    @Test fun cornerMorphIsContinuousAndConvexEvenAtMaximumLegalSettings() {
        for (settings in listOf(geometry, geometry.copy(cornerStagger = .2f,
            cornerWidthLead = .65f, cornerHeightLead = .4f, cornerDirectionBlendDepth = .05f))) {
            val current = CardFlowPose()
            var previous: FloatArray? = null
            for (step in -1200..1200) {
                settings.sample(step / 1000f, .5f, current)
                val corners = current.corners
                assertTrue(corners.all { it.isFinite() && it in 0f..1f })
                previous?.let { old ->
                    assertTrue(corners.indices.all { abs(corners[it] - old[it]) < .01f })
                }
                for (corner in 0..3) {
                    val a = corner * 2
                    val b = ((corner + 1) % 4) * 2
                    val c = ((corner + 2) % 4) * 2
                    val cross = (corners[b] - corners[a]) * (corners[c + 1] - corners[b + 1]) -
                        (corners[b + 1] - corners[a + 1]) * (corners[c] - corners[b])
                    assertTrue("Corner morph became concave at step $step", cross > .01f)
                }
                previous = corners.copyOf()
            }
        }
    }

    @Test fun cameraScalesWithCardsAndStaysOutsideTallFaces() {
        assertEquals(geometry.cameraDistance(300, 120, 160) * 3,
            geometry.cameraDistance(900, 360, 160), .001f)
        assertTrue(geometry.cameraDistance(300, 900, 440) * 72f / 440 > 900)
        assertTrue(geometry.copy(cameraDistanceInCardWidths = 3f).cameraDistance(300, 120, 160) >
            geometry.cameraDistance(300, 120, 160))
        assertEquals(geometry.cameraDistance(300, 120, 160) * 2.75f,
            geometry.cameraDistance(300, 120, 440), .001f)
        val closest = geometry.copy(cameraDistanceInCardWidths = .6f)
        assertTrue(closest.cameraDistance(500, 500, 160, 0f, 0f) * 72f / 160 >
            kotlin.math.hypot(500f, 500f))
    }

    @Test fun invalidParametersFailExplicitly() {
        for (invalid in listOf<() -> CardFlowGeometry>(
            { geometry.copy(maximumPitchDegrees = Float.NaN) },
            { geometry.copy(cameraDistanceInCardWidths = 0f) },
            { geometry.copy(tiltDepth = Float.POSITIVE_INFINITY) },
            { geometry.copy(minimumScale = 0f) },
            { geometry.copy(maximumScale = .5f) },
            { geometry.copy(depthShrink = 0f) },
            { geometry.copy(distantPivotX = -1f) },
            { geometry.copy(cornerStagger = .4f) },
            { geometry.copy(cornerWidthLead = Float.NaN) },
            { geometry.copy(cornerDirectionBlendDepth = 0f) },
            { geometry.copy(pitchDepthVariation = .5f) },
            { geometry.copy(letteringFacingPitchDegrees = Float.NaN) },
            { geometry.copy(letteringLiftDp = -1f) },
            { geometry.copy(cardOverlapFraction = Float.NaN) },
            { geometry.copy(cardOverlapFraction = -.01f) },
            { geometry.copy(cardOverlapFraction = .26f) }
        )) {
            assertThrows(IllegalArgumentException::class.java) { invalid() }
        }
    }
}
