package com.arin.free

/** A free way to get tokens for Arin. Keyless ones need nothing; the rest need one free key, added once. */
data class FreeProvider(
    val name: String,
    /** What you get, in plain words (e.g. "~1 billion tokens / month"). */
    val tokens: String,
    val note: String,
    /** OpenAI-compatible base URL (blank for the built-in keyless option). */
    val baseUrl: String = "",
    /** Where to get the free key (opened for the person; blank for keyless). */
    val keyPage: String = "",
    /** A sensible default model id for this provider. */
    val model: String = "",
    val keyless: Boolean = false,
)

/**
 * The free-token methods shown in the Free tab. New methods can be added here later without touching the screen.
 * Keyless first (works instantly), then the big free tiers that each need one free key.
 */
object FreeProviders {
    val all = listOf(
        FreeProvider(
            name = "Keyless free models",
            tokens = "Instant · no key, no signup",
            note = "Works right away. A handful of free models (GPT-OSS, DeepSeek, Qwen, Llama) pooled from Pollinations and LLM7, with automatic fallover. Rate-limited, best for everyday chat.",
            keyless = true,
        ),
        FreeProvider(
            name = "Mistral",
            tokens = "~1 billion tokens / month",
            note = "The big free tier from the video. One free key, no credit card — then Arin uses it everywhere.",
            baseUrl = "https://api.mistral.ai/v1",
            keyPage = "https://console.mistral.ai/api-keys",
            model = "mistral-small-latest",
        ),
        FreeProvider(
            name = "Google Gemini",
            tokens = "Large free tier",
            note = "Fast and high quality. Free key from Google AI Studio, no card.",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            keyPage = "https://aistudio.google.com/app/apikey",
            model = "gemini-2.0-flash",
        ),
        FreeProvider(
            name = "Groq",
            tokens = "~14,400 requests / day",
            note = "Very fast open models (Llama 3.3 70B and more). Free key, no card.",
            baseUrl = "https://api.groq.com/openai/v1",
            keyPage = "https://console.groq.com/keys",
            model = "llama-3.3-70b-versatile",
        ),
        FreeProvider(
            name = "Cerebras",
            tokens = "Large daily free cap",
            note = "Extremely fast. Free key, no card.",
            baseUrl = "https://api.cerebras.ai/v1",
            keyPage = "https://cloud.cerebras.ai",
            model = "llama-3.3-70b",
        ),
        FreeProvider(
            name = "OpenRouter",
            tokens = "Many free models",
            note = "A whole catalog of :free models behind one key. No card.",
            baseUrl = "https://openrouter.ai/api/v1",
            keyPage = "https://openrouter.ai/keys",
            model = "deepseek/deepseek-chat-v3-0324:free",
        ),
        FreeProvider(
            name = "NVIDIA NIM",
            tokens = "Free evaluation tier",
            note = "Llama, Qwen, DeepSeek and more. Free key.",
            baseUrl = "https://integrate.api.nvidia.com/v1",
            keyPage = "https://build.nvidia.com",
            model = "meta/llama-3.3-70b-instruct",
        ),
        FreeProvider(
            name = "GitHub Models",
            tokens = "Free GPT-4o / 4.1",
            note = "Uses a GitHub token (Models scope). Free.",
            baseUrl = "https://models.github.ai/inference",
            keyPage = "https://github.com/settings/tokens",
            model = "openai/gpt-4o-mini",
        ),
        FreeProvider(
            name = "SambaNova",
            tokens = "Free tier",
            note = "Very fast Llama models. Free key.",
            baseUrl = "https://api.sambanova.ai/v1",
            keyPage = "https://cloud.sambanova.ai",
            model = "Meta-Llama-3.3-70B-Instruct",
        ),
        FreeProvider(
            name = "Cohere",
            tokens = "Free trial keys",
            note = "Command models. Note: Cohere's trial forbids some commercial use.",
            baseUrl = "https://api.cohere.ai/compatibility/v1",
            keyPage = "https://dashboard.cohere.com/api-keys",
            model = "command-r-08-2024",
        ),
    )
}
