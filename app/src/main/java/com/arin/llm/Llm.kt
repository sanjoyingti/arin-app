package com.arin.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Talks to every provider. Free models, Gemini and ChatGPT share the OpenAI chat format;
 * Claude uses the Anthropic Messages format.
 */
object Llm {
    private const val FREE_BASE = "https://text.pollinations.ai"
    // A second keyless, OpenAI-compatible free source (no account, no key). Its models are tagged with this prefix.
    private const val LLM7_BASE = "https://api.llm7.io/v1"
    private const val LLM7_PREFIX = "llm7:"
    private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/openai"
    private const val OPENAI_BASE = "https://api.openai.com/v1"
    private const val ANTHROPIC_BASE = "https://api.anthropic.com/v1"
    private const val ANTHROPIC_VERSION = "2023-06-01"

    /**
     * Streams a reply. [maxTokens] asks for a longer answer (Build mode writes whole apps); null leaves the
     * service's default, which for some (DeepSeek-style APIs) is only about 4,000 tokens.
     */
    /**
     * "Think less" (Settings): ask for low reasoning effort (and, for DeepSeek, thinking off), since thinking is
     * billed as output. If a service rejects those fields, the request is sent again without them.
     */
    @Volatile var thinkLess: Boolean = false

    fun stream(provider: Provider, key: String, model: String, system: String, turns: List<Turn>, maxTokens: Int? = null, cache: Boolean = false, endpoint: CustomApi.Endpoint? = null): Flow<Delta> {
        if (provider.needsKey && key.isBlank()) {
            throw LlmException("Add your ${provider.label} API key in Settings, or switch to Free models.")
        }
        if (model.isBlank()) throw LlmException("Pick a model first, or type a model id in Settings → Custom API.")
        return when (provider) {
            Provider.Free -> freeStream(model, system, turns, maxTokens)
            Provider.Gemini -> lessThinking { low -> openAiStream("$GEMINI_BASE/chat/completions", bearer(key), model, system, turns, maxTokens = maxTokens, lowReasoning = low) }
            // OpenAI's newer models take max_completion_tokens; leave ChatGPT on its default.
            Provider.ChatGPT -> openAiStream("$OPENAI_BASE/chat/completions", bearer(key), model, system, turns)
            Provider.Claude -> anthropicStream("$ANTHROPIC_BASE/messages", anthropicHeaders(key), key, model, system, turns, maxTokens, cache)
            Provider.Custom -> (endpoint ?: CustomApi.current()).let { ep ->
                when (ep.format) {
                    ApiFormat.OpenAI -> lessThinking { low -> openAiStream(ep.chatUrl(), bearer(key), model, system, turns, maxTokens = maxTokens, lowReasoning = low) }
                    ApiFormat.Anthropic -> anthropicStream(ep.chatUrl(), customAnthropicHeaders(key), key, model, system, turns, maxTokens, cache)
                }
            }
        }
    }

    /** Run with "think less" fields when it's on; if the service refuses them before answering, run without. */
    private fun lessThinking(make: (Boolean) -> Flow<Delta>): Flow<Delta> {
        if (!thinkLess) return make(false)
        return kotlinx.coroutines.flow.flow {
            var started = false
            try {
                make(true).collect { started = true; emit(it) }
            } catch (e: LlmException) {
                val m = e.message.orEmpty().lowercase()
                val refused = listOf("reasoning", "thinking", "unrecognized", "unknown", "not supported", "unsupported", "invalid param", "extra")
                    .any { it in m }
                if (started || !refused) throw e
                make(false).collect { emit(it) }
            }
        }
    }

    private data class FreeTarget(val url: String, val headers: Map<String, String>, val model: String, val extra: Boolean)

    /** The free sources to try, the picked model first, then keyless fallbacks across both providers. */
    private fun freeTargets(model: String): List<FreeTarget> {
        fun pol(m: String) = FreeTarget("$FREE_BASE/openai", emptyMap(), m, true)
        fun llm7(m: String) = FreeTarget("$LLM7_BASE/chat/completions", bearer("unused"), m, false)
        val chosen = if (model.startsWith(LLM7_PREFIX)) llm7(model.removePrefix(LLM7_PREFIX)) else pol(model)
        // The person's own free keys (Free tokens tab) — bigger and faster, so they come before the keyless fallbacks.
        val pooled = CustomApi.freePool.map { FreeTarget("${it.baseUrl}/chat/completions", bearer(it.key), it.model.ifBlank { "auto" }, false) }
        val keyless = listOf(pol("openai"), llm7("gpt-4o-mini"), pol("mistral"), llm7("deepseek-r1"))
        return (listOf(chosen) + pooled + keyless).distinctBy { it.url + "|" + it.model }
    }

    /** How long a free model may take to send its first word before Arin gives up and tries a faster free model. */
    private const val FREE_FIRST_TOKEN_MS = 9_000L

    /**
     * Free models with automatic fallover: if the chosen keyless model errors, is rate-limited, OR is just slow to
     * start (a queued free proxy), Arin quietly switches to the next free model — so a reply still comes quickly.
     */
    private fun freeStream(model: String, system: String, turns: List<Turn>, maxTokens: Int?): Flow<Delta> =
        kotlinx.coroutines.flow.channelFlow {
            val targets = freeTargets(model)
            var last: Throwable? = null
            for ((i, t) in targets.withIndex()) {
                val inner = kotlinx.coroutines.channels.Channel<Delta>(kotlinx.coroutines.channels.Channel.BUFFERED)
                val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                    try {
                        openAiStream(t.url, t.headers, t.model, system, turns, extra = t.extra, maxTokens = maxTokens).collect { inner.send(it) }
                        inner.close()
                    } catch (e: Throwable) {
                        inner.close(e)
                    }
                }
                // Wait for the FIRST word, with a deadline. No deadline once words are flowing.
                val first = kotlinx.coroutines.withTimeoutOrNull(FREE_FIRST_TOKEN_MS) { inner.receiveCatching() }
                if (first == null) {
                    job.cancel(); last = LlmException("A free model was slow to start; Arin switched to a faster one."); continue
                }
                if (first.isClosed) {
                    last = first.exceptionOrNull() ?: last
                    job.cancel()
                    if (i == targets.lastIndex) throw (last ?: LlmException("No free model answered."))
                    continue
                }
                // First word arrived: commit to this free model and forward the whole reply.
                send(first.getOrThrow())
                for (d in inner) send(d)
                job.join()
                return@channelFlow
            }
            throw (last ?: LlmException("No free model answered."))
        }

    /** The provider's current models, newest-looking first; falls back to the built-in list. */
    suspend fun models(provider: Provider, key: String): List<ModelOption> {
        val fallback = DefaultModels.of(provider)
        if (provider.needsKey && key.isBlank()) return fallback
        val found = runCatching {
            when (provider) {
                Provider.Free -> (runCatching { freeModels() }.getOrDefault(emptyList()) + runCatching { llm7Models() }.getOrDefault(emptyList()))
                    .ifEmpty { fallback }
                Provider.Claude -> listIds("$ANTHROPIC_BASE/models", anthropicHeaders(key), "data", "id", "display_name")
                Provider.Gemini -> listIds("$GEMINI_BASE/models", bearer(key), "data", "id", null)
                    .filter { it.id.contains("gemini") }
                    .map { ModelOption(it.id.removePrefix("models/"), it.label.removePrefix("models/")) }
                Provider.ChatGPT -> listIds("$OPENAI_BASE/models", bearer(key), "data", "id", null)
                    .filter { m -> m.id.startsWith("gpt-") && listOf("audio", "realtime", "image", "tts", "transcribe", "search").none { it in m.id } }
                    .sortedByDescending { it.id }
                Provider.Custom -> customModels(key)
            }
        }.getOrNull().orEmpty()
        if (provider == Provider.Custom) {
            // Keep the typed model id at the top even when the service lists others.
            val manual = CustomApi.manualModel
            // Models this service is known for but doesn't always list (e.g. ones that need access turned on).
            val known = CustomApi.presetFor()?.models.orEmpty()
                .filter { (_, id) -> found.none { it.id == id } }
                .map { (label, id) -> ModelOption(id, label) }
            val typed = if (manual.isNotBlank() && found.none { it.id == manual } && known.none { it.id == manual }) {
                listOf(ModelOption(manual, manual))
            } else emptyList()
            if (typed.isNotEmpty() || known.isNotEmpty()) return typed + known + found
        }
        return found.ifEmpty { fallback }
    }

    private fun bearer(key: String) = mapOf("Authorization" to "Bearer $key")

    private fun anthropicHeaders(key: String) = mapOf("x-api-key" to key, "anthropic-version" to ANTHROPIC_VERSION)

    /** Claude-compatible gateways accept either header; send both. */
    private fun customAnthropicHeaders(key: String) = anthropicHeaders(key) + bearer(key)

    /** Ids that are clearly not chat models. */
    private val notChat = listOf(
        "embed", "whisper", "tts", "transcribe", "moderation", "rerank", "dall-e", "gpt-image",
        "imagen", "flux", "stable-diffusion", "sdxl", "guard",
    )

    private suspend fun customModels(key: String): List<ModelOption> {
        val headers = if (CustomApi.format == ApiFormat.Anthropic) customAnthropicHeaders(key) else bearer(key)
        return listIds(CustomApi.modelsUrl(), headers, "data", "id", if (CustomApi.format == ApiFormat.Anthropic) "display_name" else "name")
            .filter { m -> notChat.none { m.id.lowercase().contains(it) } }
    }

    private suspend fun listIds(url: String, headers: Map<String, String>, arrayKey: String, idKey: String, labelKey: String?): List<ModelOption> {
        val root = Http.getJson(url, headers) as? JsonObject ?: return emptyList()
        val items = root[arrayKey] as? JsonArray ?: return emptyList()
        return items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o[idKey] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val label = labelKey?.let { (o[it] as? JsonPrimitive)?.contentOrNull } ?: id
            ModelOption(id, label)
        }
    }

    private suspend fun freeModels(): List<ModelOption> {
        val list = Http.getJson("$FREE_BASE/models") as? JsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            // Only models open to everyone with no key.
            val tier = (o["tier"] as? JsonPrimitive)?.contentOrNull
            if (tier != null && tier != "anonymous") return@mapNotNull null
            val output = (o["output_modalities"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (output != null && "text" !in output) return@mapNotNull null
            if ((o["uncensored"] as? JsonPrimitive)?.contentOrNull == "true") return@mapNotNull null
            val desc = (o["description"] as? JsonPrimitive)?.contentOrNull?.substringBefore(" (")
            ModelOption(name, "${desc ?: name} (free)")
        }
    }

    /** Keyless free models from LLM7 (no account). Text chat models only. */
    private suspend fun llm7Models(): List<ModelOption> {
        val root = Http.getJson("$LLM7_BASE/models")
        val arr = (root as? JsonObject)?.get("data") as? JsonArray ?: root as? JsonArray ?: return emptyList()
        val skip = listOf("flux", "sd", "dall", "whisper", "tts", "image", "embed", "audio", "vision")
        return arr.mapNotNull { el ->
            val id = ((el as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (skip.any { id.contains(it, true) }) return@mapNotNull null
            ModelOption("$LLM7_PREFIX$id", "$id (free · LLM7)")
        }.take(14)
    }

    private fun openAiStream(
        url: String,
        headers: Map<String, String>,
        model: String,
        system: String,
        turns: List<Turn>,
        extra: Boolean = false,
        maxTokens: Int? = null,
        lowReasoning: Boolean = false,
    ): Flow<Delta> {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            if (maxTokens != null) put("max_tokens", maxTokens)
            if (lowReasoning) {
                put("reasoning_effort", "low")
                // DeepSeek: thinking on by default and billed as output; switch it off.
                if (model.contains("deepseek", ignoreCase = true)) putJsonObject("thinking") { put("type", "disabled") }
            }
            if (extra) {
                put("private", true) // keep prompts out of the free service's public feed
                put("referrer", "arin-android")
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                turns.forEach { t ->
                    addJsonObject {
                        put("role", if (t.fromUser) "user" else "assistant")
                        if (!t.hasMedia) {
                            put("content", t.text)
                        } else {
                            // OpenAI-style parts: text, images as data URLs, PDFs as files.
                            putJsonArray("content") {
                                addJsonObject { put("type", "text"); put("text", t.text) }
                                t.images.forEach { img ->
                                    addJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") { put("url", "data:${img.mime};base64,${img.base64}") }
                                    }
                                }
                                t.pdfs.forEach { pdf ->
                                    addJsonObject {
                                        put("type", "file")
                                        putJsonObject("file") {
                                            put("filename", pdf.name)
                                            put("file_data", "data:application/pdf;base64,${pdf.base64}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.toString()
        return Http.sse(url, headers, body).mapNotNull { data ->
            val o = Http.parseObject(data) ?: return@mapNotNull null
            (o["error"] as? JsonObject)?.let { err ->
                throw LlmException((err["message"] as? JsonPrimitive)?.contentOrNull ?: "The model returned an error.")
            }
            val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
            val delta = choice["delta"] as? JsonObject ?: return@mapNotNull null
            val text = (delta["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val thinking = ((delta["reasoning_content"] ?: delta["reasoning"]) as? JsonPrimitive)?.contentOrNull.orEmpty()
            when {
                text.isNotEmpty() -> Delta.Text(text)
                thinking.isNotEmpty() -> Delta.Thinking(thinking)
                else -> null
            }
        }
    }

    private fun anthropicStream(url: String, headers: Map<String, String>, key: String, model: String, system: String, turns: List<Turn>, maxTokens: Int? = null, cache: Boolean = false): Flow<Delta> {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens ?: 8192)
            put("stream", true)
            // Prompt caching: cache the big stable prefix (system + conversation) so each turn re-reads it
            // at a fraction of the cost. Full context is still sent — nothing is dropped.
            if (cache) {
                putJsonArray("system") {
                    addJsonObject {
                        put("type", "text"); put("text", system)
                        putJsonObject("cache_control") { put("type", "ephemeral") }
                    }
                }
            } else put("system", system)
            val lastIdx = turns.lastIndex
            putJsonArray("messages") {
                turns.forEachIndexed { idx, t ->
                    val markCache = cache && idx == lastIdx   // breakpoint at the end of the prefix
                    addJsonObject {
                        put("role", if (t.fromUser) "user" else "assistant")
                        if (!t.hasMedia && !markCache) {
                            put("content", t.text)
                        } else {
                            // Anthropic content blocks: documents and images first, then the question.
                            putJsonArray("content") {
                                t.pdfs.forEach { pdf ->
                                    addJsonObject {
                                        put("type", "document")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", "application/pdf")
                                            put("data", pdf.base64)
                                        }
                                        put("title", pdf.name)
                                    }
                                }
                                t.images.forEach { img ->
                                    addJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", img.mime)
                                            put("data", img.base64)
                                        }
                                    }
                                }
                                addJsonObject {
                                    put("type", "text"); put("text", t.text)
                                    if (markCache) putJsonObject("cache_control") { put("type", "ephemeral") }
                                }
                            }
                        }
                    }
                }
            }
        }.toString()
        return Http.sse(url, headers, body).mapNotNull { data ->
            val o = Http.parseObject(data) ?: return@mapNotNull null
            when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
                "error" -> {
                    val err = o["error"] as? JsonObject
                    throw LlmException((err?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Claude returned an error.")
                }
                "content_block_delta" -> {
                    val d = o["delta"] as? JsonObject ?: return@mapNotNull null
                    when ((d["type"] as? JsonPrimitive)?.contentOrNull) {
                        "text_delta" -> (d["text"] as? JsonPrimitive)?.contentOrNull?.let { Delta.Text(it) }
                        "thinking_delta" -> (d["thinking"] as? JsonPrimitive)?.contentOrNull?.let { Delta.Thinking(it) }
                        else -> null
                    }
                }
                else -> null
            }
        }
    }
}
