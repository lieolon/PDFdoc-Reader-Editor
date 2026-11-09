package app.pdfdoc.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizerTest {

    private val text = listOf(
        "Договор аренды квартиры заключён между арендодателем и арендатором на один год.",
        "Арендная плата составляет 40 000 рублей в месяц и вносится до пятого числа.",
        "Арендатор обязан бережно относиться к квартире и мебели.",
        "Погода в тот день была солнечной, и все были рады встрече.",
        "Залог за квартиру возвращается арендатору после окончания договора аренды.",
    ).mapIndexed { i, s -> Passage(0, i, s) }

    @Test
    fun answerFindsThePassageAboutTheQuestion() {
        val found = Summarizer.answer("Сколько стоит арендная плата?", text)
        assertEquals(1, found.first().page)
    }

    @Test
    fun answerIsEmptyForUnrelatedQuestion() {
        assertTrue(Summarizer.answer("квантовая гравитация", text).isEmpty())
    }

    @Test
    fun summaryKeepsReadingOrderAndSkipsOffTopic() {
        val summary = Summarizer.summary(text, count = 3)
        assertEquals(3, summary.size)
        assertEquals(summary.sortedBy { it.page }, summary)
        assertTrue(summary.none { it.page == 3 })
    }

    @Test
    fun sentencesSplitOnPunctuation() {
        val s = Summarizer.sentences("Первое предложение достаточно длинное. Второе тоже вполне длинное! А это ок?")
        assertEquals(2, s.size)
    }
}
