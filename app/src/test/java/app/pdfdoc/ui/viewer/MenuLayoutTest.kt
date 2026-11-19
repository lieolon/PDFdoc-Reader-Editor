package app.pdfdoc.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuLayoutTest {

    @Test
    fun defaultKeepsTesterChoiceUnderMore() {
        val l = MenuLayout()
        assertEquals(8, l.main.size)
        assertEquals(7, l.extra.size)
        assertEquals("", l.encode())
        assertEquals(l, MenuLayout.decode(""))
    }

    @Test
    fun roundTripAfterMoveAndToggle() {
        val l = MenuLayout().toggle(MenuItem.NIGHT).move(MenuItem.NIGHT, up = true)
        val back = MenuLayout.decode(l.encode())
        assertTrue(MenuItem.NIGHT in back.main)
        assertEquals(l.main, back.main)
        assertEquals(l.extra, back.extra)
    }

    @Test
    fun moveStaysWithinGroup() {
        val l = MenuLayout()
        val first = l.main.first()
        assertEquals(l, l.move(first, up = true))
        val moved = l.move(first, up = false)
        assertEquals(first, moved.main[1])
    }

    @Test
    fun newItemsGoToMore() {
        val back = MenuLayout.decode("MODE,TOOLS")
        assertEquals(listOf(MenuItem.MODE, MenuItem.TOOLS), back.main)
        assertEquals(MenuItem.entries.size - 2, back.extra.size)
    }

    @Test
    fun menuNeverEmpty() {
        var l = MenuLayout()
        MenuItem.entries.forEach { if (it !in l.more) l = l.toggle(it) }
        assertEquals(1, l.main.size)
    }
}
