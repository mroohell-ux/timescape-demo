package com.example.timescapedemo

import android.graphics.Bitmap

/**
 * State-layer scaffolding for the full Timescape note workspace. The current
 * XML/View editor uses these models incrementally while future phases can move
 * this state behind a lifecycle ViewModel without changing the document schema.
 */
data class NoteDocumentState(
    val document: NoteDocument,
    val toolState: ToolState = ToolState(),
    val selectionState: SelectionState = SelectionState(),
    val isDirty: Boolean = false,
    val lastAutoSavedAt: Long = 0L
)

data class ToolState(
    val activeTool: NoteEditorTool = NoteEditorTool.PEN,
    val color: Int = android.graphics.Color.BLACK,
    val strokeWidthDp: Float = 4f,
    val highlighterWidthDp: Float = 10f
)

data class SelectionState(
    val selectedElementIds: Set<String> = emptySet(),
    val isLassoActive: Boolean = false
)

class UndoRedoManager<T> {
    private val undoStack = mutableListOf<UndoableCommand<T>>()
    private val redoStack = mutableListOf<UndoableCommand<T>>()

    fun execute(target: T, command: UndoableCommand<T>) {
        command.redo(target)
        undoStack.add(command)
        redoStack.clear()
    }

    fun undo(target: T): Boolean {
        if (undoStack.isEmpty()) return false
        val command = undoStack.removeAt(undoStack.lastIndex)
        command.undo(target)
        redoStack.add(command)
        return true
    }

    fun redo(target: T): Boolean {
        if (redoStack.isEmpty()) return false
        val command = redoStack.removeAt(redoStack.lastIndex)
        command.redo(target)
        undoStack.add(command)
        return true
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
}

interface UndoableCommand<T> {
    fun undo(target: T)
    fun redo(target: T)
}

interface NoteRepository {
    fun loadNote(id: String): NoteDocument?
    fun autoSave(note: NoteDocument)
    fun saveThumbnail(noteId: String, thumbnail: Bitmap)
}

object ThumbnailRenderer {
    const val DEFAULT_THUMBNAIL_SIZE_PX = 512
}
