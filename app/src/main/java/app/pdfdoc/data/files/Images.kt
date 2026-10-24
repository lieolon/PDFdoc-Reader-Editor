package app.pdfdoc.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.IOException
import kotlin.math.max

object Images {
    const val MAX_SIDE = 2048

    /** Decodes an image downscaled to at most [maxSide] px, honoring EXIF orientation on API 28+. */
    fun decode(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > maxSide) decoder.setTargetSampleSize(Integer.highestOneBit(longest / maxSide).coerceAtLeast(1) * 2)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Cannot decode $uri")
    }

    /** Saves as PNG when the image has transparency, otherwise as JPEG (much smaller for photos). */
    fun save(bitmap: Bitmap, dir: File, name: String): File {
        dir.mkdirs()
        val png = bitmap.hasAlpha()
        val file = File(dir, if (png) "$name.png" else "$name.jpg")
        file.outputStream().use {
            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 90, it)
        }
        return file
    }

    /** Turns light paper around a photographed signature into transparency. */
    fun removeLightBackground(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val lum = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c))
            val alpha = ((215f - lum) * 255f / 90f).toInt().coerceIn(0, 255) * Color.alpha(c) / 255
            pixels[i] = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Crops fully transparent margins, keeping [padding] px. Returns null if nothing is visible. */
    fun trim(source: Bitmap, padding: Int = 8): Bitmap? {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (Color.alpha(pixels[y * w + x]) > 16) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < 0) return null
        left = (left - padding).coerceAtLeast(0)
        top = (top - padding).coerceAtLeast(0)
        right = (right + padding).coerceAtMost(w - 1)
        bottom = (bottom + padding).coerceAtMost(h - 1)
        return Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1)
    }
}
