package com.arin.seclab

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One entry of the tool catalogue: what it is for, how to install it and how to check it works. */
@Serializable
data class SecurityTool(
    val name: String,
    val description: String,
    val category: String,
    val install: List<String> = emptyList(),
    val verify: String = "",
    val size: String = "small",
    val needsRoot: Boolean = false,
    val core: Boolean = false,
)

/** Reads the bundled catalogue. */
object SecurityCatalogue {
    private val json = Json { ignoreUnknownKeys = true }

    /** In the order categories are shown. */
    val categories = listOf("Base", "Recon", "Web", "Passwords", "Forensics", "Reverse", "Runtime", "Capture", "OSINT", "Exploits", "Wordlists")

    fun load(context: Context): List<SecurityTool> = runCatching {
        val text = context.assets.open("security_tools.json").bufferedReader().use { it.readText() }
        json.decodeFromString<List<SecurityTool>>(text).sortedBy { categories.indexOf(it.category).takeIf { i -> i >= 0 } ?: 99 }
    }.getOrDefault(emptyList())
}
