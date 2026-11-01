package app.pdfdoc.data.ocr

import android.content.Context
import android.graphics.Bitmap
import app.pdfdoc.domain.edit.Box
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A recognized word with its bounding box in image pixels. */
data class OcrWord(val text: String, val box: Box, val confidence: Float)

/** Languages with a bundled tessdata_fast model, in the order shown in settings. */
object OcrLanguages {
    val ALL = listOf("rus", "eng", "ukr", "deu", "fra", "spa", "ita", "pol", "srp", "srp_latn")
    private val ISO = mapOf("rus" to "ru", "eng" to "en", "ukr" to "uk", "deu" to "de", "fra" to "fr", "spa" to "es", "ita" to "it", "pol" to "pl")

    fun label(context: Context, code: String): String = when (code) {
        "srp" -> context.getString(app.pdfdoc.R.string.lang_serbian_cyrillic)
        "srp_latn" -> context.getString(app.pdfdoc.R.string.lang_serbian_latin)
        else -> java.util.Locale(ISO[code] ?: code).getDisplayLanguage(context.resources.configuration.locales[0])
            .replaceFirstChar { it.titlecase() }
    }
}

/** Offline OCR with Tesseract and the bundled tessdata_fast models. */
@Singleton
class OcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root get() = File(context.filesDir, "tesseract")

    @Volatile private var current: TessBaseAPI? = null

    /** Recognizes words in [bitmap]. Blocking and CPU heavy: call off the main thread. */
    @Synchronized
    fun recognize(bitmap: Bitmap, languages: String = LANGUAGES): List<OcrWord> {
        val codes = languages.split('+').filter { it in OcrLanguages.ALL }.ifEmpty { listOf("eng") }.joinToString("+")
        ensureModels(codes)
        val api = TessBaseAPI()
        current = api
        try {
            if (!api.init(root.absolutePath, codes)) throw IOException("Tesseract init failed")
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setImage(bitmap)
            api.getUTF8Text() // runs recognition
            val words = mutableListOf<OcrWord>()
            val iterator = api.resultIterator ?: return words
            try {
                iterator.begin()
                do {
                    val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim()
                    if (!text.isNullOrEmpty()) {
                        val r = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        val confidence = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        words += OcrWord(text, Box(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()), confidence)
                    }
                } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
            } finally {
                iterator.delete()
            }
            return words
        } finally {
            current = null
            api.recycle()
        }
    }

    /** Interrupts a running recognition (from another thread). */
    fun cancel() {
        current?.stop()
    }

    /**
     * Tesseract reads models from a real directory, so the needed ones are unpacked from the APK
     * on first use (and again after an app update, which may bring new models).
     */
    private fun ensureModels(languages: String) {
        val dir = File(root, "tessdata").apply { mkdirs() }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        languages.split('+').filter { it in OcrLanguages.ALL }.forEach { code ->
            val name = "$code.traineddata"
            val target = File(dir, name)
            val marker = File(dir, "$name.version")
            if (!target.exists() || marker.takeIf { it.exists() }?.readText() != version) {
                val tmp = File(dir, "$name.tmp")
                context.assets.open("tessdata/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(target)
                marker.writeText(version)
            }
        }
    }

    companion object {
        const val LANGUAGES = "rus+eng"
    }
}
