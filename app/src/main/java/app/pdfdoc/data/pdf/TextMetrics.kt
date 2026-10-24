package app.pdfdoc.data.pdf

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import app.pdfdoc.domain.edit.TextLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Measures text with the same Roboto font that is embedded into the PDF. */
@Singleton
class TextMetrics @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val typeface: Typeface by lazy { Typeface.createFromAsset(context.assets, PdfFonts.ASSET) }

    private val paint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = this@TextMetrics.typeface } }

    /** Width and height in points of [text] set at [size] points. */
    @Synchronized
    fun measure(text: String, size: Float): Pair<Float, Float> {
        paint.textSize = size
        val lines = text.split('\n')
        val width = lines.maxOf { paint.measureText(it) }
        val height = size * (TextLayout.ASCENT + DESCENT) + (lines.size - 1) * size * TextLayout.LINE_HEIGHT
        return width to height
    }

    private companion object {
        const val DESCENT = 0.25f
    }
}
