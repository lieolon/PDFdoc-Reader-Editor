package app.pdfdoc.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.pdfdoc.domain.tools.CompressLevel
import app.pdfdoc.domain.tools.CompressResult
import app.pdfdoc.domain.tools.ImageWatermark
import app.pdfdoc.domain.tools.StampPosition
import app.pdfdoc.domain.tools.Templates
import app.pdfdoc.domain.tools.TextStamp
import app.pdfdoc.domain.tools.TextWatermark
import app.pdfdoc.domain.tools.Watermark
import app.pdfdoc.domain.tools.WatermarkLayout
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt

/** Whole-document transformations. All drawing happens in page display space, so rotated pages work. */
class PdfTools @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun addTextStamps(source: File, target: File, stamps: List<TextStamp>, fileName: String, firstNumber: Int = 1) =
        edit(source, target) { doc ->
            val font = PdfFonts.load(doc, context)
            val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date())
            val total = doc.numberOfPages
            doc.pages.forEachIndexed { index, page ->
                val g = PageGeometry.of(page)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.transform(g.matrix())
                    stamps.forEach { stamp ->
                        val text = AppearancePainter.safeText(
                            font, Templates.expand(stamp.template, index + firstNumber, total + firstNumber - 1, date, fileName),
                        )
                        if (text.isBlank()) return@forEach
                        val w = font.getStringWidth(text) / 1000f * stamp.size
                        val x = when (stamp.position) {
                            StampPosition.TOP_LEFT, StampPosition.BOTTOM_LEFT -> stamp.margin
                            StampPosition.TOP_CENTER, StampPosition.BOTTOM_CENTER -> (g.displayWidth - w) / 2
                            StampPosition.TOP_RIGHT, StampPosition.BOTTOM_RIGHT -> g.displayWidth - stamp.margin - w
                        }
                        val top = stamp.position.name.startsWith("TOP")
                        val baseline = if (top) stamp.margin + stamp.size * 0.8f else g.displayHeight - stamp.margin
                        cs.setNonStrokingColor(AppearancePainter.r(stamp.color), AppearancePainter.g(stamp.color), AppearancePainter.b(stamp.color))
                        cs.beginText()
                        cs.setFont(font, stamp.size)
                        cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, x, baseline))
                        cs.showText(text)
                        cs.endText()
                    }
                }
            }
        }

    fun addWatermark(source: File, target: File, watermark: Watermark) = edit(source, target) { doc ->
        val font by lazy { PdfFonts.load(doc, context) }
        val image by lazy {
            val bitmap = BitmapFactory.decodeFile((watermark as ImageWatermark).imagePath)
            if (bitmap.hasAlpha()) LosslessFactory.createFromImage(doc, bitmap) else JPEGFactory.createFromImage(doc, bitmap, 0.9f)
        }
        doc.pages.forEach { page ->
            val g = PageGeometry.of(page)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.transform(g.matrix())
                cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                    nonStrokingAlphaConstant = watermark.opacity
                    strokingAlphaConstant = watermark.opacity
                })
                // Size of one watermark mark, used for centering and tiling.
                val (w, h) = when (watermark) {
                    is TextWatermark -> {
                        val text = AppearancePainter.safeText(font, watermark.text)
                        font.getStringWidth(text) / 1000f * watermark.size to watermark.size
                    }
                    is ImageWatermark -> {
                        val iw = g.displayWidth * watermark.widthFraction
                        iw to iw * image.height / image.width
                    }
                }
                val centers = when (watermark.layout) {
                    WatermarkLayout.CENTER -> listOf(g.displayWidth / 2 to g.displayHeight / 2)
                    WatermarkLayout.TILED -> {
                        val stepX = w * 1.4f + 40f
                        val stepY = max(h * 4f, 140f)
                        val cols = (g.displayWidth / stepX).toInt() + 2
                        val rows = (g.displayHeight / stepY).toInt() + 2
                        (0 until rows).flatMap { r ->
                            (0 until cols).map { c -> (c * stepX + if (r % 2 == 1) stepX / 2 else 0f) to (r * stepY + stepY / 2) }
                        }
                    }
                }
                centers.forEach { (cx, cy) ->
                    cs.saveGraphicsState()
                    cs.transform(Matrix.getTranslateInstance(cx, cy))
                    // Display space has y pointing down, so a visual counter-clockwise turn is negative.
                    cs.transform(Matrix.getRotateInstance(-watermark.angle * PI / 180.0, 0f, 0f))
                    when (watermark) {
                        is TextWatermark -> {
                            val c = watermark.color
                            cs.setNonStrokingColor(AppearancePainter.r(c), AppearancePainter.g(c), AppearancePainter.b(c))
                            cs.beginText()
                            cs.setFont(font, watermark.size)
                            cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, -w / 2, h * 0.35f))
                            cs.showText(AppearancePainter.safeText(font, watermark.text))
                            cs.endText()
                        }
                        is ImageWatermark -> cs.drawImage(image, Matrix(w, 0f, 0f, -h, -w / 2, h / 2))
                    }
                    cs.restoreGraphicsState()
                }
            }
        }
    }

    /** Re-encodes large or heavy images as smaller JPEGs. Text and vector content are untouched. */
    fun compress(source: File, target: File, level: CompressLevel): CompressResult {
        var changed = 0
        edit(source, target) { doc ->
            val replaced = HashMap<COSStream, PDImageXObject>()
            val visited = HashSet<COSStream>()
            fun walk(resources: PDResources?) {
                resources ?: return
                resources.xObjectNames.toList().forEach { name ->
                    when (val x = runCatching { resources.getXObject(name) }.getOrNull()) {
                        is PDFormXObject -> if (visited.add(x.cosObject)) walk(x.resources)
                        is PDImageXObject -> {
                            val smaller = replaced.getOrPut(x.cosObject) { recompress(doc, x, level) ?: x }
                            if (smaller !== x) {
                                resources.put(name, smaller)
                                changed++
                            }
                        }
                    }
                }
            }
            doc.pages.forEach { walk(it.resources) }
        }
        return CompressResult(source.length(), target.length(), changed)
    }

    private fun recompress(doc: PDDocument, image: PDImageXObject, level: CompressLevel): PDImageXObject? {
        // Transparency, masks and 1-bit scans are already compact or would lose information.
        if (image.isStencil || image.bitsPerComponent == 1 || image.cosObject.containsKey(COSName.SMASK)) return null
        val bitmap = runCatching { image.image }.getOrNull() ?: return null
        val longest = max(bitmap.width, bitmap.height)
        val scaled = if (longest > level.maxSide) {
            val k = level.maxSide.toFloat() / longest
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * k).roundToInt().coerceAtLeast(1), (bitmap.height * k).roundToInt().coerceAtLeast(1), true)
        } else bitmap
        val jpeg = JPEGFactory.createFromImage(doc, scaled, level.quality)
        return jpeg.takeIf { it.cosObject.length < image.cosObject.length }
    }

    /** All text of the document; [pageHeader] produces the separator line put before each page. */
    fun extractText(source: File, pageHeader: (Int) -> String): String =
        PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            buildString {
                for (i in 1..doc.numberOfPages) {
                    stripper.startPage = i
                    stripper.endPage = i
                    append(pageHeader(i)).append("\n\n")
                    append(stripper.getText(doc).trim()).append("\n\n")
                }
            }
        }

    private inline fun edit(source: File, target: File, block: (PDDocument) -> Unit) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            block(doc)
            doc.save(tmp)
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
