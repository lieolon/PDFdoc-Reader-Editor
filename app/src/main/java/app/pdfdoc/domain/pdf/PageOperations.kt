package app.pdfdoc.domain.pdf

import java.io.File

/**
 * Structural PDF operations. All page indices are zero-based.
 * Every operation reads [source] and writes the result to [target]; they may be the same file.
 */
interface PageOperations {
    fun pageCount(source: File): Int
    fun deletePages(source: File, target: File, pages: Set<Int>)
    fun rotatePages(source: File, target: File, pages: Set<Int>, degrees: Int)
    fun movePage(source: File, target: File, from: Int, to: Int)
    fun extractPages(source: File, target: File, pages: List<Int>)
    fun merge(sources: List<File>, target: File)

    /** Inserts a copy of page [index] right after it. */
    fun duplicatePage(source: File, target: File, index: Int)

    /** Inserts an empty page at [at], sized like the page before it, ruled as [paper]. */
    fun insertBlankPage(source: File, target: File, at: Int, paper: Paper = Paper.BLANK)

    /** Inserts every page of [other] at position [at]. */
    fun insertDocument(source: File, other: File, target: File, at: Int)
}

/** Ruling of an inserted page, for taking notes by hand. */
enum class Paper { BLANK, LINED, SQUARED, DOTTED, MUSIC }
