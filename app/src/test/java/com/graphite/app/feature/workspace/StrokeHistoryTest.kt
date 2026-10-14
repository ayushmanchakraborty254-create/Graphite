package com.graphite.app.feature.workspace

import androidx.compose.ui.graphics.Color
import com.graphite.app.feature.workspace.history.IndexedStroke
import com.graphite.app.feature.workspace.history.StrokeHistoryManager
import com.graphite.app.feature.workspace.model.InkPoint
import com.graphite.app.feature.workspace.model.InkStroke
import com.graphite.app.feature.workspace.model.InkToolType
import org.junit.Assert.*
import org.junit.Test

class StrokeHistoryTest {

    private fun sampleStroke(id: String, x: Float = 0f, y: Float = 0f): InkStroke {
        return InkStroke(
            id = id,
            points = listOf(InkPoint(x, y), InkPoint(x + 10f, y + 10f)),
            baseWidth = 4f,
            color = Color.White,
            toolType = InkToolType.PEN
        )
    }

    @Test
    fun testUndoRedoAddStroke() {
        val history = StrokeHistoryManager(maxCapacity = 10)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)

        val stroke1 = sampleStroke("1")
        var strokes = listOf(stroke1)
        history.recordAddStroke(stroke1)

        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        // Undo
        strokes = history.undo(strokes)
        assertTrue(strokes.isEmpty())
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)

        // Redo
        strokes = history.redo(strokes)
        assertEquals(1, strokes.size)
        assertEquals("1", strokes[0].id)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun testUndoRedoEraseStrokesPreservesIndices() {
        val history = StrokeHistoryManager()
        val s1 = sampleStroke("1")
        val s2 = sampleStroke("2")
        val s3 = sampleStroke("3")

        var strokes = listOf(s1, s2, s3)

        // Erase middle stroke (index 1)
        val erased = listOf(IndexedStroke(1, s2))
        strokes = listOf(s1, s3)
        history.recordEraseStrokes(erased)

        assertTrue(history.canUndo)

        // Undo erase should restore s2 back at index 1
        strokes = history.undo(strokes)
        assertEquals(3, strokes.size)
        assertEquals("1", strokes[0].id)
        assertEquals("2", strokes[1].id)
        assertEquals("3", strokes[2].id)

        // Redo erase should remove s2 again
        strokes = history.redo(strokes)
        assertEquals(2, strokes.size)
        assertEquals("1", strokes[0].id)
        assertEquals("3", strokes[1].id)
    }

    @Test
    fun testUndoRedoClearPage() {
        val history = StrokeHistoryManager()
        val s1 = sampleStroke("1")
        val s2 = sampleStroke("2")
        val previousStrokes = listOf(s1, s2)

        history.recordClearPage(previousStrokes)
        var strokes = emptyList<InkStroke>()

        assertTrue(history.canUndo)

        // Undo clear page restores all strokes
        strokes = history.undo(strokes)
        assertEquals(2, strokes.size)
        assertEquals("1", strokes[0].id)
        assertEquals("2", strokes[1].id)

        // Redo clear page empties list again
        strokes = history.redo(strokes)
        assertTrue(strokes.isEmpty())
    }

    @Test
    fun testHistoryCapacityBounds() {
        val maxCap = 3
        val history = StrokeHistoryManager(maxCapacity = maxCap)

        val s1 = sampleStroke("1")
        val s2 = sampleStroke("2")
        val s3 = sampleStroke("3")
        val s4 = sampleStroke("4")

        history.recordAddStroke(s1)
        history.recordAddStroke(s2)
        history.recordAddStroke(s3)
        history.recordAddStroke(s4) // Should evict s1 from history

        var strokes = listOf(s1, s2, s3, s4)

        // We can undo 3 times (s4, s3, s2)
        strokes = history.undo(strokes) // undo s4
        assertEquals(3, strokes.size)
        strokes = history.undo(strokes) // undo s3
        assertEquals(2, strokes.size)
        strokes = history.undo(strokes) // undo s2
        assertEquals(1, strokes.size)

        // Cannot undo further because s1 was evicted
        assertFalse(history.canUndo)
    }
}
