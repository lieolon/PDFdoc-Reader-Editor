package app.pdfdoc.data.convert

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.data.files.Images
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens text, office, e-book and image files by converting them to a PDF kept in app storage.
 * The source file is never changed; edits go to the PDF, which is reused when the same
 * unchanged file is opened again.
 */
@Singleton
class DocumentImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: FileStore,
    private val textToPdf: TextToPdf,
) {
    private val dir get() = File(context.filesDir, DIR).apply { mkdirs() }

    /** The format of [uri] if it has to be converted, or null when it should open as a PDF. */
    fun formatOf(uri: Uri): ImportFormat? {
        if (uri.authority == authority(context)) return null
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val name = runCatching { files.query(uri).name }.getOrDefault(uri.lastPathSegment.orEmpty())
        return ImportFormats.detect(name, mime)
    }

    /** Converts [uri] to PDF and returns a content URI of the result. */
    fun import(uri: Uri, format: ImportFormat): Uri {
        val info = files.query(uri)
        val base = info.name.substringBeforeLast('.').ifBlank { "document" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val folder = File(dir, key(uri, info.sizeBytes))
        val target = File(folder, "$base.pdf")
        if (!target.exists()) {
            folder.mkdirs()
            val temp = File(folder, ".$base.part")
            try {
                convert(uri, format, base, temp)
                if (!temp.renameTo(target)) throw IOException("rename")
            } finally {
                temp.delete()
            }
        }
        return FileProvider.getUriForFile(context, authority(context), target)
    }

    private fun convert(uri: Uri, format: ImportFormat, title: String, target: File) {
        if (format == ImportFormat.IMAGE) return imagePdf(uri, title, target)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Cannot open $uri")
        val (blocks, bookTitle) = when (format) {
            ImportFormat.TEXT -> TextToPdf.plain(TextDecoding.decode(bytes)) to null
            ImportFormat.MARKDOWN -> TextToPdf.markdown(TextDecoding.decode(bytes)) to null
            ImportFormat.HTML -> HtmlText.blocks(TextDecoding.decode(bytes)) to null
            ImportFormat.DOCX -> OfficeText.docx(bytes) to null
            ImportFormat.ODT -> OfficeText.odt(bytes) to null
            ImportFormat.XLSX -> OfficeText.xlsx(bytes) { context.getString(app.pdfdoc.R.string.import_sheet, it) } to null
            ImportFormat.PPTX -> OfficeText.pptx(bytes) { context.getString(app.pdfdoc.R.string.import_slide, it) } to null
            ImportFormat.RTF -> RtfText.blocks(TextDecoding.decode(bytes)) to null
            ImportFormat.EPUB -> BookText.epub(bytes).let { it.blocks to it.title }
            ImportFormat.FB2 -> BookText.fb2(TextDecoding.decode(bytes)).let { it.blocks to it.title }
            ImportFormat.IMAGE -> error("unreachable")
        }
        textToPdf.write(blocks, bookTitle ?: title, target)
    }

    private fun imagePdf(uri: Uri, title: String, target: File) {
        val bitmap = Images.decode(context, uri, IMAGE_MAX_SIDE)
        try {
            PDDocument().use { doc ->
                val w = bitmap.width.toFloat()
                val h = bitmap.height.toFloat()
                val page = PDPage(if (w <= h) PDRectangle(SHORT_SIDE, SHORT_SIDE * h / w) else PDRectangle(SHORT_SIDE * w / h, SHORT_SIDE))
                doc.addPage(page)
                // Keep transparency and sharp edges of screenshots and drawings; photos are smaller as JPEG.
                val image = if (bitmap.hasAlpha() || bitmap.config == Bitmap.Config.ALPHA_8) {
                    LosslessFactory.createFromImage(doc, bitmap)
                } else {
                    JPEGFactory.createFromImage(doc, bitmap, JPEG_QUALITY)
                }
                PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, page.mediaBox.width, page.mediaBox.height) }
                doc.documentInformation.title = title
                doc.documentInformation.creator = "PDFdoc"
                doc.save(target)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Identifies a source file version: same URI, size and modification time give the same PDF. */
    private fun key(uri: Uri, size: Long): String {
        val modified = runCatching {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            }
        }.getOrNull() ?: 0L
        val digest = MessageDigest.getInstance("SHA-256").digest("$uri|$size|$modified".toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DIR = "converted"
        private const val SHORT_SIDE = 595f
        private const val JPEG_QUALITY = 0.9f
        private const val IMAGE_MAX_SIDE = 3000

        fun authority(context: Context) = "${context.packageName}.files"
    }
}
