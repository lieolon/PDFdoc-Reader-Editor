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
}
