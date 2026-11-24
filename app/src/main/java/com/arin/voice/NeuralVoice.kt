package com.arin.voice

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ChatGPT-style natural voice. Each sentence is turned into speech by Pollinations' free OpenAI-voice TTS
 * (model=openai-audio, voices alloy/echo/fable/onyx/nova/shimmer — no key) and played in order. When a sentence
 * can't be fetched (offline), it calls [onFallback] so the caller can use the phone's own voice instead.
 */
class NeuralVoice(private val cacheDir: File) {
    enum class Engine { Free, OpenAI }

    /** One of: alloy, echo, fable, onyx, nova, shimmer. */
    @Volatile var voice = "nova"
    /** Free = keyless Pollinations; OpenAI = the real ChatGPT voice engine with [apiKey]. */
    @Volatile var engine = Engine.Free
    @Volatile var apiKey = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = Channel<Pair<Int, String>>(Channel.UNLIMITED)
    private var player: MediaPlayer? = null
    @Volatile private var gen = 0
    private var worker: Job? = null
    private var onClipDone: () -> Unit = {}
    private var onFallback: (String) -> Unit = {}
    // Short phrases (the "hmm, let me think" fillers) are cached as audio so they replay instantly.
    private val cache = ConcurrentHashMap<String, ByteArray>()

    /** Fetch short phrases ahead of time so the first filler plays with no wait. */
    fun preload(phrases: List<String>) {
        scope.launch { withContext(Dispatchers.IO) { phrases.forEach { fetch(it) } } }
    }

    fun start(onClipDone: () -> Unit, onFallback: (String) -> Unit) {
        this.onClipDone = onClipDone
        this.onFallback = onFallback
        worker = scope.launch {
            for ((g, text) in queue) {
                if (g != gen) continue
                val bytes = withContext(Dispatchers.IO) { fetch(text) }
                if (g != gen) continue
                if (bytes == null) { onFallback(text); continue }
                playAndWait(bytes)
                if (g == gen) onClipDone()
            }
        }
    }

    fun speak(text: String) { queue.trySend(gen to text) }

    /** Stop immediately and drop anything queued (barge-in, or a new turn). */
    fun stop() {
        gen++
        runCatching { player?.stop(); player?.release() }
        player = null
    }

    fun close() {
        stop()
        worker?.cancel()
        scope.cancel()
    }

    private fun fetch(text: String): ByteArray? {
        val key = "$engine|$voice|$text"
        cache[key]?.let { return it }
        return runCatching {
            val req = when (engine) {
                Engine.OpenAI -> openAiRequest(text) ?: return null
                Engine.Free -> pollinationsRequest(text)
            }
            http.newCall(req).execute().use { r ->
                val body = r.body ?: return null
                val bytes = body.bytes()
                val type = r.header("Content-Type").orEmpty()
                // A real clip is audio; an error usually comes back as a short bit of text/JSON.
                val ok = r.isSuccessful && (type.startsWith("audio") || bytes.size >= 2_000)
                if (!ok) null else bytes.also { if (text.length <= 48) cache[key] = it }
            }
        }.getOrNull()
    }

    private fun pollinationsRequest(text: String): Request {
        val t = text.take(900)
        val url = "https://text.pollinations.ai/" + URLEncoder.encode(t, "UTF-8") + "?model=openai-audio&voice=" + voice
        // A referrer gives the free tier a steadier rate limit than fully anonymous requests.
        return Request.Builder().url(url)
            .header("User-Agent", "Arin-Android")
            .header("Referer", "https://arin.app")
            .build()
    }

    /** The ChatGPT voice engine: gpt-4o-mini-tts with an emotion-steering instruction. */
    private fun openAiRequest(text: String): Request? {
        if (apiKey.isBlank()) return null
        val payload = org.json.JSONObject()
            .put("model", "gpt-4o-mini-tts")
            .put("input", text.take(1500))
            .put("voice", voice)
            .put("instructions", OPENAI_STYLE)
            .put("response_format", "mp3")
            .toString()
        return Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private suspend fun playAndWait(bytes: ByteArray) = withContext(Dispatchers.Main) {
        val file = File.createTempFile("arin-tts", ".mp3", cacheDir)
        runCatching { file.writeBytes(bytes) }
        suspendCancellableCoroutine<Unit> { cont ->
            val mp = MediaPlayer()
            // Route to the active media output — phone speaker, or the car / Bluetooth speaker when connected.
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player = mp
            fun finish() {
                runCatching { mp.release() }
                if (player === mp) player = null
                runCatching { file.delete() }
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            }
            try {
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { finish() }
                mp.setOnErrorListener { _, _, _ -> finish(); true }
                mp.prepare()
                mp.start()
            } catch (e: Exception) {
                finish()
            }
            cont.invokeOnCancellation { runCatching { mp.release(); file.delete() } }
        }
    }

    companion object {
        val VOICES = listOf("nova", "shimmer", "alloy", "echo", "fable", "onyx")
        /** Tone steering for the ChatGPT voice engine, so it sounds warm and human rather than flat. */
        const val OPENAI_STYLE = "Speak like a warm, friendly voice assistant on a phone call: natural, " +
            "upbeat and conversational, with easy pacing and small natural pauses. Let a little emotion through — " +
            "a light laugh when something is funny, a thoughtful 'hmm' when considering something. Never robotic or monotone."
    }
}
