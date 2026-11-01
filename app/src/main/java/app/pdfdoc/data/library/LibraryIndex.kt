package app.pdfdoc.data.library

import app.pdfdoc.data.db.DocumentTextEntity
import app.pdfdoc.data.db.LibraryDao
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A document whose text contains the search words, with the surrounding text. */
data class TextHit(val documentId: Long, val snippet: String)

/** Keeps the text of opened documents so the library can be searched by content. */
@Singleton
class LibraryIndex @Inject constructor(
    private val dao: LibraryDao,
) {
    /** Extracts and stores the text of [file] unless the stored copy matches [signature]. */
    suspend fun index(documentId: Long, signature: String, file: File) {
        if (dao.textSignature(documentId) == signature) return
        val text = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            PDFTextStripper().getText(doc)
        }
        dao.upsertText(DocumentTextEntity(documentId, signature, normalize(text).take(MAX_CHARS)))
    }

    suspend fun search(query: String): List<TextHit> {
        val needle = normalize(query.trim())
        if (needle.length < MIN_QUERY) return emptyList()
        return dao.searchText(needle).map { e ->
            val i = e.text.indexOf(needle)
            val start = (i - 40).coerceAtLeast(0)
            val end = (i + needle.length + 60).coerceAtMost(e.text.length)
            TextHit(e.documentId, (if (start > 0) "…" else "") + e.text.substring(start, end).replace('\n', ' ') + if (end < e.text.length) "…" else "")
        }
    }

    companion object {
        const val MIN_QUERY = 3
        private const val MAX_CHARS = 2_000_000

        fun normalize(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[ \\t\\u00A0]+"), " ")
    }
}
