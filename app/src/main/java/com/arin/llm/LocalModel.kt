package com.arin.llm

import android.content.Context
import com.arin.terminal.Linux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Offline models: runs a GGUF model on this phone with llama.cpp's llama-server inside Arin's built-in
 * Linux, and exposes it as an OpenAI-format API on 127.0.0.1, so it plugs into Custom API like any service.
 * Nothing leaves the phone once the model is downloaded.
 */
object LocalModel {
    const val PORT = 8090
    const val BASE_URL = "http://127.0.0.1:$PORT/v1"
    /** The id llama-server answers to (--alias). */
    const val MODEL_ID = "local"
    /** llama-server needs no key; Custom API wants one, so a placeholder is stored. */
    const val DUMMY_KEY = "local"

    data class Preset(
        val name: String,
        /** Hugging Face repo holding GGUF files. */
        val repo: String,
        /** Rough download size, for the picker. */
        val size: String,
        val ram: String,
        val note: String,
    )

    /** Uncensored (abliterated) models small enough for a phone; each was checked to exist on Hugging Face. */
    val presets = listOf(
        Preset(
            "Gemma 4 E4B abliterated", "huihui-ai/Huihui-gemma-4-E4B-it-qat-q4_0-unquantized-abliterated-GGUF",
            "≈5 GB", "8 GB+ RAM", "Newest small Google model with refusals removed. Best quality that runs well on a phone.",
        ),
        Preset(
            "Qwen3 8B abliterated v2", "mradermacher/Huihui-Qwen3-8B-abliterated-v2-GGUF",
            "≈5 GB", "8 GB+ RAM", "Strong reasoning and coding, refusals removed. Slower than Gemma E4B.",
        ),
        Preset(
            "Gemma 4 E2B abliterated", "huihui-ai/Huihui-gemma-4-E2B-it-qat-q4_0-unquantized-abliterated-GGUF",
            "≈3 GB", "6 GB+ RAM", "Fastest and lightest. Pick this on phones with less memory.",
        ),
        Preset(
            "Gemma 4 12B abliterated", "huihui-ai/Huihui-gemma-4-12B-it-qat-q4_0-unquantized-abliterated-GGUF",
            "≈7–8 GB", "12 GB+ RAM", "The most capable one here; only for phones with lots of memory. Slow.",
        ),
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private fun arinDir(context: Context) = File(Linux.rootfs(context), "root/.arin").apply { mkdirs() }
    fun modelsDir(context: Context) = File(arinDir(context), "models").apply { mkdirs() }
    private fun serverDir(context: Context) = File(arinDir(context), "llama")
    private fun serverMarker(context: Context) = File(arinDir(context), "llama-ready")

    fun serverInstalled(context: Context) = serverMarker(context).exists()

    /** GGUF files already on the phone (finished downloads only). */
    fun downloaded(context: Context): List<File> =
        modelsDir(context).listFiles { f -> f.name.endsWith(".gguf") && !f.name.contains("mmproj", true) }.orEmpty()
            .sortedByDescending { it.lastModified() }

    /** Path of [file] as seen inside Linux. */
    fun linuxPath(context: Context, file: File): String = "/" + file.relativeTo(Linux.rootfs(context)).path

    // ------------------------------------------------------------------ install llama-server

    /**
     * Installs llama-server in Linux: the official prebuilt Ubuntu build from llama.cpp's latest GitHub
     * release, else (if it won't run here) a build from source. Safe to run again.
     */
    suspend fun installServer(context: Context, log: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        if (!Linux.installed(context)) return@withContext "Install Linux first (Settings → Linux)."
        val arch = if (android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm64") == true) "arm64" else "x64"
        log("Installing libraries llama.cpp needs…")
        Linux.run(context, "apt-get update -qq >/dev/null 2>&1; apt-get install -y -qq libgomp1 ca-certificates curl tar >/dev/null 2>&1; " +
            "apt-get install -y -qq libcurl4t64 libssl3t64 >/dev/null 2>&1 || apt-get install -y -qq libcurl4 libssl3 >/dev/null 2>&1; echo ok", timeoutMs = 15 * 60_000L)

        val prebuilt = runCatching {
            log("Looking up the latest llama.cpp release…")
            val rel = getJson("https://api.github.com/repos/ggml-org/llama.cpp/releases/latest") as JsonObject
            val tag = (rel["tag_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val asset = (rel["assets"] as JsonArray).map { it as JsonObject }.firstOrNull { a ->
                val n = (a["name"] as JsonPrimitive).content
                n.endsWith("bin-ubuntu-$arch.tar.gz") || n.endsWith("bin-ubuntu-$arch.zip")
            } ?: throw IllegalStateException("No Ubuntu $arch build in release $tag")
            val name = (asset["name"] as JsonPrimitive).content
            val url = (asset["browser_download_url"] as JsonPrimitive).content
            log("Downloading llama.cpp $tag ($name)…")
            val archive = File(arinDir(context), name)
            download(url, archive) { done, total -> log("Downloading llama.cpp: ${pct(done, total)}") }
            val dir = serverDir(context)
            val p = linuxPath(context, archive)
            val r = Linux.run(context,
                "rm -rf ~/.arin/llama && mkdir -p ~/.arin/llama && cd ~/.arin/llama && " +
                    (if (name.endsWith(".zip")) "(command -v unzip >/dev/null || apt-get install -y -qq unzip >/dev/null) && unzip -oq '$p'" else "tar -xzf '$p'") +
                    " && rm -f '$p' && S=$(find ~/.arin/llama -name llama-server -type f | head -1) && chmod +x \"\$S\" && " +
                    "D=$(dirname \"\$S\") && ln -sf \"\$S\" ~/.arin/llama-server && echo \"\$D\" > ~/.arin/llama-libdir && " +
                    "LD_LIBRARY_PATH=\"\$D\" \"\$S\" --version 2>&1 | head -3",
                timeoutMs = 10 * 60_000L)
            if (r.exitCode != 0 || !File(dir, ".").exists()) throw IllegalStateException(r.combined().takeLast(400))
            log(r.combined().trim().lines().lastOrNull().orEmpty())
            true
        }.getOrElse { e ->
            log("Prebuilt llama.cpp didn't work here (${e.message?.take(200)}). Building it on the phone instead — this takes 10–30 minutes…")
            false
        }
        if (!prebuilt) {
            val r = Linux.run(context,
                "apt-get install -y -qq build-essential cmake git >/dev/null 2>&1 && rm -rf ~/.arin/llama-src && " +
                    "git clone --depth 1 https://github.com/ggml-org/llama.cpp ~/.arin/llama-src && cd ~/.arin/llama-src && " +
                    "cmake -B build -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=ON >/dev/null && " +
                    "cmake --build build --target llama-server -j\$(nproc) 2>&1 | tail -3 && " +
                    "mkdir -p ~/.arin/llama && cp build/bin/llama-server ~/.arin/llama/ && ln -sf ~/.arin/llama/llama-server ~/.arin/llama-server && " +
                    "echo ~/.arin/llama > ~/.arin/llama-libdir && ~/.arin/llama-server --version 2>&1 | head -3",
                timeoutMs = 60 * 60_000L) { line -> if (line.contains('%') || line.contains("Linking")) log(line.trim().take(120)) }
            if (r.exitCode != 0) return@withContext "Couldn't install llama.cpp: " + (r.error ?: r.combined().takeLast(500))
        }
        serverMarker(context).writeText(System.currentTimeMillis().toString())
        log("llama.cpp server is installed.")
        null
    }

    // ------------------------------------------------------------------ download a model

    /**
     * Downloads the best phone-sized GGUF from [repo] (Q4_K_M, else another 4-bit file) into the models
     * folder, resuming a partial download. Returns the file, or throws with a readable message.
     */
    suspend fun downloadModel(context: Context, repo: String, onProgress: (String) -> Unit): File = withContext(Dispatchers.IO) {
        val clean = repo.trim().removePrefix("https://huggingface.co/").removeSuffix("/").substringBefore("/tree/")
        // A direct link to a .gguf file works too.
        if (clean.endsWith(".gguf")) {
            val url = if (repo.startsWith("http")) repo.replace("/blob/", "/resolve/") else "https://huggingface.co/$clean".replace("/blob/", "/resolve/")
            val out = File(modelsDir(context), url.substringAfterLast('/').substringBefore('?'))
            download(url, out) { d, t -> onProgress("Downloading ${out.name}: ${pct(d, t)}") }
            return@withContext out
        }
        onProgress("Finding the right file in $clean…")
        val files = (getJson("https://huggingface.co/api/models/$clean/tree/main") as JsonArray)
            .mapNotNull { (it as? JsonObject)?.let { o -> (o["path"] as? JsonPrimitive)?.contentOrNull to ((o["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L) } }
            .filter { (p, _) -> p != null && p.endsWith(".gguf", true) && !p.contains("mmproj", true) }
            .map { (p, s) -> p!! to s }
        if (files.isEmpty()) throw IllegalStateException("No GGUF files found in $clean. Paste a Hugging Face repo that has .gguf files.")
        val prefs = listOf("Q4_K_M", "Q4_0", "Q4_K_S", "IQ4_XS", "Q4_K_L", "Q5_K_M", "Q3_K_M")
        val pick = prefs.firstNotNullOfOrNull { q -> files.filter { it.first.contains(q, true) && !it.first.contains("-of-0") }.minByOrNull { it.second } }
            ?: files.filter { !it.first.contains("-of-0") }.minByOrNull { it.second }
            ?: throw IllegalStateException("Only split (multi-part) GGUF files in $clean; pick a smaller model.")
        val out = File(modelsDir(context), pick.first.substringAfterLast('/'))
        if (out.exists() && pick.second > 0 && out.length() == pick.second) return@withContext out
        download("https://huggingface.co/$clean/resolve/main/${pick.first}", out) { d, t ->
            onProgress("Downloading ${out.name}: ${pct(d, if (t > 0) t else pick.second)}")
        }
        out
    }

    // ------------------------------------------------------------------ run

    suspend fun isUp(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newBuilder().readTimeout(3, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("http://127.0.0.1:$PORT/health").build()).execute()
                .use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * Starts llama-server with [model] (restarting it if another model is loaded) and waits until it has
     * loaded the model. Returns a problem, or null when it's ready.
     */
    suspend fun start(context: Context, model: File, contextSize: Int = 8192, onStatus: (String) -> Unit = {}): String? {
        if (!serverInstalled(context)) return "The offline engine isn't installed yet. Open Settings → Offline models."
        if (!model.exists()) return "The model file is missing. Download it again in Settings → Offline models."
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8).let { if (it > 4) it - 2 else it }
        val path = linuxPath(context, model)
        onStatus("Loading ${model.nameWithoutExtension} on the phone…")
        Linux.run(context,
            "pkill -f 'llama-server' 2>/dev/null; sleep 1; D=$(cat ~/.arin/llama-libdir 2>/dev/null); " +
                "(nohup env LD_LIBRARY_PATH=\"\$D\" ~/.arin/llama-server -m '$path' --alias $MODEL_ID --host 127.0.0.1 --port $PORT " +
                "-c $contextSize -t $threads --jinja > ~/.arin/llama.log 2>&1 &); sleep 2; echo started",
            timeoutMs = 60_000L)
        // Loading a few GB from storage can take a while on a phone.
        for (i in 0 until 180) {
            if (isUp()) { File(arinDir(context), "llama-model").writeText(path); return null }
            if (i % 5 == 0) onStatus("Loading ${model.nameWithoutExtension} on the phone… ${i}s")
            delay(1_000)
        }
        val tail = runCatching { File(arinDir(context), "llama.log").readLines().takeLast(15).joinToString("\n") }.getOrDefault("")
        return "The offline model didn't start. Last log lines:\n$tail"
    }

    suspend fun stop(context: Context) {
        Linux.run(context, "pkill -f llama-server; echo stopped", timeoutMs = 30_000L)
    }

    /** The model llama-server was last started with (Linux path), if any. */
    fun lastModel(context: Context): File? = runCatching {
        File(Linux.rootfs(context), File(arinDir(context), "llama-model").readText().trim().removePrefix("/"))
    }.getOrNull()?.takeIf { it.exists() }

    fun isLocal(baseUrl: String) = baseUrl.startsWith("http://127.0.0.1:$PORT") || baseUrl.startsWith("http://localhost:$PORT")

    // ------------------------------------------------------------------ helpers

    private fun pct(done: Long, total: Long) =
        if (total > 0) "${done * 100 / total}% (${mb(done)} of ${mb(total)})" else mb(done)

    private fun mb(b: Long) = if (b >= 1L shl 30) "%.2f GB".format(b / 1073741824.0) else "${b / 1048576} MB"

    private fun getJson(url: String) = http.newCall(
        Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "Arin-Android").build(),
    ).execute().use { r ->
        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} from $url")
        json.parseToJsonElement(r.body!!.string())
    }

    /** Streams [url] into [out], resuming a partial file, reporting progress about twice a second. */
    private fun download(url: String, out: File, onProgress: (Long, Long) -> Unit) {
        val part = File(out.parentFile, out.name + ".part")
        var have = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(url).header("User-Agent", "Arin-Android")
            .apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newBuilder().readTimeout(5, TimeUnit.MINUTES).build().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Download failed: HTTP ${r.code}")
            if (r.code != 206) have = 0L   // server ignored the range: start over
            val total = (r.body!!.contentLength().takeIf { it > 0 } ?: 0L).let { if (it > 0) it + have else 0L }
            RandomAccessFile(part, "rw").use { f ->
                f.setLength(have); f.seek(have)
                val buf = ByteArray(1 shl 16)
                var last = 0L
                r.body!!.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        f.write(buf, 0, n); have += n
                        val now = System.currentTimeMillis()
                        if (now - last > 500) { last = now; onProgress(have, total) }
                    }
                }
            }
            onProgress(have, total)
        }
        if (out.exists()) out.delete()
        if (!part.renameTo(out)) throw IllegalStateException("Couldn't save ${out.name}")
    }
}
