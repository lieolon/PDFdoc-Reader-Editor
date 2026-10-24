package app.pdfdoc.domain.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    private fun note(id: Long) = NoteEdit(id, 0, Pt(0f, 0f), "n$id", 0)

    @Test
    fun undoRedoWalksSnapshots() {
        val h = EditHistory()
        h.push(listOf(note(1)))
        h.push(listOf(note(1), note(2)))
        assertTrue(h.undo())
        assertEquals(listOf(note(1)), h.current)
        assertTrue(h.redo())
        assertEquals(2, h.current.size)
        assertFalse(h.redo())
    }

    @Test
    fun pushClearsRedo() {
        val h = EditHistory()
        h.push(listOf(note(1)))
        h.undo()
        h.push(listOf(note(3)))
        assertFalse(h.canRedo)
    }

    @Test
    fun limitDropsOldest() {
        val h = EditHistory(limit = 2)
        (1L..5L).forEach { h.push(listOf(note(it))) }
        assertTrue(h.undo())
        assertTrue(h.undo())
        assertFalse(h.undo())
    }

    @Test
    fun resizedShapeFollowsFrame() {
        val shape = ShapeEdit(1, 0, ShapeKind.LINE, Pt(0f, 0f), Pt(10f, 10f), 0, 2f)
        val r = resized(shape, Box(0f, 0f, 20f, 40f)) as ShapeEdit
        assertEquals(Pt(20f, 40f), r.end)
    }

    @Test
    fun resizedTextScalesFontByHeight() {
        val text = TextEdit(1, 0, Box(10f, 10f, 50f, 22f), "abc", 10f, 0)
        val r = resized(text, Box(10f, 10f, 200f, 34f)) as TextEdit
        assertEquals(20f, r.size, 0.001f)
        assertEquals(80f, r.box.width, 0.001f)
    }
}
