package app.pdfdoc.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BarLayoutTest {

    @Test
    fun defaultEncodesEmpty() {
        assertEquals("", BarLayout().encode())
        assertEquals(BarLayout(), BarLayout.decode(""))
    }

    @Test
    fun roundTripKeepsOrderAndHidden() {
        val layout = BarLayout().move(BarItem.PEN.ordinal, 0).toggle(BarItem.MEASURE_AREA)
        val back = BarLayout.decode(layout.encode())
        assertEquals(BarItem.PEN, back.order.first())
        assertTrue(BarItem.MEASURE_AREA in back.hidden)
        assertTrue(BarItem.MEASURE_AREA !in back.visible)
        assertEquals(listOf(BarItem.MEASURE_AREA), back.more)
    }

    @Test
    fun unknownDroppedAndNewItemsAppended() {
        val back = BarLayout.decode("PEN,-GONE,SELECT")
        assertEquals(listOf(BarItem.PEN, BarItem.SELECT), back.order.take(2))
        assertEquals(BarItem.entries.size, back.order.size)
        assertTrue(back.hidden.isEmpty())
    }

    @Test
    fun cannotHideEverything() {
        var layout = BarLayout()
        BarItem.entries.forEach { layout = layout.toggle(it) }
        assertEquals(1, layout.visible.size)
    }
}
