package com.graphite.app.feature.workspace.history

import com.graphite.app.feature.workspace.model.InkStroke

sealed interface CanvasAction {
    data class AddStroke(val stroke: InkStroke) : CanvasAction
    data class EraseStrokes(val erasedItems: List<IndexedStroke>) : CanvasAction
    data class ClearPage(val previousStrokes: List<InkStroke>) : CanvasAction
}

data class IndexedStroke(
    val index: Int,
    val stroke: InkStroke
)

class StrokeHistoryManager(private val maxCapacity: Int = 50) {
    private val undoStack = ArrayDeque<CanvasAction>()
    private val redoStack = ArrayDeque<CanvasAction>()

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    fun recordAddStroke(stroke: InkStroke) {
        pushAction(CanvasAction.AddStroke(stroke))
    }

    fun recordEraseStrokes(erasedItems: List<IndexedStroke>) {
        if (erasedItems.isEmpty()) return
        pushAction(CanvasAction.EraseStrokes(erasedItems))
    }

    fun recordClearPage(previousStrokes: List<InkStroke>) {
        if (previousStrokes.isEmpty()) return
        pushAction(CanvasAction.ClearPage(previousStrokes))
    }

    private fun pushAction(action: CanvasAction) {
        if (undoStack.size >= maxCapacity) {
            undoStack.removeFirst()
        }
        undoStack.addLast(action)
        redoStack.clear()
    }

    /**
     * Executes undo operation on the current strokes list.
     * Returns the updated list of strokes, or the same list if undo is impossible.
     */
    fun undo(currentStrokes: List<InkStroke>): List<InkStroke> {
        if (undoStack.isEmpty()) return currentStrokes
        val action = undoStack.removeLast()
        redoStack.addLast(action)

        val updated = currentStrokes.toMutableList()
        when (action) {
            is CanvasAction.AddStroke -> {
                // Undo adding a stroke: remove the added stroke
                updated.removeAll { it.id == action.stroke.id }
            }
            is CanvasAction.EraseStrokes -> {
                // Undo erasing strokes: reinsert them at their original indices (sorted by original index)
                val sorted = action.erasedItems.sortedBy { it.index }
                for (item in sorted) {
                    val targetIndex = item.index.coerceIn(0, updated.size)
                    updated.add(targetIndex, item.stroke)
                }
            }
            is CanvasAction.ClearPage -> {
                // Undo clear page: restore all previously cleared strokes
                return action.previousStrokes
            }
        }
        return updated
    }

    /**
     * Executes redo operation on the current strokes list.
     * Returns the updated list of strokes, or the same list if redo is impossible.
     */
    fun redo(currentStrokes: List<InkStroke>): List<InkStroke> {
        if (redoStack.isEmpty()) return currentStrokes
        val action = redoStack.removeLast()
        undoStack.addLast(action)

        val updated = currentStrokes.toMutableList()
        when (action) {
            is CanvasAction.AddStroke -> {
                // Redo add stroke: append the stroke again
                updated.add(action.stroke)
            }
            is CanvasAction.EraseStrokes -> {
                // Redo erase strokes: remove those strokes again
                val idsToRemove = action.erasedItems.map { it.stroke.id }.toSet()
                updated.removeAll { it.id in idsToRemove }
            }
            is CanvasAction.ClearPage -> {
                // Redo clear page: clear all strokes
                return emptyList()
            }
        }
        return updated
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
