package com.graphite.app.feature.workspace

import androidx.compose.ui.graphics.Color
import com.graphite.app.feature.workspace.model.InkPoint
import com.graphite.app.feature.workspace.model.InkStroke
import com.graphite.app.feature.workspace.model.InkToolType
import com.graphite.app.feature.workspace.model.PaperStyle
import org.junit.Assert.*
import org.junit.Test

class WorkspaceViewModelTest {

    @Test
    fun testAddStrokeAndUndoRedo() {
        val vm = WorkspaceViewModel()
        assertEquals(0, vm.uiState.value.strokes.size)
        assertFalse(vm.uiState.value.canUndo)
        assertFalse(vm.uiState.value.canRedo)

        val stroke = InkStroke(
            id = "stroke-1",
            points = listOf(InkPoint(10f, 10f), InkPoint(20f, 20f)),
            baseWidth = 4f
        )
        vm.addStroke(stroke)

        assertEquals(1, vm.uiState.value.strokes.size)
        assertTrue(vm.uiState.value.canUndo)
        assertFalse(vm.uiState.value.canRedo)

        // Undo
        vm.undo()
        assertEquals(0, vm.uiState.value.strokes.size)
        assertFalse(vm.uiState.value.canUndo)
        assertTrue(vm.uiState.value.canRedo)

        // Redo
        vm.redo()
        assertEquals(1, vm.uiState.value.strokes.size)
        assertTrue(vm.uiState.value.canUndo)
        assertFalse(vm.uiState.value.canRedo)
    }

    @Test
    fun testEraseAtIntersectsStroke() {
        val vm = WorkspaceViewModel()
        val stroke = InkStroke(
            id = "stroke-target",
            points = listOf(InkPoint(100f, 100f), InkPoint(200f, 100f)),
            baseWidth = 4f
        )
        vm.addStroke(stroke)
        assertEquals(1, vm.uiState.value.strokes.size)

        // Erase far away (at 500, 500) -> stroke remains
        vm.eraseAt(500f, 500f, 20f)
        assertEquals(1, vm.uiState.value.strokes.size)

        // Erase directly on segment (at 150, 100) -> stroke removed
        vm.eraseAt(150f, 100f, 20f)
        assertEquals(0, vm.uiState.value.strokes.size)

        // Undo restores stroke
        vm.undo()
        assertEquals(1, vm.uiState.value.strokes.size)
    }

    @Test
    fun testToolAndColorSelection() {
        val vm = WorkspaceViewModel()
        assertEquals(InkToolType.PEN, vm.uiState.value.selectedTool)

        vm.selectTool(InkToolType.ERASER)
        assertEquals(InkToolType.ERASER, vm.uiState.value.selectedTool)

        // Selecting a color automatically sets Pen tool
        val testColor = Color(0xFF3D5AFE)
        vm.selectColor(testColor)
        assertEquals(InkToolType.PEN, vm.uiState.value.selectedTool)
        assertEquals(testColor, vm.uiState.value.selectedColor)

        // Select paper style
        vm.selectPaperStyle(PaperStyle.GRID)
        assertEquals(PaperStyle.GRID, vm.uiState.value.paperStyle)
    }

    @Test
    fun testClearPageFlow() {
        val vm = WorkspaceViewModel()
        val stroke = InkStroke(
            id = "s1",
            points = listOf(InkPoint(5f, 5f), InkPoint(15f, 15f))
        )
        vm.addStroke(stroke)

        // Request clear opens dialog
        vm.requestClearPage()
        assertTrue(vm.uiState.value.showClearDialog)

        // Dismiss closes dialog without clearing
        vm.dismissClearDialog()
        assertFalse(vm.uiState.value.showClearDialog)
        assertEquals(1, vm.uiState.value.strokes.size)

        // Request and confirm
        vm.requestClearPage()
        vm.confirmClearPage()
        assertFalse(vm.uiState.value.showClearDialog)
        assertEquals(0, vm.uiState.value.strokes.size)

        // Undo restores strokes
        vm.undo()
        assertEquals(1, vm.uiState.value.strokes.size)
    }
}
