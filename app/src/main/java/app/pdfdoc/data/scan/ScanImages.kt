package app.pdfdoc.data.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
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
