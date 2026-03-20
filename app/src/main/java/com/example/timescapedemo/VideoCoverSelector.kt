package com.example.timescapedemo

import android.graphics.Bitmap
import kotlin.random.Random

/**
 * Picks a visually stable cover frame timestamp while avoiding very early and dark frames.
 */
object VideoCoverSelector {

    private const val EARLY_SECONDS_TO_AVOID = 3_000L
    private const val MAX_ATTEMPTS = 6

    fun pickTimestampMs(
        durationMs: Long,
        random: Random = Random.Default
    ): Long {
        if (durationMs <= 0L) return 0L
        if (durationMs <= EARLY_SECONDS_TO_AVOID) return durationMs / 2L
        return random.nextLong(EARLY_SECONDS_TO_AVOID, durationMs)
    }

    fun pickStableCover(
        durationMs: Long?,
        state: VideoCardState?,
        frameProvider: (Long) -> Bitmap?,
        random: Random = Random.Default
    ): VideoCoverState {
        val total = durationMs ?: 0L
        val existing = state?.cover
        if (existing != null) return existing

        if (total <= 0L) return VideoCoverState(timestampMs = 0L)

        repeat(MAX_ATTEMPTS) {
            val ts = pickTimestampMs(total, random)
            val frame = frameProvider(ts)
            if (frame != null && !isFrameTooDark(frame)) {
                return VideoCoverState(timestampMs = ts)
            }
        }
        return VideoCoverState(timestampMs = pickTimestampMs(total, random))
    }

    fun isFrameTooDark(bitmap: Bitmap, darknessThreshold: Float = 0.09f): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return true

        val sampleStepX = (width / 24).coerceAtLeast(1)
        val sampleStepY = (height / 24).coerceAtLeast(1)

        var luminanceSum = 0.0
        var sampleCount = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val color = bitmap.getPixel(x, y)
                val r = ((color shr 16) and 0xFF) / 255.0
                val g = ((color shr 8) and 0xFF) / 255.0
                val b = (color and 0xFF) / 255.0
                val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
                luminanceSum += luminance
                sampleCount += 1
                x += sampleStepX
            }
            y += sampleStepY
        }

        val avg = if (sampleCount == 0) 0.0 else luminanceSum / sampleCount.toDouble()
        return avg < darknessThreshold
    }
}
