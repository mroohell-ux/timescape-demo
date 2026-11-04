package com.example.timescapedemo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sin

internal object FlowWindMotion {
    fun sample(position: Float, cardId: Long, row: Float, rtl: Boolean, pose: FlowWindPose) {
        require(position.isFinite() && row.isFinite())
        var hash = (cardId xor (cardId ushr 32)).toInt()
        hash = (hash xor (hash ushr 16)) * 0x45d9f3b
        hash = (hash xor (hash ushr 16)) * 0x45d9f3b
        hash = hash xor (hash ushr 16)
        val first = (hash and 255) / 255f
        val second = ((hash ushr 8) and 255) / 255f
        val third = ((hash ushr 16) and 255) / 255f
        val distance = abs(position).coerceIn(0f, 1f)
        val envelope = smoothstep(distance)
        val delay = .08f + first * .28f + row.coerceIn(0f, 1f) * .08f
        val phase = distance - delay * 2f * envelope * (1f - envelope)
        val travel = smoothstep(phase)
        val gust = sin(PI.toFloat() * travel)
        val direction = sign(position) * if (rtl) -1f else 1f
        pose.x = direction * (.72f + first * .32f) * travel
        pose.y = direction * ((second - .5f) * .30f * gust +
            (row.coerceIn(0f, 1f) - .5f) * .08f * travel)
        pose.pitch = (third - .5f) * 24f * gust
        pose.yaw = direction * (42f + second * 32f) * travel
        pose.roll = direction * (10f + third * 26f) * travel
        pose.scale = 1f - (.10f + first * .12f) * travel
        pose.alpha = 1f - smoothstep(((phase - .08f) / .92f).coerceIn(0f, 1f))
        pose.pivotX = .2f + third * .6f
        pose.pivotY = .2f + second * .6f
    }

    private fun smoothstep(value: Float) = value * value * (3f - 2f * value)
}

internal class FlowWindPose {
    var x = 0f
    var y = 0f
    var pitch = 0f
    var yaw = 0f
    var roll = 0f
    var scale = 1f
    var alpha = 1f
    var pivotX = .5f
    var pivotY = .5f
}
