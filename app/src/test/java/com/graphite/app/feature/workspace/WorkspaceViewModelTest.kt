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

    @Test
    fun testDocumentStrokeIsolationBetweenDifferentNotebooks() {
        val vm = WorkspaceViewModel()
        val noteA = com.graphite.app.data.repository.GraphiteRepository.createNotebook("Document A")
        val noteB = com.graphite.app.data.repository.GraphiteRepository.createNotebook("Document B")

        // 1. Load Document A and add a stroke
        vm.loadNotebook(noteA.id, emptyList())
        val strokeA = InkStroke(
            id = "stroke-doc-A",
            points = listOf(InkPoint(10f, 10f), InkPoint(50f, 50f))
        )
        vm.addStroke(strokeA)
        assertEquals(1, vm.uiState.value.strokes.size)

        val savedNoteA = com.graphite.app.data.repository.GraphiteRepository.getNotebook(noteA.id)
        assertNotNull(savedNoteA)
        assertEquals(1, savedNoteA!!.initialStrokes.size)
        assertEquals("stroke-doc-A", savedNoteA.initialStrokes.first().id)

        // 2. Switch to Document B: must be completely empty, strokes from Document A must NOT bleed into B
        vm.loadNotebook(noteB.id, emptyList())
        assertEquals(0, vm.uiState.value.strokes.size)

        // 3. Add stroke to Document B
        val strokeB = InkStroke(
            id = "stroke-doc-B",
            points = listOf(InkPoint(80f, 80f), InkPoint(90f, 90f))
        )
        vm.addStroke(strokeB)
        assertEquals(1, vm.uiState.value.strokes.size)

        val savedNoteB = com.graphite.app.data.repository.GraphiteRepository.getNotebook(noteB.id)
        assertNotNull(savedNoteB)
        assertEquals(1, savedNoteB!!.initialStrokes.size)
        assertEquals("stroke-doc-B", savedNoteB.initialStrokes.first().id)

        // Verify Document A was not polluted by Document B
        val freshNoteA = com.graphite.app.data.repository.GraphiteRepository.getNotebook(noteA.id)
        assertEquals(1, freshNoteA!!.initialStrokes.size)
        assertEquals("stroke-doc-A", freshNoteA.initialStrokes.first().id)

        // 4. Switch back to Document A and load its saved strokes
        vm.loadNotebook(noteA.id, freshNoteA.initialStrokes)
        assertEquals(1, vm.uiState.value.strokes.size)
        assertEquals("stroke-doc-A", vm.uiState.value.strokes.first().id)
    }

    @Test
    fun testDeleteStrokesViaLasso() {
        val vm = WorkspaceViewModel()
        val s1 = InkStroke(id = "s1", points = listOf(InkPoint(1f, 1f)))
        val s2 = InkStroke(id = "s2", points = listOf(InkPoint(2f, 2f)))
        val s3 = InkStroke(id = "s3", points = listOf(InkPoint(3f, 3f)))

        vm.addStroke(s1)
        vm.addStroke(s2)
        vm.addStroke(s3)
        assertEquals(3, vm.uiState.value.strokes.size)

        // Delete middle stroke (index 1: s2)
        vm.deleteStrokes(setOf(1))
        assertEquals(2, vm.uiState.value.strokes.size)
        assertEquals("s1", vm.uiState.value.strokes[0].id)
        assertEquals("s3", vm.uiState.value.strokes[1].id)

        // Undo restores all 3 strokes
        vm.undo()
        assertEquals(3, vm.uiState.value.strokes.size)
    }
}
