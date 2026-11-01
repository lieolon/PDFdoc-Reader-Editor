package app.pdfdoc.ui.viewer

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.text.PageText
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReadAloudState(
    val active: Boolean = false,
    val playing: Boolean = false,
    val page: Int = 0,
    val sentence: Int = 0,
    /** Rectangles of the sentence being spoken, on [page]. */
    val rects: List<Box> = emptyList(),
    val rate: Float = 1f,
    /** The device has no speech engine or it failed to start. */
    val unavailable: Boolean = false,
)

/**
 * Reads the document aloud sentence by sentence with the system text-to-speech engine,
 * which works offline with downloaded voices. Highlights what is being read.
 */
class ReadAloud(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pageCount: () -> Int,
    private val pageText: suspend (Int) -> PageText?,
) {
    private val _state = MutableStateFlow(ReadAloudState())
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private var job: Job? = null
    private val done = Channel<String>(Channel.UNLIMITED)

    fun start(page: Int, sentence: Int = 0) {
        _state.update { it.copy(active = true, playing = true, page = page, sentence = sentence, unavailable = false) }
        play()
    }

    fun pause() {
        job?.cancel()
        tts?.stop()
        _state.update { it.copy(playing = false) }
    }

    fun resume() {
        if (!state.value.active) return
        _state.update { it.copy(playing = true) }
        play()
    }

    fun skip(delta: Int) {
        val s = state.value
        if (!s.active) return
        job?.cancel()
        tts?.stop()
        _state.update { it.copy(sentence = (it.sentence + delta).coerceAtLeast(0), playing = true) }
        play()
    }

    fun setRate(rate: Float) {
        _state.update { it.copy(rate = rate) }
        tts?.setSpeechRate(rate)
    }

    fun stop() {
        job?.cancel()
        tts?.stop()
        _state.value = ReadAloudState(rate = state.value.rate)
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun play() {
        job?.cancel()
        job = scope.launch {
            if (!ensureEngine()) {
                _state.update { ReadAloudState(unavailable = true, rate = it.rate) }
                return@launch
            }
            var page = state.value.page
            var sentence = state.value.sentence
            while (page < pageCount()) {
                val text = pageText(page)
                val sentences = text?.sentences().orEmpty()
                if (text != null && sentence < sentences.size) {
                    applyLanguage(text.text)
                }
                while (text != null && sentence < sentences.size) {
                    val range = sentences[sentence]
                    _state.update { it.copy(page = page, sentence = sentence, rects = text.rects(range)) }
                    speak(text.text(range))
                    sentence++
                }
                page++
                sentence = 0
                _state.update { it.copy(page = page.coerceAtMost(pageCount() - 1), sentence = 0, rects = emptyList()) }
            }
            stop()
        }
    }

    private suspend fun speak(text: String) {
        val engine = tts ?: return
        val id = UUID.randomUUID().toString()
        engine.setSpeechRate(state.value.rate)
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS) return
        while (true) {
            if (done.receive() == id) return
        }
    }

    /** Picks Russian or the default language depending on the script of the page. */
    private fun applyLanguage(text: String) {
        val engine = tts ?: return
        val cyrillic = text.count { it in 'Ѐ'..'ӿ' }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        val locale = if (cyrillic > latin) Locale("ru") else if (latin > 0) Locale.ENGLISH else Locale.getDefault()
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
    }

    private suspend fun ensureEngine(): Boolean {
        ready?.let { return it.await() }
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        tts = TextToSpeech(context) { status -> deferred.complete(status == TextToSpeech.SUCCESS) }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { utteranceId?.let(done::trySend) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { utteranceId?.let(done::trySend) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let(done::trySend) }
            })
        }
        val ok = deferred.await()
        if (!ok) ready = null
        return ok
    }
}
