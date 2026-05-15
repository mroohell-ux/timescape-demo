package com.example.timescapedemo

private const val DEFAULT_UNDO_DEPTH = 80

interface NoteCommand {
    fun apply(document: NoteDocument)
    fun revert(document: NoteDocument)
}

class AddElementCommand(private val element: NoteElement) : NoteCommand {
    override fun apply(document: NoteDocument) {
        document.elements.removeAll { it.id == element.id }
        document.elements.add(element)
        document.updatedAt = System.currentTimeMillis()
    }

    override fun revert(document: NoteDocument) {
        document.elements.removeAll { it.id == element.id }
        document.updatedAt = System.currentTimeMillis()
    }
}

class RemoveElementCommand(private val element: NoteElement) : NoteCommand {
    override fun apply(document: NoteDocument) {
        document.elements.removeAll { it.id == element.id }
        document.updatedAt = System.currentTimeMillis()
    }

    override fun revert(document: NoteDocument) {
        document.elements.removeAll { it.id == element.id }
        document.elements.add(element)
        document.updatedAt = System.currentTimeMillis()
    }
}

class UndoRedoManager(private val maxDepth: Int = DEFAULT_UNDO_DEPTH) {
    private val undoStack = ArrayDeque<NoteCommand>()
    private val redoStack = ArrayDeque<NoteCommand>()

    fun execute(command: NoteCommand, document: NoteDocument) {
        command.apply(document)
        undoStack.addLast(command)
        while (undoStack.size > maxDepth) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(document: NoteDocument): Boolean {
        val command = undoStack.removeLastOrNull() ?: return false
        command.revert(document)
        redoStack.addLast(command)
        return true
    }

    fun redo(document: NoteDocument): Boolean {
        val command = redoStack.removeLastOrNull() ?: return false
        command.apply(document)
        undoStack.addLast(command)
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
}

class NoteDocumentState(val document: NoteDocument = NoteDocument())

data class ToolState(
    var activeTool: NoteTool = NoteTool.PEN,
    var color: Int = android.graphics.Color.rgb(42, 36, 56),
    var strokeWidth: Float = 6f
)

data class SelectionState(val selectedElementIds: MutableSet<String> = mutableSetOf())

class NoteEditorViewModel(
    val documentState: NoteDocumentState = NoteDocumentState(),
    val toolState: ToolState = ToolState(),
    val selectionState: SelectionState = SelectionState(),
    val undoRedoManager: UndoRedoManager = UndoRedoManager()
) {
    val document: NoteDocument get() = documentState.document

    fun addElement(element: NoteElement) {
        undoRedoManager.execute(AddElementCommand(element), document)
    }

    fun removeElement(element: NoteElement) {
        undoRedoManager.execute(RemoveElementCommand(element), document)
    }
}
