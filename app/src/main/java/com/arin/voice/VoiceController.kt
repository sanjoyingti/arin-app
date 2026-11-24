package com.arin.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

enum class VoiceState { Starting, Listening, Thinking, Speaking, Paused }

/**
 * Talks with the person using the phone's own speech recognition and voice. Arin listens,
 * hands the words to [onHeard], then reads the reply aloud sentence by sentence as it arrives.
 * Must be used on the main thread.
 */
class VoiceController(
    private val context: Context,
    private val onHeard: (String) -> Unit,
    private val onInterrupt: () -> Unit,
) {
    var state by mutableStateOf(VoiceState.Starting)
        private set
    /** Words heard so far in the current turn. */
    var partial by mutableStateOf("")
        private set
    /** Microphone loudness, 0..1, for the animation. */
    var level by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = mutableListOf<String>()
    private val queued = AtomicInteger(0)
    private var replyDone = false
    private var closed = false
    // Continuous mode: keep the mic open turn after turn; only a tap (or closing) stops it.
    private var userPaused = false
    private var errorRetries = 0
    var rate = 1.0f

    /** Use the natural ChatGPT-style voice (Pollinations) instead of the robotic phone voice. */
    var naturalVoice = true
    /** Which natural voice: nova, shimmer, alloy, echo, fable, onyx. */
    var voiceName = "nova"
        set(v) { field = v; neural?.voice = v }
    /** Use the real ChatGPT voice engine (OpenAI) instead of the free keyless one. */
    var voiceOpenAi = false
    /** OpenAI key for the ChatGPT voice engine (only used when [voiceOpenAi] is on). */
    var voiceKey = ""
    /** Let the person interrupt by talking while Arin speaks. */
    var bargeIn = true
    private var neural: NeuralVoice? = null
    private var gate: SpeechGate? = null
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    private var focusRequest: android.media.AudioFocusRequest? = null
    private val fillerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var fillerRunnable: Runnable? = null
    private var spokeThisReply = false

    private val audioAttributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Take audio focus so the car/Bluetooth switches to Arin and other apps duck while it talks. */
    private fun requestAudioFocus() {
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttributes)
            .build()
        focusRequest = req
        runCatching { audioManager.requestAudioFocus(req) }
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { fr -> runCatching { audioManager.abandonAudioFocusRequest(fr) } }
        focusRequest = null
    }

    private var beepMuted = false

    /** Silence the speech recognizer's start/stop beep while listening (it fires on every re-arm).
     *  setStreamMute is ref-counted per app and auto-restores if the process dies, so music can't
     *  get stuck muted. We hold audio focus anyway, so other apps' audio is already paused. */
    @Suppress("DEPRECATION")
    private fun muteBeep() {
        if (beepMuted) return
        runCatching { audioManager.setStreamMute(android.media.AudioManager.STREAM_MUSIC, true) }
        runCatching { audioManager.setStreamMute(android.media.AudioManager.STREAM_SYSTEM, true) }
        beepMuted = true
    }

    @Suppress("DEPRECATION")
    private fun unmuteBeep() {
        if (!beepMuted) return
        runCatching { audioManager.setStreamMute(android.media.AudioManager.STREAM_MUSIC, false) }
        runCatching { audioManager.setStreamMute(android.media.AudioManager.STREAM_SYSTEM, false) }
        beepMuted = false
    }

    /** While Arin speaks, listen for the person starting to talk so they can cut in without tapping. */
    private fun startGate() {
        if (!bargeIn || closed || gate != null) return
        gate = SpeechGate(onSpeech = { if (!closed) onBargeIn() }).also { it.start() }
    }

    private fun stopGate() {
        gate?.stop()
        gate = null
    }

    private fun onBargeIn() {
        if (closed || state != VoiceState.Speaking) return
        // The person started talking: stop Arin at once and listen to them.
        onInterrupt()
        replyDone = true
        listen()
    }

    fun start() {
        requestAudioFocus()
        if (naturalVoice) {
            neural = NeuralVoice(context.cacheDir).also {
                it.voice = voiceName
                // The ChatGPT voice engine only kicks in when it's chosen AND a key is present;
                // otherwise stay on the free keyless voice rather than falling to the robotic one.
                if (voiceOpenAi && voiceKey.isNotBlank()) {
                    it.engine = NeuralVoice.Engine.OpenAI
                    it.apiKey = voiceKey
                }
            }
            neural?.start(
                onClipDone = { mainThread { onUtteranceEnded() } },
                onFallback = { text -> mainThread { if (!closed) deviceSpeak(text, counted = true) } },
            )
            neural?.preload(FILLERS)
        }
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setAudioAttributes(audioAttributes)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { mainThread { onUtteranceEnded() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { mainThread { onUtteranceEnded() } }
                })
                pendingSpeech.toList().also { pendingSpeech.clear() }.forEach { deviceSpeak(it, counted = false) }
            }
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            error = "This phone has no speech recognition. Install or enable Google's speech services."
            state = VoiceState.Paused
            return
        }
        listen()
    }

    fun listen() {
        if (closed) return
        cancelFiller()
        stopGate()
        // Go silent before listening so the mic never hears Arin's own voice.
        tts?.stop()
        neural?.stop()
        muteBeep()
        queued.set(0)
        userPaused = false
        errorRetries = 0
        partial = ""
        error = null
        state = VoiceState.Listening
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            recognizer = it
            it.setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        r.cancel()
        r.startListening(intent)
    }

    /** A new reply is starting: nothing has been spoken from it yet. */
    fun replyStarted() {
        replyDone = false
        spokeThisReply = false
        state = VoiceState.Thinking
        // If the first sentence is slow to arrive, fill the silence with a quick "hmm, let me think"
        // like a person would, instead of a dead pause.
        if (naturalVoice && neural != null) scheduleFiller()
    }

    private fun scheduleFiller() {
        cancelFiller()
        val r = Runnable {
            // Only when the wait is genuinely long, and only now and then — not a reflex every turn.
            if (!closed && !spokeThisReply && state == VoiceState.Thinking && Math.random() < FILLER_CHANCE) {
                state = VoiceState.Speaking
                unmuteBeep()
                startGate()
                queued.incrementAndGet()
                neural?.speak(FILLERS.random())
            }
        }
        fillerRunnable = r
        fillerHandler.postDelayed(r, FILLER_DELAY_MS)
    }

    private fun cancelFiller() {
        fillerRunnable?.let { fillerHandler.removeCallbacks(it) }
        fillerRunnable = null
    }

    /** Re-arm the mic after a short gap so the conversation continues without a tap. */
    private fun reListen(delayMs: Long) {
        if (closed || userPaused) return
        fillerHandler.postDelayed({
            if (!closed && !userPaused && state != VoiceState.Speaking && state != VoiceState.Thinking) listen()
        }, delayMs)
    }

    /** Speak one sentence of the reply. */
    fun say(sentence: String) {
        val clean = speakable(sentence)
        if (clean.isBlank()) return
        // The real reply has started: no need for the "thinking" filler (if it hasn't played yet).
        spokeThisReply = true
        cancelFiller()
        unmuteBeep() // restore audio before Arin speaks (we mute it while listening)
        val n = neural
        if (naturalVoice && n != null) {
            state = VoiceState.Speaking
            startGate()
            queued.incrementAndGet()
            n.speak(clean)
            return
        }
        if (!ttsReady) { pendingSpeech += clean; return }
        deviceSpeak(clean, counted = false)
    }

    /** The reply is complete; listening resumes once the last sentence has been spoken. */
    fun replyFinished() {
        replyDone = true
        if (queued.get() == 0 && state != VoiceState.Listening) listen()
    }

    /** Tap on the orb: interrupt and listen, or pause the continuous listening. */
    fun tap() {
        when (state) {
            VoiceState.Speaking, VoiceState.Thinking -> { onInterrupt(); replyDone = true; listen() }
            VoiceState.Paused -> listen()
            // Tapping while it's listening pauses the conversation until the next tap.
            VoiceState.Listening -> { userPaused = true; cancelFiller(); runCatching { recognizer?.cancel() }; state = VoiceState.Paused }
            VoiceState.Starting -> {}
        }
    }

    fun close() {
        closed = true
        cancelFiller()
        stopGate()
        unmuteBeep()
        abandonAudioFocus()
        runCatching { recognizer?.destroy() }
        runCatching { tts?.stop(); tts?.shutdown() }
        runCatching { neural?.close() }
        recognizer = null
        tts = null
        neural = null
    }

    /**
     * Speak one sentence with the phone's own voice. [counted] is true when the slot was already
     * counted in [queued] (a natural-voice sentence that fell back to the device because it was offline).
     */
    private fun deviceSpeak(text: String, counted: Boolean) {
        state = VoiceState.Speaking
        unmuteBeep()
        startGate()
        if (!counted) queued.incrementAndGet()
        if (!ttsReady) { onUtteranceEnded(); return }
        tts?.setSpeechRate(rate)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "arin-${System.nanoTime()}")
    }

    private fun onUtteranceEnded() {
        if (queued.decrementAndGet() <= 0) {
            queued.set(0)
            if (replyDone) listen() else if (state == VoiceState.Speaking) { state = VoiceState.Thinking; stopGate() }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { level = 0f }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onResults(results: Bundle?) {
            errorRetries = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            // Nothing recognised: keep listening rather than stopping.
            if (text.isEmpty()) { if (!userPaused) reListen(250) else state = VoiceState.Paused; return }
            partial = text
            state = VoiceState.Thinking
            onHeard(text)
        }
        override fun onError(code: Int) {
            level = 0f
            // Tapped to stop, or shutting down: stay paused.
            if (closed || userPaused) { state = VoiceState.Paused; return }
            when (code) {
                // Heard nothing this turn: keep listening so the chat flows without a tap.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> reListen(250)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    error = "Arin needs microphone permission for voice mode."
                    state = VoiceState.Paused
                }
                // The recognizer can't run two sessions at once; back off briefly and retry.
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> reListen(400)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    if (errorRetries++ < 3) reListen(900)
                    else { error = "Speech recognition needs an internet connection right now."; state = VoiceState.Paused }
                }
                else -> if (errorRetries++ < 3) reListen(600) else state = VoiceState.Paused
            }
        }
    }

    private fun mainThread(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    companion object {
        /** Only fill the silence after a real wait, and only this fraction of the time. */
        private const val FILLER_DELAY_MS = 2200L
        private const val FILLER_CHANCE = 0.3

        /** Short, human "thinking out loud" lines played while the reply is still being formed. */
        val FILLERS = listOf(
            "Hmm, let me think.",
            "Let me think about that.",
            "Hmm.",
            "Okay, let me see.",
            "Give me a second.",
            "Right, let me think.",
        )

        /** Markdown and code read badly aloud: keep the words. */
        fun speakable(text: String): String = text
            .replace(Regex("""```.*?```""", RegexOption.DOT_MATCHES_ALL), " (code shown on screen) ")
            .replace(Regex("""`([^`]*)`"""), "$1")
            .replace(Regex("""\[([^\]]+)\]\([^)]*\)"""), "$1")
            .replace(Regex("""[*_#>|]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        private val sentenceEnd = Regex("""(?<=[.!?…:])\s+|\n{2,}""")

        /**
         * Splits [text] after [from] into whole sentences ready to speak. Returns the sentences
         * and the new offset; the unfinished tail is left for later.
         */
        fun nextSentences(text: String, from: Int, final: Boolean): Pair<List<String>, Int> {
            if (from >= text.length) return emptyList<String>() to from
            val rest = text.substring(from)
            val out = mutableListOf<String>()
            var consumed = 0
            var last = 0
            sentenceEnd.findAll(rest).forEach { m ->
                val piece = rest.substring(last, m.range.last + 1).trim()
                if (piece.isNotEmpty()) out += piece
                last = m.range.last + 1
                consumed = last
            }
            if (final && last < rest.length) {
                rest.substring(last).trim().takeIf { it.isNotEmpty() }?.let { out += it }
                consumed = rest.length
            }
            return out to from + consumed
        }
    }
}
