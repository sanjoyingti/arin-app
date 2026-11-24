package com.arin.builder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The build toolchain (Gradle, Android Gradle plugin, Kotlin, SDK, AndroidX libraries…), kept up to date by itself:
 * before a build, Arin looks up the newest STABLE release of each one online (at most every few hours) and uses it.
 * Every version that builds successfully is remembered as "last good"; if builds start failing after an upgrade,
 * [fallBack] returns to the last good set until the next successful build.
 */
object Toolchain {
    /** Built-in defaults: the newest stable versions when this Arin was built (checked 24 Nov 2026). */
    private val defaults = linkedMapOf(
        "AGP" to "9.5.2",
        "KOTLIN" to "2.4.21",
        "COMPOSE_BOM" to "2026.11.01",
        "GRADLE" to "9.9.2",
        "JDK" to "21",
        "SDK" to "37",
        "CORE" to "1.19.1",
        "ACTIVITY" to "1.13.0",
        "LIFECYCLE" to "2.11.1",
        "NAVIGATION" to "2.10.2",
        "COROUTINES" to "1.11.0",
        "SERIALIZATION" to "1.13.0",
        "KSP" to "2.3.14",
    )

    private const val PREFS = "toolchain"
    private const val CHECK_EVERY_MS = 6 * 60 * 60_000L
    private const val PAUSE_AFTER_FALLBACK_MS = 24 * 60 * 60_000L

    private fun prefs(): android.content.SharedPreferences? =
        runCatching { com.arin.ArinApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()

    /** The version of [name] to use now. */
    fun get(name: String): String = prefs()?.getString("cur_$name", null) ?: defaults.getValue(name)

    /** The versions in use, for the log. */
    fun summary(): String = defaults.keys.joinToString(", ") { "$it ${get(it)}" }

    private val http by lazy { OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build() }

    private fun fetch(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Arin").build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    private fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }
    private val byVersion = Comparator<String> { a, b ->
        val x = parts(a); val y = parts(b)
        (0 until maxOf(x.size, y.size)).map { (x.getOrElse(it) { 0 }).compareTo(y.getOrElse(it) { 0 }) }.firstOrNull { it != 0 } ?: 0
    }

    /** Newest stable (digits and dots only: no alpha, beta, RC, dev) of a list. */
    private fun newest(versions: List<String>): String? =
        versions.map { it.trim() }.filter { Regex("""\d+(\.\d+)+""").matches(it) }.maxWithOrNull(byVersion)

    private fun google(group: String, artifact: String): String? =
        fetch("https://dl.google.com/android/maven2/${group.replace('.', '/')}/group-index.xml")
            ?.let { Regex("""<$artifact versions="([^"]*)"""").find(it)?.groupValues?.get(1) }
            ?.let { newest(it.split(',')) }

    private fun central(path: String): String? =
        fetch("https://repo.maven.apache.org/maven2/$path/maven-metadata.xml")
            ?.let { x -> newest(Regex("<version>([^<]+)</version>").findAll(x).map { it.groupValues[1] }.toList()) }

    private fun gradle(): String? = fetch("https://services.gradle.org/versions/current")
        ?.let { Regex(""""version"\s*:\s*"([0-9.]+)"""").find(it)?.groupValues?.get(1) }

    private fun sdk(): String? = fetch("https://dl.google.com/android/repository/repository2-3.xml")
        ?.let { x -> Regex("""platforms;android-(\d+)"""").findAll(x).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()?.toString() }

    /**
     * Looks up the newest stable versions online and uses them (skipped if checked recently, or for a while after a
     * fallback). Anything it can't look up keeps its current version. Never throws.
     */
    suspend fun refresh(log: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        val p = prefs() ?: return@withContext
        val now = System.currentTimeMillis()
        if (now - p.getLong("checked", 0) < CHECK_EVERY_MS) return@withContext
        if (now - p.getLong("fell_back", 0) < PAUSE_AFTER_FALLBACK_MS) return@withContext
        val found: Map<String, String?> = runCatching {
            coroutineScope {
                val jobs = mapOf(
                    "AGP" to async { google("com.android.tools.build", "gradle") },
                    "KOTLIN" to async { central("org/jetbrains/kotlin/kotlin-gradle-plugin") },
                    "COMPOSE_BOM" to async { google("androidx.compose", "compose-bom") },
                    "GRADLE" to async { gradle() },
                    "SDK" to async { sdk() },
                    "CORE" to async { google("androidx.core", "core-ktx") },
                    "ACTIVITY" to async { google("androidx.activity", "activity-compose") },
                    "LIFECYCLE" to async { google("androidx.lifecycle", "lifecycle-runtime-compose") },
                    "NAVIGATION" to async { google("androidx.navigation", "navigation-compose") },
                    "COROUTINES" to async { central("org/jetbrains/kotlinx/kotlinx-coroutines-core") },
                    "SERIALIZATION" to async { central("org/jetbrains/kotlinx/kotlinx-serialization-json") },
                    "KSP" to async { central("com/google/devtools/ksp/symbol-processing-gradle-plugin") },
                )
                jobs.values.awaitAll()
                jobs.mapValues { it.value.await() }
            }
        }.getOrDefault(emptyMap())
        val e = p.edit().putLong("checked", now)
        val changed = mutableListOf<String>()
        for ((name, v) in found) {
            if (v == null) continue
            val cur = get(name)
            // Only move forward, never to an older release.
            if (byVersion.compare(v, cur) > 0) {
                e.putString("cur_$name", v)
                changed += "$name $cur → $v"
            }
        }
        e.apply()
        if (changed.isNotEmpty()) log("Using the newest build tools: " + changed.joinToString(", "))
    }

    /** A build succeeded with the current versions: remember them as the last good set. */
    fun markGood() {
        val p = prefs() ?: return
        val e = p.edit()
        defaults.keys.forEach { e.putString("good_$it", get(it)) }
        e.apply()
    }

    /** Builds keep failing after an upgrade: go back to the last versions that built (or the built-in ones). */
    fun fallBack(): String? {
        val p = prefs() ?: return null
        val e = p.edit()
        val changed = mutableListOf<String>()
        defaults.forEach { (name, def) ->
            val good = p.getString("good_$name", null) ?: def
            if (good != get(name)) {
                changed += "$name ${get(name)} → $good"
                e.putString("cur_$name", good)
            }
        }
        e.putLong("fell_back", System.currentTimeMillis()).apply()
        return changed.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}
