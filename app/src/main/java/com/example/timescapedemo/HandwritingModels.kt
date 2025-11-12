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

enum class HandwritingPaperStyle {
    PLAIN,
    RULED,
    GRID;

    companion object {
        fun fromName(name: String?): HandwritingPaperStyle? =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

enum class HandwritingPenType {
    ROUND,
    MARKER,
    CALLIGRAPHY,
    HIGHLIGHTER;

    companion object {
        fun fromName(name: String?): HandwritingPenType? =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

enum class HandwritingEraserType {
    ROUND,
    BLOCK;

    companion object {
        fun fromName(name: String?): HandwritingEraserType? =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

enum class HandwritingPaletteSection {
    PEN,
    ERASER,
    CANVAS;

    companion object {
        fun fromName(name: String?): HandwritingPaletteSection? =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

enum class HandwritingDrawingTool {
    PEN,
    ERASER;

    companion object {
        fun fromName(name: String?): HandwritingDrawingTool? =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

data class HandwritingOptions(
    @ColorInt val backgroundColor: Int,
    @ColorInt val brushColor: Int,
    val brushSizeDp: Float,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val format: HandwritingFormat,
    val paperStyle: HandwritingPaperStyle,
    val penType: HandwritingPenType,
    val eraserSizeDp: Float,
    val eraserType: HandwritingEraserType
)

data class HandwritingContent(
    var path: String,
    var options: HandwritingOptions
)
