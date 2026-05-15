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

enum class HandwritingFace { FRONT, BACK }

data class HandwritingSide(
    var path: String,
    var options: HandwritingOptions
)

data class HandwritingContent(
    var path: String,
    var options: HandwritingOptions,
    var back: HandwritingSide? = null
) {
    fun hasBack(): Boolean = back != null

    fun side(face: HandwritingFace): HandwritingSide = if (face == HandwritingFace.BACK) {
        back ?: HandwritingSide(path, options)
    } else {
        HandwritingSide(path, options)
    }
}

enum class NoteEditorTool {
    PEN,
    ERASER,
    TEXT,
    IMAGE,
    SELECT,
    LASSO,
    SHAPE,
    HIGHLIGHTER,
    COLOR,
    WIDTH,
    MORE,
    PAN
}

enum class NoteElementType {
    HANDWRITING_STROKE,
    TEXT_BLOCK,
    IMAGE_BLOCK,
    SHAPE_BLOCK,
    CHECKBOX_BLOCK,
    STICKER_BLOCK,
    AUDIO_BLOCK,
    LINK_BLOCK
}

data class NoteViewportState(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val zoom: Float = 1f
)

data class NoteDocument(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val canvasWidth: Float,
    val canvasHeight: Float,
    val viewportState: NoteViewportState,
    val elements: List<NoteDocumentElement>
)

sealed class NoteDocumentElement {
    abstract val id: String
    abstract val type: NoteElementType
    abstract val x: Float
    abstract val y: Float
    abstract val width: Float
    abstract val height: Float
    abstract val rotation: Float
    abstract val scale: Float
    abstract val zIndex: Int
}

data class StrokeElement(
    override val id: String,
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    override val rotation: Float = 0f,
    override val scale: Float = 1f,
    override val zIndex: Int = 0,
    val points: List<StrokePointElement>,
    @ColorInt val color: Int,
    val strokeWidth: Float,
    val toolType: NoteEditorTool
) : NoteDocumentElement() {
    override val type: NoteElementType = NoteElementType.HANDWRITING_STROKE
}

data class StrokePointElement(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val timestamp: Long
)

data class TextElement(
    override val id: String,
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    override val rotation: Float = 0f,
    override val scale: Float = 1f,
    override val zIndex: Int = 0,
    val text: String,
    val textSizeSp: Float,
    @ColorInt val color: Int,
    val bold: Boolean = false,
    val italic: Boolean = false
) : NoteDocumentElement() {
    override val type: NoteElementType = NoteElementType.TEXT_BLOCK
}

data class ImageElement(
    override val id: String,
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    override val rotation: Float = 0f,
    override val scale: Float = 1f,
    override val zIndex: Int = 0,
    val imagePath: String?
) : NoteDocumentElement() {
    override val type: NoteElementType = NoteElementType.IMAGE_BLOCK
}

data class ShapeElement(
    override val id: String,
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    override val rotation: Float = 0f,
    override val scale: Float = 1f,
    override val zIndex: Int = 0,
    @ColorInt val strokeColor: Int,
    @ColorInt val fillColor: Int
) : NoteDocumentElement() {
    override val type: NoteElementType = NoteElementType.SHAPE_BLOCK
}
