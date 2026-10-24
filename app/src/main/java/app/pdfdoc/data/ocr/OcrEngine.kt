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

/** Offline OCR with Tesseract and the bundled tessdata_fast models (Russian + English). */
@Singleton
class OcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root get() = File(context.filesDir, "tesseract")

    @Volatile private var current: TessBaseAPI? = null

    /** Recognizes words in [bitmap]. Blocking and CPU heavy: call off the main thread. */
    @Synchronized
    fun recognize(bitmap: Bitmap, languages: String = LANGUAGES): List<OcrWord> {
        ensureModels()
        val api = TessBaseAPI()
        current = api
        try {
            if (!api.init(root.absolutePath, languages)) throw IOException("Tesseract init failed")
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

    /** Tesseract reads models from a real directory, so copy them out of the APK once. */
    private fun ensureModels() {
        val dir = File(root, "tessdata").apply { mkdirs() }
        MODELS.forEach { name ->
            val target = File(dir, name)
            val size = context.assets.openFd("tessdata/$name").use { it.length }
            if (target.length() != size) {
                val tmp = File(dir, "$name.tmp")
                context.assets.open("tessdata/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(target)
            }
        }
    }

    companion object {
        const val LANGUAGES = "rus+eng"
        private val MODELS = listOf("rus.traineddata", "eng.traineddata")
    }
}
