package com.graphite.app.feature.workspace

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import com.graphite.app.feature.diagnostic.InputModePolicy
import com.graphite.app.feature.workspace.history.IndexedStroke
import com.graphite.app.feature.workspace.history.StrokeHistoryManager
import com.graphite.app.feature.workspace.model.InkStroke
import com.graphite.app.feature.workspace.model.InkToolType
import com.graphite.app.feature.workspace.model.PaperStyle
import com.graphite.app.feature.workspace.model.WorkspaceColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class WorkspaceUiState(
    val strokes: List<InkStroke> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val selectedTool: InkToolType = InkToolType.PEN,
    val selectedColor: Color = WorkspaceColors.CoralOrange,
    val selectedStrokeWidth: Float = 4.0f,
    val paperStyle: PaperStyle = PaperStyle.RULED,
    val inputPolicy: InputModePolicy = InputModePolicy.ACTIVE_PALM_GUARD,
    val showClearDialog: Boolean = false,
    val isStylusActive: Boolean = false
)

class WorkspaceViewModel : ViewModel() {

    private val historyManager = StrokeHistoryManager(maxCapacity = 60)

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    fun addStroke(stroke: InkStroke) {
        if (stroke.points.isEmpty()) return
        historyManager.recordAddStroke(stroke)
        _uiState.update { current ->
            current.copy(
                strokes = current.strokes + stroke,
                canUndo = historyManager.canUndo,
                canRedo = historyManager.canRedo
            )
        }
    }

    fun eraseAt(x: Float, y: Float, eraserRadius: Float = 28f, pageIndex: Int = -1) {
        val currentStrokes = _uiState.value.strokes
        val erasedItems = mutableListOf<IndexedStroke>()
        val remainingStrokes = mutableListOf<InkStroke>()

        for (i in currentStrokes.indices) {
            val stroke = currentStrokes[i]
            val matchesPage = pageIndex < 0 || stroke.pageIndex == pageIndex
            if (matchesPage && stroke.intersectsPoint(x, y, eraserRadius)) {
                erasedItems.add(IndexedStroke(i, stroke))
            } else {
                remainingStrokes.add(stroke)
            }
        }

        if (erasedItems.isNotEmpty()) {
            historyManager.recordEraseStrokes(erasedItems)
            _uiState.update { current ->
                current.copy(
                    strokes = remainingStrokes,
                    canUndo = historyManager.canUndo,
                    canRedo = historyManager.canRedo
                )
            }
        }
    }

    fun undo() {
        if (!historyManager.canUndo) return
        val updated = historyManager.undo(_uiState.value.strokes)
        _uiState.update { current ->
            current.copy(
                strokes = updated,
                canUndo = historyManager.canUndo,
                canRedo = historyManager.canRedo
            )
        }
    }

    fun redo() {
        if (!historyManager.canRedo) return
        val updated = historyManager.redo(_uiState.value.strokes)
        _uiState.update { current ->
            current.copy(
                strokes = updated,
                canUndo = historyManager.canUndo,
                canRedo = historyManager.canRedo
            )
        }
    }

    fun requestClearPage() {
        if (_uiState.value.strokes.isEmpty()) return
        _uiState.update { it.copy(showClearDialog = true) }
    }

    fun dismissClearDialog() {
        _uiState.update { it.copy(showClearDialog = false) }
    }

    fun confirmClearPage() {
        val currentStrokes = _uiState.value.strokes
        if (currentStrokes.isNotEmpty()) {
            historyManager.recordClearPage(currentStrokes)
            _uiState.update { current ->
                current.copy(
                    strokes = emptyList(),
                    canUndo = historyManager.canUndo,
                    canRedo = historyManager.canRedo,
                    showClearDialog = false
                )
            }
        } else {
            _uiState.update { it.copy(showClearDialog = false) }
        }
    }

    fun selectTool(tool: InkToolType) {
        _uiState.update { it.copy(selectedTool = tool) }
    }

    fun selectColor(color: Color) {
        _uiState.update { it.copy(selectedColor = color, selectedTool = InkToolType.PEN) }
    }

    fun selectStrokeWidth(width: Float) {
        _uiState.update { it.copy(selectedStrokeWidth = width) }
    }

    fun selectPaperStyle(style: PaperStyle) {
        _uiState.update { it.copy(paperStyle = style) }
    }

    fun setInputPolicy(policy: InputModePolicy) {
        _uiState.update { it.copy(inputPolicy = policy) }
    }

    fun setStylusActive(isActive: Boolean) {
        if (_uiState.value.isStylusActive != isActive) {
            _uiState.update { it.copy(isStylusActive = isActive) }
        }
    }
}
