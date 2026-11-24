package com.arin.builder

import android.content.Context
import com.arin.terminal.Linux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Rebuilds a decoded APK project (edited in the reverse-engineering chat) back into an installable APK, using the
 * pure-Java tools set up by [Linux.installAndroidTools]: APKEditor to rebuild (no arm64 aapt2 needed) and
 * uber-apk-signer to sign. Everything runs inside Arin's built-in Linux; the finished APK is copied out so
 * [ApkInstaller.install] can open Android's installer for it.
 *
 * Convention: projects decoded for editing live under /root/files/rea/<name>. The "Rebuild & install" button
 * rebuilds the most recently changed one; REA can also rebuild any folder on request.
 */
object ApkRebuild {

    /** Where decoded, editable projects live inside Linux. */
    const val PROJECTS_DIR = "/root/files/rea"

    data class Project(val name: String, val linuxPath: String, val modified: Long)

    /** Decoded projects under [PROJECTS_DIR], newest first. */
    fun projects(context: Context): List<Project> {
        val dir = File(Linux.rootfs(context), "root/files/rea")
        val subs = dir.listFiles { f -> f.isDirectory }?.toList().orEmpty()
        return subs
            .map { Project(it.name, "$PROJECTS_DIR/${it.name}", it.lastModified()) }
            .sortedByDescending { it.modified }
    }

    /** The most recently changed decoded project, or null if there is none yet. */
    fun newestProject(context: Context): Project? = projects(context).firstOrNull()

    // ---- Resume: a rebuild that was interrupted (app closed, stopped) can continue from the saved project. ----
    // Everything needed survives on disk: the decoded, edited project lives in Linux at the saved path, so
    // resuming re-runs only the rebuild+sign step — the slow decode and all edits are never lost or redone.

    @Serializable
    private data class RebuildState(val projectPath: String, val stage: String) // stage: "running" | "done"

    private val json = Json { ignoreUnknownKeys = true }
    private fun stateFile(context: Context) = File(context.filesDir, "rebuild-state.json")

    /** Marks a rebuild of [projectPath] as in progress, so it can be resumed if interrupted. */
    fun markRunning(context: Context, projectPath: String) {
        runCatching { stateFile(context).writeText(json.encodeToString(RebuildState(projectPath, "running"))) }
    }

    /** Clears the saved rebuild state (after a successful install). */
    fun clearState(context: Context) {
        runCatching { stateFile(context).delete() }
    }

    /**
     * If a rebuild was left in progress and its decoded project still exists on disk, returns that project's
     * path so the UI can offer "Resume rebuild"; otherwise null.
     */
    fun resumable(context: Context): String? {
        val f = stateFile(context)
        if (!f.exists()) return null
        val s = runCatching { json.decodeFromString<RebuildState>(f.readText()) }.getOrNull() ?: return null
        if (s.stage != "running") return null
        val dir = File(Linux.rootfs(context), s.projectPath.removePrefix("/"))
        return if (dir.exists()) s.projectPath else null
    }

    class RebuildError(message: String) : Exception(message)

    /**
     * Rebuilds and signs [projectLinuxPath] (a decoded-project folder, or an already-built APK), returning the
     * finished signed APK copied into the app's cache. Progress lines are reported through [log].
     */
    suspend fun rebuildAndSign(
        context: Context,
        projectLinuxPath: String,
        log: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        if (!Linux.installed(context)) throw RebuildError("The built-in Linux isn't installed yet.")
        val rebuildHelper = File(Linux.rootfs(context), "root/.arin/rebuild-sign.sh")
        if (!rebuildHelper.exists()) {
            throw RebuildError("The rebuild tools aren't installed yet. Install the Android tools in the reverse-engineering chat first.")
        }
        val outLinux = "/root/files/rebuilt-signed.apk"
        val r = Linux.run(
            context,
            "bash ${Linux.REBUILD_SIGN_SCRIPT} '${projectLinuxPath.replace("'", "'\\''")}' '$outLinux'",
            timeoutMs = 20 * 60_000L,
            onOutput = log,
        )
        if (r.exitCode != 0 || r.error != null) {
            throw RebuildError(r.error ?: r.stderr.trim().takeLast(400).ifBlank { "Rebuild failed." })
        }
        // The helper prints the finished APK's Linux path on its last stdout line.
        val printed = r.stdout.trim().lines().lastOrNull { it.isNotBlank() }?.trim()
            ?: throw RebuildError("Rebuild finished but produced no APK path.")
        val builtInLinux = File(Linux.rootfs(context), printed.removePrefix("/"))
        if (!builtInLinux.exists()) throw RebuildError("Couldn't find the rebuilt APK at $printed.")
        val outDir = File(context.cacheDir, "rebuilt-apks").apply { mkdirs() }
        val name = File(printed).name.ifBlank { "rebuilt-signed.apk" }
        val dest = File(outDir, name)
        builtInLinux.inputStream().use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 16) } }
        dest
    }
}
