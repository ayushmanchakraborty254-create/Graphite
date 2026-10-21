package com.graphite.app.feature.workspace

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import com.graphite.app.data.repository.GraphiteRepository
import com.graphite.app.feature.diagnostic.InputModePolicy
import com.graphite.app.feature.workspace.history.IndexedStroke
import com.graphite.app.feature.workspace.history.StrokeHistoryManager
import com.graphite.app.feature.workspace.model.InkPoint
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
    val inputPolicy: InputModePolicy = InputModePolicy.STYLUS_ONLY_FOR_INK,
    val showClearDialog: Boolean = false,
    val isStylusActive: Boolean = false
)

class WorkspaceViewModel : ViewModel() {

    private val historyManager = StrokeHistoryManager(maxCapacity = 60)

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    private var currentNotebookId: String? = null

    fun loadNotebook(id: String, initialStrokes: List<InkStroke> = emptyList()) {
        currentNotebookId = id
        historyManager.clear()
        _uiState.update { current ->
            current.copy(
                strokes = initialStrokes,
                canUndo = false,
                canRedo = false,
                showClearDialog = false
            )
        }
    }

    fun addStroke(stroke: InkStroke) {
        if (stroke.points.isEmpty()) return
        historyManager.recordAddStroke(stroke)
        val updatedStrokes = _uiState.value.strokes + stroke
        _uiState.update { current ->
            current.copy(
                strokes = updatedStrokes,
                canUndo = historyManager.canUndo,
                canRedo = historyManager.canRedo
            )
        }
        currentNotebookId?.let { id ->
            GraphiteRepository.updateNotebookStrokes(id, updatedStrokes)
        }
    }

    fun deletePage(pageIndexToDelete: Int) {
        val currentStrokes = _uiState.value.strokes
        val remainingStrokes = currentStrokes.mapNotNull { stroke ->
            when {
                stroke.pageIndex == pageIndexToDelete -> null
                stroke.pageIndex > pageIndexToDelete -> stroke.copy(pageIndex = stroke.pageIndex - 1)
                else -> stroke
            }
        }
        historyManager.clear()
        _uiState.update { current ->
            current.copy(
                strokes = remainingStrokes,
                canUndo = false,
                canRedo = false
            )
        }
        currentNotebookId?.let { id ->
            GraphiteRepository.updateNotebookStrokes(id, remainingStrokes)
        }
    }

    enum class EraserModeType {
        WHOLE_STROKE,
        PRECISION_AREA
    }

    fun eraseAt(
        x: Float,
        y: Float,
        eraserRadius: Float = 28f,
        pageIndex: Int = -1,
        mode: EraserModeType = EraserModeType.WHOLE_STROKE
    ) {
        val currentStrokes = _uiState.value.strokes
        val erasedItems = mutableListOf<IndexedStroke>()
        val remainingStrokes = mutableListOf<InkStroke>()
        var didModify = false

        for (i in currentStrokes.indices) {
            val stroke = currentStrokes[i]
            val matchesPage = pageIndex < 0 || stroke.pageIndex == pageIndex
            if (matchesPage && stroke.intersectsPoint(x, y, eraserRadius)) {
                didModify = true
                erasedItems.add(IndexedStroke(i, stroke))
                if (mode == EraserModeType.PRECISION_AREA) {
                    val sliced = sliceStrokePrecision(stroke, x, y, eraserRadius)
                    remainingStrokes.addAll(sliced)
                }
            } else {
                remainingStrokes.add(stroke)
            }
        }

        if (didModify) {
            historyManager.recordEraseStrokes(erasedItems)
            _uiState.update { current ->
                current.copy(
                    strokes = remainingStrokes,
                    canUndo = historyManager.canUndo,
                    canRedo = historyManager.canRedo
                )
            }
            currentNotebookId?.let { id ->
                GraphiteRepository.updateNotebookStrokes(id, remainingStrokes)
            }
        }
    }

    private fun sliceStrokePrecision(
        stroke: InkStroke,
        cx: Float,
        cy: Float,
        radius: Float
    ): List<InkStroke> {
        val effectiveR = radius + (stroke.baseWidth * 0.5f).coerceAtLeast(1f)
        val rSq = effectiveR * effectiveR
        val pts = stroke.points
        if (pts.size < 2) return emptyList()

        val subStrokes = mutableListOf<MutableList<InkPoint>>()
        var currentSub = mutableListOf<InkPoint>()

        fun finishSub() {
            if (currentSub.size >= 2) {
                subStrokes.add(currentSub)
            }
            currentSub = mutableListOf()
        }

        for (k in 0 until pts.size - 1) {
            val p1 = pts[k]
            val p2 = pts[k + 1]

            val d1Sq = (p1.x - cx) * (p1.x - cx) + (p1.y - cy) * (p1.y - cy)
            val d2Sq = (p2.x - cx) * (p2.x - cx) + (p2.y - cy) * (p2.y - cy)

            val in1 = d1Sq <= rSq
            val in2 = d2Sq <= rSq

            if (!in1 && !in2) {
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val fx = p1.x - cx
                val fy = p1.y - cy

                val a = dx * dx + dy * dy
                val b = 2f * (fx * dx + fy * dy)
                val c = fx * fx + fy * fy - rSq

                val discriminant = b * b - 4f * a * c
                if (a > 0.0001f && discriminant > 0f) {
                    val sqrtDisc = Math.sqrt(discriminant.toDouble()).toFloat()
                    val t1 = (-b - sqrtDisc) / (2f * a)
                    val t2 = (-b + sqrtDisc) / (2f * a)

                    if (t1 in 0.001f..0.999f && t2 in 0.001f..0.999f && t1 < t2) {
                        if (currentSub.isEmpty()) currentSub.add(p1)
                        val q1 = InkPoint(
                            x = p1.x + t1 * dx,
                            y = p1.y + t1 * dy,
                            pressure = p1.pressure + t1 * (p2.pressure - p1.pressure),
                            timestamp = (p1.timestamp + t1 * (p2.timestamp - p1.timestamp)).toLong()
                        )
                        currentSub.add(q1)
                        finishSub()

                        val q2 = InkPoint(
                            x = p1.x + t2 * dx,
                            y = p1.y + t2 * dy,
                            pressure = p1.pressure + t2 * (p2.pressure - p1.pressure),
                            timestamp = (p1.timestamp + t2 * (p2.timestamp - p1.timestamp)).toLong()
                        )
                        currentSub.add(q2)
                        currentSub.add(p2)
                        continue
                    }
                }

                if (currentSub.isEmpty()) currentSub.add(p1)
                currentSub.add(p2)
            } else if (!in1 && in2) {
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val fx = p1.x - cx
                val fy = p1.y - cy

                val a = dx * dx + dy * dy
                val b = 2f * (fx * dx + fy * dy)
                val c = fx * fx + fy * fy - rSq
                val discriminant = (b * b - 4f * a * c).coerceAtLeast(0f)
                val t = if (a > 0.0001f) {
                    ((-b - Math.sqrt(discriminant.toDouble()).toFloat()) / (2f * a)).coerceIn(0f, 1f)
                } else 0f

                if (currentSub.isEmpty()) currentSub.add(p1)
                val q = InkPoint(
                    x = p1.x + t * dx,
                    y = p1.y + t * dy,
                    pressure = p1.pressure + t * (p2.pressure - p1.pressure),
                    timestamp = (p1.timestamp + t * (p2.timestamp - p1.timestamp)).toLong()
                )
                currentSub.add(q)
                finishSub()
            } else if (in1 && !in2) {
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val fx = p1.x - cx
                val fy = p1.y - cy

                val a = dx * dx + dy * dy
                val b = 2f * (fx * dx + fy * dy)
                val c = fx * fx + fy * fy - rSq
                val discriminant = (b * b - 4f * a * c).coerceAtLeast(0f)
                val t = if (a > 0.0001f) {
                    ((-b + Math.sqrt(discriminant.toDouble()).toFloat()) / (2f * a)).coerceIn(0f, 1f)
                } else 1f

                val q = InkPoint(
                    x = p1.x + t * dx,
                    y = p1.y + t * dy,
                    pressure = p1.pressure + t * (p2.pressure - p1.pressure),
                    timestamp = (p1.timestamp + t * (p2.timestamp - p1.timestamp)).toLong()
                )
                currentSub.add(q)
                currentSub.add(p2)
            } else {
                finishSub()
            }
        }
        finishSub()

        return subStrokes.map { subPts ->
            stroke.copy(
                id = java.util.UUID.randomUUID().toString(),
                points = subPts
            )
        }
    }

    fun translateStrokes(indices: Set<Int>, dx: Float, dy: Float) {
        if (indices.isEmpty() || (dx == 0f && dy == 0f)) return
        val currentStrokes = _uiState.value.strokes.toMutableList()
        for (idx in indices) {
            if (idx in currentStrokes.indices) {
                val s = currentStrokes[idx]
                val translatedPoints = s.points.map { p ->
                    p.copy(x = p.x + dx, y = p.y + dy)
                }
                currentStrokes[idx] = s.copy(points = translatedPoints)
            }
        }
        _uiState.update { it.copy(strokes = currentStrokes) }
        currentNotebookId?.let { id ->
            GraphiteRepository.updateNotebookStrokes(id, currentStrokes)
        }
    }


    fun deleteStrokes(indices: Set<Int>) {
        if (indices.isEmpty()) return
        val currentStrokes = _uiState.value.strokes
        val erasedItems = mutableListOf<IndexedStroke>()
        val remainingStrokes = mutableListOf<InkStroke>()

        for (i in currentStrokes.indices) {
            val stroke = currentStrokes[i]
            if (indices.contains(i)) {
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
            currentNotebookId?.let { id ->
                GraphiteRepository.updateNotebookStrokes(id, remainingStrokes)
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
        currentNotebookId?.let { id ->
            GraphiteRepository.updateNotebookStrokes(id, updated)
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
        currentNotebookId?.let { id ->
            GraphiteRepository.updateNotebookStrokes(id, updated)
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
            currentNotebookId?.let { id ->
                GraphiteRepository.updateNotebookStrokes(id, emptyList())
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
