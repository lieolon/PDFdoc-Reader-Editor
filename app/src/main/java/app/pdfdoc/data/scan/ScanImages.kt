package app.pdfdoc.data.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import app.pdfdoc.domain.edit.Pt
import app.pdfdoc.domain.scan.EdgeDetector
import app.pdfdoc.domain.scan.Filters
import app.pdfdoc.domain.scan.Quad
import app.pdfdoc.domain.scan.ScanFilter
import java.io.File
import kotlin.math.max

object ScanImages {
    /** Longest side of a straightened page; ~300 dpi for A4, enough for OCR. */
    const val MAX_PAGE_SIDE = 3000

    /** Straightens the [quad] region of [source] into a rectangle (perspective correction). */
    fun warp(source: Bitmap, quad: Quad): Bitmap {
        var (w, h) = quad.outputSize()
        val scale = MAX_PAGE_SIDE.toFloat() / max(w, h)
        if (scale < 1f) {
            w = (w * scale).toInt().coerceAtLeast(1)
            h = (h * scale).toInt().coerceAtLeast(1)
        }
        val src = quad.corners.flatMap { listOf(it.x, it.y) }.toFloatArray()
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val matrix = Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Finds the page outline in [source], or null if no clear page is visible. */
    fun detectPage(source: Bitmap): Quad? {
        val scale = DETECT_SIDE.toFloat() / max(source.width, source.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
        } else source
        val k = if (scale < 1f) scale else 1f
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val quad = EdgeDetector.detect(pixels, small.width, small.height) ?: return null
        return Quad.ordered(quad.corners.map { Pt((it.x / k).coerceIn(0f, source.width.toFloat()), (it.y / k).coerceIn(0f, source.height.toFloat())) })
    }

    private const val DETECT_SIDE = 256

    /** A4 at 300 dpi. */
    const val ID_SHEET_WIDTH = 2480
    const val ID_SHEET_HEIGHT = 3508

    /** Front and back of an ID card at real size (85.6 × 54 mm) on one A4 sheet. */
    fun idSheet(front: Bitmap, back: Bitmap): Bitmap {
        val sheet = Bitmap.createBitmap(ID_SHEET_WIDTH, ID_SHEET_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(android.graphics.Color.WHITE)
        val cardW = 1011f
        val cardH = 638f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        listOf(front to ID_SHEET_HEIGHT * 0.27f, back to ID_SHEET_HEIGHT * 0.62f).forEach { (card, centerY) ->
            // Cards photographed upright are turned to landscape.
            val upright = if (card.height > card.width) rotateClockwise(card) else card
            val k = minOf(cardW / upright.width, cardH / upright.height)
            val w = upright.width * k
            val h = upright.height * k
            val left = (ID_SHEET_WIDTH - w) / 2
            val top = centerY - h / 2
            canvas.drawBitmap(upright, null, android.graphics.RectF(left, top, left + w, top + h), paint)
        }
        return sheet
    }

    /** Splits a photographed book spread at the middle into the left and right page. */
    fun splitSpread(spread: Bitmap): Pair<Bitmap, Bitmap> {
        val half = spread.width / 2
        return Bitmap.createBitmap(spread, 0, 0, half, spread.height) to
            Bitmap.createBitmap(spread, half, 0, spread.width - half, spread.height)
    }

    fun rotateClockwise(source: Bitmap): Bitmap =
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(90f) }, true)

    fun filter(source: Bitmap, filter: ScanFilter): Bitmap {
        if (filter == ScanFilter.COLOR) return source
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        return Bitmap.createBitmap(Filters.apply(pixels, w, h, filter), w, h, Bitmap.Config.ARGB_8888)
    }

    /** Decodes a downscaled copy for previews. */
    fun decodePreview(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 92): File {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return file
    }
}
