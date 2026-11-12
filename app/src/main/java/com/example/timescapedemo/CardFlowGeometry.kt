package com.example.timescapedemo

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

data class CardFlowGeometry(
    val maximumPitchDegrees: Float = 30f,
    val maximumYawDegrees: Float = -10f,
    val maximumRollDegrees: Float = 12f,
    val tiltDepth: Float = 1f,
    val cameraDistanceInCardWidths: Float = 1.35f,
    val minimumScale: Float = .52f,
    val maximumScale: Float = 1f,
    val depthShrink: Float = .10f,
    val departingShrink: Float = .10f,
    val distantScaleY: Float = 1f,
    val focusPivotX: Float = .5f,
    val focusPivotY: Float = .5f,
    val distantPivotX: Float = .46f,
    val distantPivotY: Float = .78f,
    val lateralBend: Float = .095f,
    val lateralDrift: Float = .012f,
    val bendFrequency: Float = .65f,
    val cornerStagger: Float = .14f,
    val cornerWidthLead: Float = .55f,
    val cornerHeightLead: Float = .30f,
    val cornerDirectionBlendDepth: Float = .15f,
    val pitchDepthVariation: Float = .15f,
    val letteringFacingPitchDegrees: Float = 0f,
    val letteringLiftDp: Float = 14f,
    val cardOverlapFraction: Float = .10f
) {
    init {
        require(maximumPitchDegrees in 0f..60f) { "Pitch must be between 0 and 60 degrees" }
        require(maximumYawDegrees in -30f..30f) { "Yaw must be between -30 and 30 degrees" }
        require(maximumRollDegrees in 0f..12f) { "Roll must be between 0 and 12 degrees" }
        require(tiltDepth in .1f..8f) { "Tilt depth must be between .1 and 8 cards" }
        require(cameraDistanceInCardWidths in .6f..8f) { "Camera distance must be between .6 and 8 card widths" }
        require(minimumScale in .2f..1f) { "Minimum scale must be between .2 and 1" }
        require(maximumScale in .5f..1.2f && minimumScale <= maximumScale) {
            "Maximum scale must be between .5 and 1.2 and not below the minimum"
        }
        require(depthShrink in .01f..0.5f) { "Depth shrink must be between .01 and .5 per card" }
        require(departingShrink in 0f..0.25f) { "Departing shrink must be between 0 and .25 per card" }
        require(distantScaleY in .8f..1.2f) { "Distant Y scale must be between .8 and 1.2" }
        require(listOf(focusPivotX, focusPivotY, distantPivotX, distantPivotY).all { it in 0f..1f }) {
            "Pivot fractions must be between 0 and 1"
        }
        require(lateralBend in 0f..0.15f && lateralDrift in 0f..0.03f) { "Lateral displacement is out of range" }
        require(bendFrequency in .1f..1f) { "Bend frequency must be between .1 and 1" }
        require(cornerStagger in 0f..0.2f) { "Corner stagger must be between 0 and .2 of the transition" }
        require(cornerWidthLead in 0f..0.65f && cornerHeightLead in 0f..0.4f) {
            "Corner displacement is out of range"
        }
        require(cornerDirectionBlendDepth in .05f..0.5f) {
            "Corner direction blend must be between .05 and .5 cards"
        }
        require(pitchDepthVariation in 0f..0.3f) { "Pitch depth variation must be between 0 and .3" }
        require(letteringFacingPitchDegrees in -10f..10f) { "Lettering pitch must be between -10 and 10 degrees" }
        require(letteringLiftDp in 0f..24f) { "Lettering lift must be between 0 and 24dp" }
        require(cardOverlapFraction in 0f..0.25f) { "Card overlap must be between 0 and .25" }
    }

    internal fun scaleAtDepth(depth: Float): Float =
        (maximumScale - max(0f, depth) * depthShrink - max(0f, -depth) * departingShrink)
            .coerceIn(minimumScale, maximumScale)

    internal fun ribbonOffset(depth: Float, pitch: Float): Float {
        val shrink = if (depth < 0f) departingShrink else depthShrink
        if (shrink == 0f) return depth * maximumScale * pitch
        val distance = abs(depth)
        val taperEnd = (maximumScale - minimumScale) / shrink
        val tapered = min(distance, taperEnd)
        return sign(depth) * (maximumScale * tapered - shrink * tapered * tapered / 2f +
            max(0f, distance - taperEnd) * minimumScale) * pitch
    }

    internal fun depthAtRibbonOffset(offset: Float, pitch: Float): Float {
        val distance = abs(offset) / pitch
        val shrink = if (offset < 0f) departingShrink else depthShrink
        if (shrink == 0f) return offset / (maximumScale * pitch)
        val taperEnd = (maximumScale - minimumScale) / shrink
        val taperDistance = taperEnd * (maximumScale + minimumScale) / 2f
        return sign(offset) * if (distance <= taperDistance) {
            (maximumScale - sqrt(max(0f, maximumScale * maximumScale - 2f * shrink * distance))) / shrink
        } else taperEnd + (distance - taperDistance) / minimumScale
    }

    internal fun sample(depth: Float, opening: Float, pose: CardFlowPose) {
        val progress = (abs(depth) / tiltDepth).coerceIn(0f, 1f)
        val away = progress * progress * (3f - 2f * progress) * (1f - opening)
        val bendDepth = abs(depth).coerceAtMost(6f)
        pose.depthScale = scaleAtDepth(depth)
        pose.scaleX = 1f
        pose.scaleY = 1f + (distantScaleY - 1f) * away
        val beyondNeighbor = max(0f, abs(depth) / tiltDepth - 1f)
        val pitchWeight = 1f - pitchDepthVariation / (1f + beyondNeighbor * .35f)
        pose.pitch = sign(depth) * maximumPitchDegrees * pitchWeight * away
        // Keep the band twisting beyond the first neighbor without a sinusoidal reversal.
        val twist = abs(depth) / (abs(depth) + 3f * tiltDepth)
        pose.yaw = maximumYawDegrees * twist * away
        pose.outerEdgeAngle = -maximumRollDegrees * tanh(depth / (8f * tiltDepth)) * away
        pose.pivotX = focusPivotX + (distantPivotX - focusPivotX) * away
        pose.pivotY = focusPivotY + (distantPivotY - focusPivotY) * away
        if (depth < 0f) pose.pivotY = 1f - pose.pivotY
        pose.bend = (-sin(bendDepth * bendFrequency) * lateralBend +
            bendDepth * lateralDrift) * (1f - opening)
        sampleCorners(depth, pose.depthScale, pose.corners)
    }

    internal fun sampleHorizontal(depth: Float, opening: Float, pose: CardFlowPose) {
        sample(depth, opening, pose)
        val pitch = pose.pitch
        pose.pitch = -pose.yaw
        pose.yaw = -pitch
        pose.outerEdgeAngle = -pose.outerEdgeAngle
        val pivotX = pose.pivotX
        pose.pivotX = pose.pivotY
        pose.pivotY = pivotX
        pose.scaleX = pose.scaleY
        pose.scaleY = 1f
        val corners = pose.corners
        val topLeftX = corners[0]
        val topRightX = corners[2]
        val topRightY = corners[3]
        val bottomRightX = corners[4]
        val bottomLeftX = corners[6]
        val bottomLeftY = corners[7]
        corners[0] = corners[1]
        corners[1] = topLeftX
        corners[2] = bottomLeftY
        corners[3] = bottomLeftX
        corners[4] = corners[5]
        corners[5] = bottomRightX
        corners[6] = topRightY
        corners[7] = topRightX
    }

    private fun sampleCorners(depth: Float, scale: Float, corners: FloatArray) {
        val near = (1f - abs(depth)).coerceIn(0f, 1f)
        val progress = smoothstep(near)
        val staggerEnvelope = 2f * progress * (1f - progress)
        val direction = (depth / cornerDirectionBlendDepth).coerceIn(-1f, 1f) * .5f + .5f
        for (corner in 0..3) {
            val forwardRank = when (corner) {
                0 -> 0f
                1 -> 1f
                2 -> 3f
                else -> 2f
            }
            val reverseRank = when (corner) {
                0 -> 2f
                1 -> 3f
                2 -> 1f
                else -> 0f
            }
            val delay = cornerStagger * (reverseRank + (forwardRank - reverseRank) * direction)
            // Delay real scale growth instead of adding an inward-then-outward deformation.
            val delayedScale = scaleAtDepth(depth + sign(depth) * delay * staggerEnvelope)
            val lag = (scale - delayedScale) / (2f * scale)
            val x = lag * cornerWidthLead
            val y = lag * cornerHeightLead
            corners[corner * 2] = if (corner == 0 || corner == 3) x else 1f - x
            corners[corner * 2 + 1] = if (corner < 2) y else 1f - y
        }
    }

    private fun smoothstep(value: Float) = value * value * (3f - 2f * value)

    internal fun cameraDistance(
        width: Int,
        height: Int,
        densityDpi: Int,
        pivotX: Float = focusPivotX,
        pivotY: Float = focusPivotY
    ): Float {
        // View normalizes depth pixels by DPI; its camera projects at 72 screen pixels per unit.
        val cornerRadius = hypot(width * max(pivotX, 1f - pivotX), height * max(pivotY, 1f - pivotY))
        val screenDistance = max(width * cameraDistanceInCardWidths,
            max(max(width, height).toFloat(), cornerRadius) * 1.1f)
        return screenDistance * densityDpi / 72f
    }
}

internal class CardFlowPose {
    var depthScale = 1f
    var scaleX = 1f
    var scaleY = 1f
    var pitch = 0f
    var yaw = 0f
    var outerEdgeAngle = 0f
    var pivotX = .5f
    var pivotY = .5f
    var bend = 0f
    val corners = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
}
