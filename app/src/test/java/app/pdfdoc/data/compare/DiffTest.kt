package app.pdfdoc.data.compare

import org.junit.Assert.assertEquals
import org.junit.Test

class DiffTest {

    private fun apply(a: List<String>, b: List<String>, ops: List<Diff.Op>): List<String> {
        val out = mutableListOf<String>()
        ops.forEach { op ->
            when (op) {
                is Diff.Op.Equal -> out += a[op.a]
                is Diff.Op.Insert -> out += b[op.b]
                is Diff.Op.Delete -> Unit
            }
        }
        return out
    }

    @Test
    fun scriptTurnsOldIntoNew() {
        val a = "the quick brown fox jumps over the lazy dog".split(' ')
        val b = "the quick red fox jumped over the very lazy dog".split(' ')
        val ops = Diff.diff(a, b, 100)
        assertEquals(b, apply(a, b, ops))
        assertEquals(7, ops.count { it is Diff.Op.Equal })
    }

    @Test
    fun handlesEmptyAndIdentical() {
        assertEquals(listOf("x"), apply(emptyList(), listOf("x"), Diff.diff(emptyList(), listOf("x"), 10)))
        val same = listOf("a", "b")
        assertEquals(true, Diff.diff(same, same, 10).all { it is Diff.Op.Equal })
    }

    @Test
    fun fallsBackWhenTooManyEdits() {
        val a = (0 until 50).map { "a$it" }
        val b = (0 until 50).map { "b$it" }
        val ops = Diff.diff(a, b, 5)
        assertEquals(b, apply(a, b, ops))
    }
}
