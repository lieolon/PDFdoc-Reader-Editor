package app.pdfdoc.domain.edit

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoreEditsTest {

    @Test
    fun recognizesStraightLine() {
        val points = (0..20).map { Pt(it * 10f, 100f + if (it % 2 == 0) 1f else -1f) }
        val (kind, ends) = ShapeRecognizer.recognize(points)!!
        assertEquals(ShapeKind.LINE, kind)
        assertEquals(0f, ends.first.x, 0.01f)
        assertEquals(200f, ends.second.x, 0.01f)
    }

    @Test
    fun recognizesRectangle() {
        val points = mutableListOf<Pt>()
        for (i in 0..20) points += Pt(i * 10f, 0f)
        for (i in 0..10) points += Pt(200f, i * 10f)
        for (i in 20 downTo 0) points += Pt(i * 10f, 100f)
        for (i in 10 downTo 0) points += Pt(0f, i * 10f)
        assertEquals(ShapeKind.RECT, ShapeRecognizer.recognize(points)!!.first)
    }

    @Test
    fun recognizesEllipse() {
        val points = (0..60).map { i ->
            val a = 2 * PI * i / 60
            Pt((100 + 80 * cos(a)).toFloat(), (100 + 40 * sin(a)).toFloat())
        }
        assertEquals(ShapeKind.OVAL, ShapeRecognizer.recognize(points)!!.first)
    }

    @Test
    fun keepsScribble() {
        val points = (0..40).map { i -> Pt(i * 5f, if (i % 4 < 2) 0f else 60f) }
        assertNull(ShapeRecognizer.recognize(points))
    }

    @Test
    fun measuresLengthAndArea() {
        val square = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(0f, 10f))
        assertEquals(30f, Measure.length(square, closed = false), 0.001f)
        assertEquals(100f, Measure.area(square), 0.001f)
        val m = MeasureEdit(1, 0, MeasureKind.DISTANCE, listOf(Pt(0f, 0f), Pt(Measure.POINTS_PER_CM * 2, 0f)), 0, unitsPerPoint = 5f / Measure.POINTS_PER_CM, unit = "m")
        assertEquals(10f, m.value, 0.001f)
    }

    @Test
    fun eraserRemovesOnlyTouchedStrokes() {
        val strokes = listOf(listOf(Pt(0f, 0f), Pt(100f, 0f)), listOf(Pt(0f, 50f), Pt(100f, 50f)))
        val left = eraseStrokes(strokes, listOf(Pt(50f, 2f)), 4f)
        assertEquals(1, left.size)
        assertEquals(50f, left.single().first().y, 0f)
    }

    @Test
    fun newEditsSurviveHelpers() {
        val link = LinkEdit(1, 0, Box(0f, 0f, 10f, 10f), "https://example.com")
        assertEquals(Box(5f, 5f, 15f, 15f), (link.translate(5f, 5f) as LinkEdit).box)
        assertEquals(3, (onPage(RedactEdit(1, 0, Box(0f, 0f, 1f, 1f)), 3)).page)
        val field = FieldEdit(1, 0, Box(0f, 0f, 10f, 10f), FieldKind.TEXT, "name")
        assertEquals(Box(0f, 0f, 20f, 20f), (resized(field, Box(0f, 0f, 20f, 20f)) as FieldEdit).box)
        assertEquals(true, field.appliedOnSave)
        assertEquals(false, link.appliedOnSave)
    }
}
