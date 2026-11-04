package com.example.timescapedemo

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat

internal enum class FlowWallpaperPreset(
    val key: String,
    @StringRes val titleRes: Int,
    channels: FloatArray? = null
) {
    BLUE("blue", R.string.wallpaper_preset_blue),
    GOLD("gold", R.string.wallpaper_preset_gold, floatArrayOf(
        0f, 0f, 1f, 0f, 0f,
        .35f, .65f, 0f, 0f, 0f,
        1f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )),
    PURPLE("purple", R.string.wallpaper_preset_purple, floatArrayOf(
        .3f, 0f, .7f, 0f, 0f,
        1f, 0f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )),
    GREEN("green", R.string.wallpaper_preset_green, floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        .7f, .3f, 0f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )),
    ROSE("rose", R.string.wallpaper_preset_rose, floatArrayOf(
        0f, 0f, 1f, 0f, 0f,
        1f, 0f, 0f, 0f, 0f,
        .3f, .7f, 0f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    ));

    private val palette = channels?.let { ColorMatrixColorFilter(ColorMatrix(it)) }

    fun drawable(context: Context): Drawable =
        requireNotNull(ContextCompat.getDrawable(context, R.drawable.bg_app_cosmic_flow))
            .mutate().apply { colorFilter = palette }

    companion object {
        fun fromKey(key: String): FlowWallpaperPreset? = entries.firstOrNull { it.key == key }
    }
}
