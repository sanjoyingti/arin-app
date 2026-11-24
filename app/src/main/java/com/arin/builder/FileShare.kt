package com.arin.builder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Puts a file on a free file-hosting site and returns a link that downloads it on any phone. It tries several hosts in
 * turn and keeps the first link that really downloads (checked with a request after uploading). When every host fails,
 * the error lists what was tried, so the model can search the internet for other free hosts and upload there instead.
 */
object FileShare {
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES).followRedirects(true).build()
    }
    private val octet = "application/octet-stream".toMediaType()

    class Shared(val file: File, val url: String, val host: String)

    private fun multipart(field: String, file: File, extra: Map<String, String> = emptyMap()) =
        MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            extra.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart(field, file.name, file.asRequestBody(octet))
        }.build()

    private fun post(url: String, body: okhttp3.RequestBody): String =
        http.newCall(Request.Builder().url(url).header("User-Agent", "Arin").post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code} ${text.take(120)}")
            text
        }

    /** Each host: a name and how to upload to it (returns the download link). */
    private val hosts: List<Pair<String, (File) -> String>> = listOf(
        "gofile.io" to { f ->
            val j = JSONObject(post("https://upload.gofile.io/uploadfile", multipart("file", f)))
            require(j.optString("status") == "ok") { j.toString().take(120) }
            j.getJSONObject("data").getString("downloadPage")
        },
        "catbox.moe" to { f ->
            post("https://catbox.moe/user/api.php", multipart("fileToUpload", f, mapOf("reqtype" to "fileupload"))).trim()
                .also { require(it.startsWith("https://")) { it.take(120) } }
        },
        "tmpfiles.org" to { f ->
            val j = JSONObject(post("https://tmpfiles.org/api/v1/upload", multipart("file", f)))
            j.getJSONObject("data").getString("url").replace("tmpfiles.org/", "tmpfiles.org/dl/").replace("http://", "https://")
        },
        "litterbox.catbox.moe (72 h)" to { f ->
            post("https://litterbox.catbox.moe/resources/internals/api.php", multipart("fileToUpload", f, mapOf("reqtype" to "fileupload", "time" to "72h"))).trim()
                .also { require(it.startsWith("https://")) { it.take(120) } }
        },
        "0x0.st" to { f ->
            post("https://0x0.st", multipart("file", f)).trim().also { require(it.startsWith("http")) { it.take(120) } }
        },
    )

    /** True when [url] answers like a real download (or a download page). */
    private fun works(url: String): Boolean = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Arin").header("Range", "bytes=0-0").get().build())
            .execute().use { it.isSuccessful || it.code == 206 }
    }.getOrDefault(false)

    /**
     * Hosts Arin found by itself: when every known host failed, the model searched the internet, found a working free
     * host and saved a line "name|shell command" here; {FILE} in the command is replaced by the file's path and the
     * command prints the download link. These are tried first next time (inside Arin's Linux).
     */
    const val LEARNED_HOSTS = "/root/.arin/share-hosts.txt"

    private suspend fun learned(context: android.content.Context, file: File, log: (String) -> Unit, errors: MutableList<String>): Shared? {
        val list = File(com.arin.terminal.Linux.rootfs(context), LEARNED_HOSTS.removePrefix("/"))
        if (!com.arin.terminal.Linux.installed(context) || !list.exists()) return null
        // The file must be inside Linux for the command to read it.
        val shareDir = File(com.arin.terminal.Linux.rootfs(context), "root/files/share").apply { mkdirs() }
        val inLinux = File(shareDir, file.name)
        if (file.canonicalPath != inLinux.canonicalPath) file.inputStream().use { i -> inLinux.outputStream().use { i.copyTo(it, 1 shl 16) } }
        for (line in list.readLines().map { it.trim() }.filter { it.contains('|') && !it.startsWith("#") }.reversed()) {
            val name = line.substringBefore('|').trim()
            val cmd = line.substringAfter('|').replace("{FILE}", "'/root/files/share/${inLinux.name.replace("'", "")}'")
            log("Uploading ${file.name} to $name…")
            val r = com.arin.terminal.Linux.run(context, cmd, timeoutMs = 20 * 60_000L)
            val url = Regex("""https?://\S+""").findAll(r.stdout).lastOrNull()?.value?.trim('"', '\'', ',', ')')
            if (url != null && works(url)) return Shared(file, url, name)
            errors += "$name (found earlier): ${if (url == null) "no link" else "the link didn't download"}"
        }
        return null
    }

    /** Uploads [file] to the first free host that works; throws with every host's error when none does. */
    suspend fun upload(file: File, context: android.content.Context? = null, log: (String) -> Unit = {}): Shared = withContext(Dispatchers.IO) {
        require(file.exists() && file.length() > 0) { "${file.name} doesn't exist or is empty" }
        val errors = mutableListOf<String>()
        if (context != null) runCatching { learned(context, file, log, errors) }.getOrNull()?.let { return@withContext it }
        for ((name, send) in hosts) {
            log("Uploading ${file.name} to $name…")
            val url = runCatching { send(file) }.getOrElse { errors += "$name: ${it.message?.take(100)}"; null } ?: continue
            if (works(url)) return@withContext Shared(file, url, name)
            errors += "$name: the link didn't download"
        }
        error("Every free host failed for ${file.name}: " + errors.joinToString("; "))
    }
}
