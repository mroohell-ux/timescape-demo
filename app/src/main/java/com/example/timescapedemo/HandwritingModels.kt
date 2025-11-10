package com.example.timescapedemo

import android.graphics.Bitmap
import androidx.annotation.ColorInt

enum class HandwritingFormat(
    val compressFormat: Bitmap.CompressFormat,
    val extension: String
) {
    PNG(Bitmap.CompressFormat.PNG, "png"),
    JPEG(Bitmap.CompressFormat.JPEG, "jpg"),
    WEBP(Bitmap.CompressFormat.WEBP, "webp");

    companion object {
        fun fromName(name: String?): HandwritingFormat? =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) }

        fun fromExtension(extension: String?): HandwritingFormat? =
            values().firstOrNull { it.extension.equals(extension, ignoreCase = true) }
    }
}

data class HandwritingOptions(
    @ColorInt val backgroundColor: Int,
    @ColorInt val brushColor: Int,
    val brushSizeDp: Float,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val format: HandwritingFormat
)

data class HandwritingContent(
    var path: String,
    var options: HandwritingOptions
)
