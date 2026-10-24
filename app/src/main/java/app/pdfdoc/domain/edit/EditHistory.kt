package app.pdfdoc.domain.edit

/** Snapshot-based undo/redo over an immutable list of edits. */
class EditHistory(initial: List<Edit> = emptyList(), private val limit: Int = 100) {
    var current: List<Edit> = initial
        private set

    private val undoStack = ArrayDeque<List<Edit>>()
    private val redoStack = ArrayDeque<List<Edit>>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun push(next: List<Edit>) {
        if (next == current) return
        undoStack.addLast(current)
        while (undoStack.size > limit) undoStack.removeFirst()
        current = next
        redoStack.clear()
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(current)
        current = previous
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(current)
        current = next
        return true
    }

    fun reset(list: List<Edit>) {
        current = list
        undoStack.clear()
        redoStack.clear()
    }
}
