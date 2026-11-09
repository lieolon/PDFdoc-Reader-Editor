package app.pdfdoc.domain.text

import kotlin.math.ln
import kotlin.math.sqrt

/** A sentence of a document, with where it came from. */
data class Passage(val document: Int, val page: Int, val text: String)

/**
 * Offline "summary and questions": no language model, only word statistics.
 * The summary picks the sentences that carry the document's most frequent content words;
 * an answer is the passages that share the most (and rarest) words with the question.
 */
object Summarizer {
    private val stop = setOf(
        // Russian
        "и", "в", "во", "не", "что", "он", "на", "я", "с", "со", "как", "а", "то", "все", "она", "так", "его", "но", "да", "ты",
        "к", "у", "же", "вы", "за", "бы", "по", "только", "ее", "её", "мне", "было", "вот", "от", "меня", "еще", "ещё", "нет", "о", "из",
        "ему", "теперь", "когда", "даже", "ну", "ли", "если", "уже", "или", "ни", "быть", "был", "него", "до", "вас", "нибудь",
        "уж", "вам", "сказал", "ведь", "там", "потом", "себя", "ничего", "ей", "может", "они", "тут", "где", "есть", "надо", "ней",
        "для", "мы", "тебя", "их", "чем", "была", "сам", "чтоб", "без", "будто", "чего", "раз", "тоже", "себе", "под", "будет",
        "это", "этот", "эта", "эти", "того", "этого", "какой", "который", "которые", "при", "также", "т", "д", "г", "см",
        // English
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "were", "be", "been", "it", "this",
        "that", "with", "as", "by", "at", "from", "not", "but", "we", "you", "he", "she", "they", "i", "our", "your", "their",
        "his", "her", "its", "can", "will", "would", "should", "may", "has", "have", "had", "do", "does", "did", "so", "if",
        "what", "which", "who", "when", "where", "how", "why", "there", "here", "all", "any", "some", "no", "yes", "than",
        "then", "also", "into", "about", "more", "most", "such", "only", "other", "these", "those", "each",
    )

    /** Words reduced to a crude stem (first 6 letters), so word forms like "договора/договору" match. */
    fun terms(text: String): List<String> =
        Regex("""[\p{L}\p{N}]+""").findAll(text.lowercase().replace('ё', 'е'))
            .map { it.value }
            .filter { it.length > 2 && it !in stop && !it.all(Char::isDigit) }
            .map { if (it.length > 6) it.take(6) else it }
            .toList()

    fun sentences(paragraph: String): List<String> =
        paragraph.replace(Regex("""\s+"""), " ").trim()
            .split(Regex("""(?<=[.!?…])\s+(?=[\p{Lu}\p{N}«"(])"""))
            .map { it.trim() }
            .filter { it.length in 25..600 }

    /** The [count] most representative passages, in reading order. */
    fun summary(passages: List<Passage>, count: Int = 5): List<Passage> {
        if (passages.isEmpty()) return emptyList()
        val tf = HashMap<String, Int>()
        val bags = passages.map { terms(it.text) }
        bags.forEach { b -> b.forEach { tf[it] = (tf[it] ?: 0) + 1 } }
        val top = tf.values.maxOrNull()?.toFloat() ?: return emptyList()
        val scored = passages.indices.map { i ->
            val bag = bags[i]
            val score = if (bag.isEmpty()) 0f else bag.sumOf { (tf[it]!! / top).toDouble() }.toFloat() / sqrt(bag.size.toFloat())
            // Early sentences of a document tend to state its purpose.
            val position = 1f + 0.3f / (1 + i / 5)
            i to score * position
        }
        val chosen = mutableListOf<Int>()
        for ((i, _) in scored.sortedByDescending { it.second }) {
            if (chosen.size >= count) break
            // Skip near-duplicates of what is already picked.
            if (chosen.none { overlap(bags[it], bags[i]) > 0.6f }) chosen += i
        }
        return chosen.sorted().map { passages[it] }
    }

    /** Passages that best answer [question], best first; empty when nothing shares its words. */
    fun answer(question: String, passages: List<Passage>, count: Int = 3): List<Passage> {
        val q = terms(question).toSet()
        if (q.isEmpty()) return emptyList()
        val bags = passages.map { terms(it.text).toSet() }
        val df = HashMap<String, Int>()
        bags.forEach { b -> b.forEach { if (it in q) df[it] = (df[it] ?: 0) + 1 } }
        val n = passages.size.toDouble()
        return passages.indices.mapNotNull { i ->
            val hits = bags[i].intersect(q)
            if (hits.isEmpty()) return@mapNotNull null
            val score = hits.sumOf { ln(1 + n / df[it]!!) } * (hits.size.toDouble() / q.size)
            i to score
        }.sortedByDescending { it.second }.take(count).map { passages[it.first] }
    }

    private fun overlap(a: List<String>, b: List<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val sa = a.toSet()
        val sb = b.toSet()
        return sa.intersect(sb).size.toFloat() / minOf(sa.size, sb.size)
    }
}
