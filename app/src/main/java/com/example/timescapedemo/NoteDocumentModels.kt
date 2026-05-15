package com.example.timescapedemo

import androidx.annotation.ColorInt
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Domain model for Timescape's rich infinite notebook editor.  All positions and
 * sizes are stored in virtual canvas coordinates, never in screen pixels.
 */
data class NoteDocument(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Untitled memory note",
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt,
    var canvasWidth: Float = 4096f,
    var canvasHeight: Float = 8192f,
    var viewportState: ViewportState = ViewportState(),
    val elements: MutableList<NoteElement> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("canvasWidth", canvasWidth.toDouble())
        put("canvasHeight", canvasHeight.toDouble())
        put("viewport", viewportState.toJson())
        put("elements", JSONArray().also { array -> elements.forEach { array.put(it.toJson()) } })
    }
}

data class ViewportState(
    var offsetX: Float = 0f,
    var offsetY: Float = 0f,
    var zoom: Float = 1f
) {
    fun screenToCanvas(screenX: Float, screenY: Float): CanvasPoint =
        CanvasPoint((screenX / zoom) + offsetX, (screenY / zoom) + offsetY)

    fun canvasToScreen(canvasX: Float, canvasY: Float): CanvasPoint =
        CanvasPoint((canvasX - offsetX) * zoom, (canvasY - offsetY) * zoom)

    fun toJson(): JSONObject = JSONObject().apply {
        put("offsetX", offsetX.toDouble())
        put("offsetY", offsetY.toDouble())
        put("zoom", zoom.toDouble())
    }
}

data class CanvasPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("pressure", pressure.toDouble())
        put("timestamp", timestamp)
    }
}

enum class NoteElementType { HANDWRITING_STROKE, TEXT_BLOCK, IMAGE_BLOCK, SHAPE_BLOCK, CHECKBOX_BLOCK, STICKER_BLOCK, AUDIO_BLOCK, LINK_BLOCK }

enum class NoteTool { PEN, ERASER, TEXT, IMAGE, SELECT, SHAPE, HIGHLIGHTER, PAN }

sealed class NoteElement(
    open val id: String,
    open val type: NoteElementType,
    open var x: Float,
    open var y: Float,
    open var width: Float,
    open var height: Float,
    open var rotation: Float = 0f,
    open var scale: Float = 1f,
    open var zIndex: Int = 0,
    open var selected: Boolean = false
) {
    abstract fun toJson(): JSONObject
}

data class StrokeElement(
    override val id: String = UUID.randomUUID().toString(),
    val points: MutableList<CanvasPoint> = mutableListOf(),
    @ColorInt var color: Int,
    var strokeWidth: Float,
    var tool: NoteTool = NoteTool.PEN,
    override var x: Float = 0f,
    override var y: Float = 0f,
    override var width: Float = 0f,
    override var height: Float = 0f,
    override var rotation: Float = 0f,
    override var scale: Float = 1f,
    override var zIndex: Int = 0,
    override var selected: Boolean = false
) : NoteElement(id, NoteElementType.HANDWRITING_STROKE, x, y, width, height, rotation, scale, zIndex, selected) {
    fun recomputeBounds(padding: Float = strokeWidth) {
        val minX = points.minOfOrNull { it.x } ?: 0f
        val minY = points.minOfOrNull { it.y } ?: 0f
        val maxX = points.maxOfOrNull { it.x } ?: minX
        val maxY = points.maxOfOrNull { it.y } ?: minY
        x = minX - padding
        y = minY - padding
        width = (maxX - minX) + padding * 2f
        height = (maxY - minY) + padding * 2f
    }

    override fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("width", width.toDouble())
        put("height", height.toDouble())
        put("rotation", rotation.toDouble())
        put("scale", scale.toDouble())
        put("zIndex", zIndex)
        put("color", color)
        put("strokeWidth", strokeWidth.toDouble())
        put("tool", tool.name)
        put("points", JSONArray().also { array -> points.forEach { array.put(it.toJson()) } })
    }
}

data class TextElement(
    override val id: String = UUID.randomUUID().toString(),
    var text: String = "",
    var fontSize: Float = 18f,
    @ColorInt var color: Int,
    override var x: Float,
    override var y: Float,
    override var width: Float = 240f,
    override var height: Float = 80f,
    override var rotation: Float = 0f,
    override var scale: Float = 1f,
    override var zIndex: Int = 0,
    override var selected: Boolean = false
) : NoteElement(id, NoteElementType.TEXT_BLOCK, x, y, width, height, rotation, scale, zIndex, selected) {
    override fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("text", text)
        put("fontSize", fontSize.toDouble())
        put("color", color)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("width", width.toDouble())
        put("height", height.toDouble())
        put("rotation", rotation.toDouble())
        put("scale", scale.toDouble())
        put("zIndex", zIndex)
    }
}

data class ImageElement(
    override val id: String = UUID.randomUUID().toString(),
    val imageUri: String,
    override var x: Float,
    override var y: Float,
    override var width: Float,
    override var height: Float,
    override var rotation: Float = 0f,
    override var scale: Float = 1f,
    override var zIndex: Int = 0,
    override var selected: Boolean = false
) : NoteElement(id, NoteElementType.IMAGE_BLOCK, x, y, width, height, rotation, scale, zIndex, selected) {
    override fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("imageUri", imageUri)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("width", width.toDouble())
        put("height", height.toDouble())
        put("rotation", rotation.toDouble())
        put("scale", scale.toDouble())
        put("zIndex", zIndex)
    }
}

data class ShapeElement(
    override val id: String = UUID.randomUUID().toString(),
    val shape: String = "rectangle",
    @ColorInt var strokeColor: Int,
    @ColorInt var fillColor: Int,
    override var x: Float,
    override var y: Float,
    override var width: Float,
    override var height: Float,
    override var rotation: Float = 0f,
    override var scale: Float = 1f,
    override var zIndex: Int = 0,
    override var selected: Boolean = false
) : NoteElement(id, NoteElementType.SHAPE_BLOCK, x, y, width, height, rotation, scale, zIndex, selected) {
    override fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("shape", shape)
        put("strokeColor", strokeColor)
        put("fillColor", fillColor)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("width", width.toDouble())
        put("height", height.toDouble())
        put("rotation", rotation.toDouble())
        put("scale", scale.toDouble())
        put("zIndex", zIndex)
    }
}
