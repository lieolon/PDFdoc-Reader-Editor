package app.pdfdoc.data.pdf

import android.content.Context
import app.pdfdoc.domain.edit.Box
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDOutputIntent
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.util.Matrix
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class DocProperties(
    val title: String = "",
    val author: String = "",
    val subject: String = "",
    val keywords: String = "",
    val creator: String = "",
    val producer: String = "",
    val created: Calendar? = null,
    val modified: Calendar? = null,
    val pageCount: Int = 0,
    val version: Float = 0f,
)

data class SanitizeOptions(
    val metadata: Boolean = true,
    val scripts: Boolean = true,
    val attachments: Boolean = true,
    val comments: Boolean = false,
    val thumbnails: Boolean = true,
)

data class Attachment(val name: String, val size: Long, val description: String?)

data class OutlineEntry(val title: String, val page: Int, val level: Int)

enum class PaperSize(val width: Float, val height: Float) {
    A3(841.89f, 1190.55f),
    A4(595.28f, 841.89f),
    A5(419.53f, 595.28f),
    LETTER(612f, 792f),
    LEGAL(612f, 1008f),
}

/** Whole-document operations of the Tools screen that work on the PDF structure. */
class PdfDocumentTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val renderers: PdfRenderSessionFactory,
    private val renderCopies: RenderCopyBuilder,
) {

    // region Pages

    /** Crops margins given in points, in the page as displayed, on [pages] (all when null). */
    fun cropMargins(source: File, target: File, top: Float, right: Float, bottom: Float, left: Float, pages: Set<Int>?) =
        edit(source, target) { doc ->
            doc.pages.forEachIndexed { i, page ->
                if (pages != null && i !in pages) return@forEachIndexed
                val g = PageGeometry.of(page)
                val box = Box(left, top, g.displayWidth - right, g.displayHeight - bottom)
                if (box.width > 20f && box.height > 20f) page.cropBox = g.toPdfRectangle(box)
            }
        }

    /** Crops every page to its visible content plus a small border. Returns how many pages changed. */
    suspend fun autoCrop(source: File, target: File, padding: Float = 12f): Int {
        val boxes = renderers.open(source).use { session ->
            session.pageSizes.indices.map { i ->
                val size = session.pageSizes[i]
                val bitmap = session.render(i, AUTO_CROP_WIDTH) ?: return@map null
                val k = size.width / bitmap.width.toFloat()
                val content = contentBounds(bitmap)
                bitmap.recycle()
                content?.let { Box(it.left * k - padding, it.top * k - padding, it.right * k + padding, it.bottom * k + padding) }
            }
        }
        var changed = 0
        edit(source, target) { doc ->
            doc.pages.forEachIndexed { i, page ->
                val g = PageGeometry.of(page)
                val box = boxes.getOrNull(i) ?: return@forEachIndexed
                val clipped = Box(max(0f, box.left), max(0f, box.top), min(g.displayWidth, box.right), min(g.displayHeight, box.bottom))
                if (clipped.width < g.displayWidth - 2f || clipped.height < g.displayHeight - 2f) {
                    page.cropBox = g.toPdfRectangle(clipped)
                    changed++
                }
            }
        }
        return changed
    }

    private fun contentBounds(bitmap: android.graphics.Bitmap): Box? {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        var top = -1
        var bottom = -1
        var left = w
        var right = -1
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            var any = false
            for (x in 0 until w) {
                val c = row[x]
                val lum = ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10
                if (lum < 235) {
                    any = true
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
            if (any) {
                if (top < 0) top = y
                bottom = y
            }
        }
        if (top < 0) return null
        return Box(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
    }

    /** Scales every page to [paper], keeping its orientation and centering the content. */
    fun resizePages(source: File, target: File, paper: PaperSize) = edit(source, target) { doc ->
        doc.pages.forEach { page ->
            val crop = page.cropBox
            val rotated = page.rotation.mod(180) == 90
            val landscape = (if (rotated) crop.height else crop.width) > (if (rotated) crop.width else crop.height)
            // Target size in unrotated page space.
            var tw = if (landscape) paper.height else paper.width
            var th = if (landscape) paper.width else paper.height
            if (rotated) tw = th.also { th = tw }
            val s = min(tw / crop.width, th / crop.height)
            val dx = (tw - crop.width * s) / 2 - crop.lowerLeftX * s
            val dy = (th - crop.height * s) / 2 - crop.lowerLeftY * s
            val m = Matrix(s, 0f, 0f, s, dx, dy)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.PREPEND, true).use { cs ->
                cs.saveGraphicsState()
                cs.transform(m)
            }
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, false).use { cs ->
                cs.restoreGraphicsState()
            }
            page.annotations.forEach { a ->
                val r = a.rectangle ?: return@forEach
                a.rectangle = PDRectangle(r.lowerLeftX * s + dx, r.lowerLeftY * s + dy, r.width * s, r.height * s)
            }
            val box = PDRectangle(0f, 0f, tw, th)
            page.mediaBox = box
            page.cropBox = box
            page.cosObject.removeItem(COSName.BLEED_BOX)
            page.cosObject.removeItem(COSName.TRIM_BOX)
            page.cosObject.removeItem(COSName.ART_BOX)
        }
    }

    /** Draws annotations and form values into the pages so they can no longer be changed. */
    fun flatten(source: File, target: File): Boolean {
        if (renderCopies.build(source, target, excludeOwn = false)) return true
        source.copyTo(target, overwrite = true)
        return false
    }

    // endregion

    // region Properties and cleanup

    fun properties(source: File): DocProperties = load(source).use { doc ->
        val info = doc.documentInformation
        DocProperties(
            title = info.title.orEmpty(),
            author = info.author.orEmpty(),
            subject = info.subject.orEmpty(),
            keywords = info.keywords.orEmpty(),
            creator = info.creator.orEmpty(),
            producer = info.producer.orEmpty(),
            created = runCatching { info.creationDate }.getOrNull(),
            modified = runCatching { info.modificationDate }.getOrNull(),
            pageCount = doc.numberOfPages,
            version = doc.version,
        )
    }

    fun setProperties(source: File, target: File, p: DocProperties) = edit(source, target) { doc ->
        val info = doc.documentInformation
        info.title = p.title.ifBlank { null }
        info.author = p.author.ifBlank { null }
        info.subject = p.subject.ifBlank { null }
        info.keywords = p.keywords.ifBlank { null }
        info.modificationDate = Calendar.getInstance()
        // Readers prefer XMP over the info dictionary; drop stale XMP unless it marks a PDF/A file.
        val xmp = runCatching { doc.documentCatalog.metadata?.toByteArray()?.decodeToString() }.getOrNull()
        if (xmp != null && !xmp.contains("pdfaid")) doc.documentCatalog.metadata = null
    }

    fun sanitize(source: File, target: File, o: SanitizeOptions) = edit(source, target) { doc ->
        val catalog = doc.documentCatalog
        if (o.metadata) {
            doc.documentInformation = com.tom_roush.pdfbox.pdmodel.PDDocumentInformation()
            catalog.metadata = null
            catalog.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
            doc.pages.forEach {
                it.cosObject.removeItem(COSName.METADATA)
                it.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
            }
        }
        if (o.scripts) {
            catalog.names?.cosObject?.removeItem(COSName.JAVA_SCRIPT)
            catalog.cosObject.removeItem(COSName.AA)
            val open = catalog.cosObject.getDictionaryObject(COSName.getPDFName("OpenAction")) as? COSDictionary
            if (open != null && open.getNameAsString(COSName.S) in SCRIPT_ACTIONS) catalog.cosObject.removeItem(COSName.getPDFName("OpenAction"))
            doc.pages.forEach { page ->
                page.cosObject.removeItem(COSName.AA)
                page.annotations.forEach { a ->
                    a.cosObject.removeItem(COSName.AA)
                    val action = a.cosObject.getDictionaryObject(COSName.A) as? COSDictionary
                    if (action != null && action.getNameAsString(COSName.S) in SCRIPT_ACTIONS) a.cosObject.removeItem(COSName.A)
                }
            }
            catalog.acroForm?.fieldTree?.forEach { it.cosObject.removeItem(COSName.AA) }
        }
        if (o.attachments) {
            catalog.names?.cosObject?.removeItem(COSName.EMBEDDED_FILES)
            doc.pages.forEach { page -> page.annotations = ArrayList(page.annotations.filterNot { it is PDAnnotationFileAttachment }) }
        }
        if (o.comments) {
            doc.pages.forEach { page ->
                page.annotations = ArrayList(page.annotations.filter { it is PDAnnotationLink || it is PDAnnotationWidget })
            }
        }
        if (o.thumbnails) doc.pages.forEach { it.cosObject.removeItem(COSName.getPDFName("Thumb")) }
    }

    // endregion

    // region Attachments

    fun attachments(source: File): List<Attachment> = load(source).use { doc ->
        embeddedFiles(doc).map { (name, spec) ->
            val file = spec.embeddedFile ?: spec.embeddedFileUnicode
            Attachment(name, file?.length?.toLong() ?: file?.size?.toLong() ?: 0L, spec.fileDescription)
        }.sortedBy { it.name.lowercase() }
    }

    fun readAttachment(source: File, name: String): ByteArray? = load(source).use { doc ->
        val spec = embeddedFiles(doc)[name] ?: return null
        (spec.embeddedFile ?: spec.embeddedFileUnicode)?.toByteArray()
    }

    fun addAttachment(source: File, target: File, name: String, mime: String?, data: InputStream) = edit(source, target) { doc ->
        val files = embeddedFiles(doc).toMutableMap()
        val bytes = data.readBytes()
        val embedded = PDEmbeddedFile(doc, ByteArrayInputStream(bytes))
        embedded.subtype = mime
        embedded.size = bytes.size
        embedded.creationDate = GregorianCalendar()
        val spec = PDComplexFileSpecification()
        spec.file = name
        spec.fileUnicode = name
        spec.embeddedFile = embedded
        spec.embeddedFileUnicode = embedded
        var unique = name
        var n = 2
        while (unique in files) unique = "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.', "")}".trimEnd('.').also { n++ }
        files[unique] = spec
        setEmbeddedFiles(doc, files)
    }

    fun removeAttachment(source: File, target: File, name: String) = edit(source, target) { doc ->
        setEmbeddedFiles(doc, embeddedFiles(doc) - name)
    }

    private fun embeddedFiles(doc: PDDocument): Map<String, PDComplexFileSpecification> {
        val result = LinkedHashMap<String, PDComplexFileSpecification>()
        fun walk(node: com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode<PDComplexFileSpecification>?) {
            node ?: return
            runCatching { node.names }.getOrNull()?.forEach { (k, v) -> result[k] = v }
            runCatching { node.kids }.getOrNull()?.forEach { walk(it) }
        }
        walk(doc.documentCatalog.names?.embeddedFiles)
        // Files attached to comments.
        doc.pages.forEach { page ->
            page.annotations.filterIsInstance<PDAnnotationFileAttachment>().forEach { a ->
                (a.file as? PDComplexFileSpecification)?.let { spec -> result.putIfAbsent(spec.filename ?: spec.file ?: "file", spec) }
            }
        }
        return result
    }

    private fun setEmbeddedFiles(doc: PDDocument, files: Map<String, PDComplexFileSpecification>) {
        val catalog = doc.documentCatalog
        val names = catalog.names ?: PDDocumentNameDictionary(catalog).also { catalog.names = it }
        if (files.isEmpty()) {
            names.cosObject.removeItem(COSName.EMBEDDED_FILES)
            return
        }
        val tree = PDEmbeddedFilesNameTreeNode()
        tree.names = files.toSortedMap()
        names.embeddedFiles = tree
    }

    // endregion

    // region Outline

    fun outline(source: File): List<OutlineEntry> = load(source).use { doc ->
        val pages = doc.pages.toList()
        val items = mutableListOf<OutlineEntry>()
        fun walk(node: PDOutlineNode, level: Int) {
            node.children().forEach { item ->
                val page = runCatching { item.findDestinationPage(doc) }.getOrNull()?.let(pages::indexOf) ?: -1
                items += OutlineEntry(item.title.orEmpty().trim(), page, level)
                if (level < 8) walk(item, level + 1)
            }
        }
        doc.documentCatalog.documentOutline?.let { walk(it, 0) }
        items
    }

    /** Replaces the table of contents with [entries] (levels describe the nesting). */
    fun setOutline(source: File, target: File, entries: List<OutlineEntry>) = edit(source, target) { doc ->
        if (entries.isEmpty()) {
            doc.documentCatalog.documentOutline = null
            return@edit
        }
        val outline = PDDocumentOutline()
        val stack = ArrayList<PDOutlineNode>().apply { add(outline) }
        entries.forEach { e ->
            val item = PDOutlineItem()
            item.title = e.title
            doc.pages.takeIf { e.page in 0 until doc.numberOfPages }?.get(e.page)?.let { page ->
                item.destination = PDPageFitDestination().apply { this.page = page }
            }
            val level = e.level.coerceIn(0, stack.size - 1)
            while (stack.size > level + 1) stack.removeAt(stack.lastIndex)
            stack.last().addLast(item)
            stack += item
        }
        outline.openNode()
        doc.documentCatalog.documentOutline = outline
    }

    // endregion

    // region PDF/A

    /**
     * Makes an archival PDF/A-2b candidate: XMP identification, an sRGB output intent, document
     * ID, no scripts or attachments. Returns the number of fonts that are not embedded (those
     * keep the file from strict PDF/A conformance).
     */
    fun toPdfA(source: File, target: File, title: String): Int {
        var missingFonts = 0
        edit(source, target) { doc ->
            doc.isAllSecurityToBeRemoved = true
            sanitizeForArchive(doc)
            val catalog = doc.documentCatalog
            val now = Calendar.getInstance()
            val info = doc.documentInformation
            if (info.title.isNullOrBlank()) info.title = title
            info.producer = "PDFdoc"
            if (info.creationDate == null) info.creationDate = now
            info.modificationDate = now
            val xmp = xmp(info.title.orEmpty(), info.author.orEmpty(), info.creationDate ?: now, now)
            val metadata = PDMetadata(doc, ByteArrayInputStream(xmp.toByteArray()))
            catalog.metadata = metadata
            if (catalog.outputIntents.isEmpty()) {
                val intent = PDOutputIntent(doc, ByteArrayInputStream(SrgbIcc.bytes()))
                intent.info = "sRGB IEC61966-2.1"
                intent.outputCondition = "sRGB IEC61966-2.1"
                intent.outputConditionIdentifier = "sRGB IEC61966-2.1"
                intent.registryName = "http://www.color.org"
                catalog.addOutputIntent(intent)
            }
            doc.pages.forEach { page -> page.annotations.forEach { it.isPrinted = true } }
            val id = COSString(MessageDigest.getInstance("MD5").digest((title + now.timeInMillis).toByteArray()))
            doc.document.documentID = COSArray().apply { add(id); add(id) }
            if (doc.version < 1.7f) doc.version = 1.7f
            missingFonts = countMissingFonts(doc)
        }
        return missingFonts
    }

    private fun sanitizeForArchive(doc: PDDocument) {
        val catalog = doc.documentCatalog
        catalog.names?.cosObject?.removeItem(COSName.JAVA_SCRIPT)
        catalog.names?.cosObject?.removeItem(COSName.EMBEDDED_FILES)
        catalog.cosObject.removeItem(COSName.AA)
        doc.pages.forEach { page ->
            page.cosObject.removeItem(COSName.AA)
            page.annotations = ArrayList(page.annotations.filterNot { it is PDAnnotationFileAttachment })
        }
    }

    private fun countMissingFonts(doc: PDDocument): Int {
        val seen = HashSet<Any>()
        var missing = 0
        fun walk(resources: PDResources?) {
            resources ?: return
            resources.fontNames.forEach { name ->
                val font = runCatching { resources.getFont(name) }.getOrNull() ?: return@forEach
                if (seen.add(font.cosObject) && !font.isEmbedded) missing++
            }
            resources.xObjectNames.forEach { name ->
                val x = runCatching { resources.getXObject(name) }.getOrNull()
                if (x is com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject && seen.add(x.cosObject)) walk(x.resources)
            }
        }
        doc.pages.forEach { walk(it.resources) }
        return missing
    }

    private fun xmp(title: String, author: String, created: Calendar, modified: Calendar): String {
        fun date(c: Calendar) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = c.timeZone ?: TimeZone.getDefault() }.format(c.time)
        fun esc(s: String) = app.pdfdoc.data.convert.DocxWriter.escape(s)
        return """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:pdf="http://ns.adobe.com/pdf/1.3/">
<pdfaid:part>2</pdfaid:part>
<pdfaid:conformance>B</pdfaid:conformance>
<dc:format>application/pdf</dc:format>
<dc:title><rdf:Alt><rdf:li xml:lang="x-default">${esc(title)}</rdf:li></rdf:Alt></dc:title>
${if (author.isNotBlank()) "<dc:creator><rdf:Seq><rdf:li>${esc(author)}</rdf:li></rdf:Seq></dc:creator>" else ""}
<xmp:CreateDate>${date(created)}</xmp:CreateDate>
<xmp:ModifyDate>${date(modified)}</xmp:ModifyDate>
<xmp:MetadataDate>${date(modified)}</xmp:MetadataDate>
<xmp:CreatorTool>PDFdoc</xmp:CreatorTool>
<pdf:Producer>PDFdoc</pdf:Producer>
</rdf:Description>
</rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    // endregion

    private fun load(file: File) = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())

    private inline fun edit(source: File, target: File, block: (PDDocument) -> Unit) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        load(source).use { doc ->
            block(doc)
            doc.save(tmp)
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private companion object {
        const val AUTO_CROP_WIDTH = 500
        val SCRIPT_ACTIONS = setOf("JavaScript", "Launch", "SubmitForm", "ImportData")
    }
}

/** A minimal ICC v2 sRGB display profile (D50 primaries, gamma 2.2), generated instead of shipped. */
object SrgbIcc {
    fun bytes(): ByteArray {
        val tags = linkedMapOf(
            "desc" to desc("sRGB IEC61966-2.1"),
            "cprt" to text("No copyright, use freely"),
            "wtpt" to xyz(0.9642, 1.0, 0.8249),
            "rXYZ" to xyz(0.4361, 0.2225, 0.0139),
            "gXYZ" to xyz(0.3851, 0.7169, 0.0971),
            "bXYZ" to xyz(0.1431, 0.0606, 0.7141),
            "rTRC" to curve(),
            "gTRC" to curve(),
            "bTRC" to curve(),
        )
        val headerSize = 128
        val tableSize = 4 + tags.size * 12
        var offset = headerSize + tableSize
        val offsets = tags.mapValues { (_, data) -> offset.also { offset += align(data.size) } }
        val total = offset
        val out = java.nio.ByteBuffer.allocate(total)
        out.putInt(total)
        out.putInt(0)
        out.putInt(0x02100000)
        out.put("mntr".toByteArray()); out.put("RGB ".toByteArray()); out.put("XYZ ".toByteArray())
        listOf(2026, 1, 1, 0, 0, 0).forEach { out.putShort(it.toShort()) }
        out.put("acsp".toByteArray())
        repeat(5) { out.putInt(0) } // platform, flags, manufacturer, model, attributes (high)
        out.putInt(0) // attributes (low)
        out.putInt(0) // rendering intent
        out.putInt(s15(0.9642)); out.putInt(s15(1.0)); out.putInt(s15(0.8249))
        out.putInt(0) // creator
        repeat(44) { out.put(0) }
        out.putInt(tags.size)
        tags.forEach { (sig, data) ->
            out.put(sig.toByteArray())
            out.putInt(offsets.getValue(sig))
            out.putInt(data.size)
        }
        tags.values.forEach { data ->
            out.put(data)
            repeat(align(data.size) - data.size) { out.put(0) }
        }
        return out.array()
    }

    private fun align(n: Int) = (n + 3) / 4 * 4
    private fun s15(v: Double) = (v * 65536).roundToInt()

    private fun xyz(x: Double, y: Double, z: Double) = java.nio.ByteBuffer.allocate(20).apply {
        put("XYZ ".toByteArray()); putInt(0); putInt(s15(x)); putInt(s15(y)); putInt(s15(z))
    }.array()

    private fun curve() = java.nio.ByteBuffer.allocate(14).apply {
        put("curv".toByteArray()); putInt(0); putInt(1); putShort(0x0233) // gamma 2.2 as u8Fixed8
    }.array()

    private fun text(s: String): ByteArray {
        val ascii = s.toByteArray(Charsets.US_ASCII)
        return java.nio.ByteBuffer.allocate(8 + ascii.size + 1).apply { put("text".toByteArray()); putInt(0); put(ascii); put(0) }.array()
    }

    private fun desc(s: String): ByteArray {
        val ascii = s.toByteArray(Charsets.US_ASCII)
        return java.nio.ByteBuffer.allocate(12 + ascii.size + 1 + 8 + 3 + 67).apply {
            put("desc".toByteArray()); putInt(0); putInt(ascii.size + 1); put(ascii); put(0)
            putInt(0); putInt(0) // unicode language and count
            putShort(0); put(0) // scriptcode code and count
            repeat(67) { put(0) }
        }.array()
    }
}
