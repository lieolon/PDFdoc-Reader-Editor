package app.pdfdoc.domain.scan

enum class ScanFilter { COLOR, ENHANCED, GRAYSCALE, BLACK_WHITE }

/** Pure image filters over ARGB pixel arrays, so they run off the UI thread and in unit tests. */
object Filters {

    fun apply(pixels: IntArray, width: Int, height: Int, filter: ScanFilter): IntArray = when (filter) {
        ScanFilter.COLOR -> pixels.copyOf()
        ScanFilter.ENHANCED -> enhance(pixels)
        ScanFilter.GRAYSCALE -> grayscale(enhance(pixels))
        ScanFilter.BLACK_WHITE -> blackWhite(pixels, width, height)
    }

    fun luminance(c: Int): Int {
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    fun grayscale(pixels: IntArray) = IntArray(pixels.size) { gray(luminance(pixels[it])) }

    /** Stretches levels between the 1st and 99th luminance percentile: whiter paper, darker ink. */
    fun enhance(pixels: IntArray): IntArray {
        val hist = IntArray(256)
        pixels.forEach { hist[luminance(it)]++ }
        val lo = percentile(hist, pixels.size, 0.01)
        val hi = percentile(hist, pixels.size, 0.99)
        if (hi - lo < 16) return pixels.copyOf()
        val lut = IntArray(256) { ((it - lo) * 255 / (hi - lo)).coerceIn(0, 255) }
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            (0xFF shl 24) or (lut[c shr 16 and 0xFF] shl 16) or (lut[c shr 8 and 0xFF] shl 8) or lut[c and 0xFF]
        }
    }

    /**
     * Bradley adaptive threshold: a pixel is ink when it is noticeably darker than the mean of its
     * neighbourhood. Handles uneven lighting and shadows far better than a global threshold.
     */
    fun blackWhite(pixels: IntArray, width: Int, height: Int, windowFraction: Int = 16, sensitivity: Int = 15): IntArray {
        val lum = IntArray(pixels.size) { luminance(pixels[it]) }
        // Integral image with a zero row/column for simpler window sums.
        val stride = width + 1
        val integral = LongArray(stride * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0L
            for (x in 0 until width) {
                rowSum += lum[y * width + x]
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + rowSum
            }
        }
        val half = (maxOf(width, height) / windowFraction / 2).coerceAtLeast(1)
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            val y1 = (y - half).coerceAtLeast(0)
            val y2 = (y + half).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val x1 = (x - half).coerceAtLeast(0)
                val x2 = (x + half).coerceAtMost(width - 1)
                val count = (x2 - x1 + 1).toLong() * (y2 - y1 + 1)
                val sum = integral[(y2 + 1) * stride + x2 + 1] - integral[y1 * stride + x2 + 1] -
                    integral[(y2 + 1) * stride + x1] + integral[y1 * stride + x1]
                val ink = lum[y * width + x] * count * 100 <= sum * (100 - sensitivity)
                out[y * width + x] = gray(if (ink) 0 else 255)
            }
        }
        return out
    }

    private fun percentile(hist: IntArray, total: Int, fraction: Double): Int {
        val target = (total * fraction).toLong()
        var acc = 0L
        for (i in hist.indices) {
            acc += hist[i]
            if (acc > target) return i
        }
        return 255
    }
}
