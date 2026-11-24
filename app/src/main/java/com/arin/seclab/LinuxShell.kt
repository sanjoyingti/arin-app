package com.arin.seclab

import android.content.Context
import com.arin.terminal.Linux
import java.io.File

/** Thin wrapper over the built-in Linux: everything the Security Lab runs goes through here. */
object LinuxShell {

    /** Wraps [s] so the shell sees it as one literal word. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** True when the built-in Linux is installed. */
    fun available(context: Context): Boolean = runCatching { Linux.installed(context) }.getOrDefault(false)

    /** The Linux filesystem root on the phone (null when Linux isn't installed). */
    fun rootfs(context: Context): File? = runCatching { Linux.rootfs(context) }.getOrNull()

    /** A folder inside the Linux filesystem, created if needed. */
    fun dir(context: Context, linuxPath: String): File? {
        val root = rootfs(context) ?: return null
        return File(root, linuxPath.trimStart('/')).apply { mkdirs() }
    }

    /** Copies an app-side file into Linux and returns its Linux path, or null. */
    fun stage(context: Context, source: File, linuxPath: String): String? {
        val dest = File(rootfs(context) ?: return null, linuxPath.trimStart('/'))
        return runCatching {
            dest.parentFile?.mkdirs()
            source.copyTo(dest, overwrite = true)
            "/" + linuxPath.trimStart('/')
        }.getOrNull()
    }

    suspend fun exec(
        context: Context,
        command: String,
        timeoutMs: Long = 10 * 60_000L,
        onLine: (String) -> Unit = {},
    ): Linux.Result? = runCatching {
        Linux.run(context, "bash -c " + quote(command), timeoutMs = timeoutMs, onOutput = onLine)
    }.getOrNull()

    /** Runs [command] and returns its combined output. */
    suspend fun output(context: Context, command: String, timeoutMs: Long = 120_000L): String {
        val r = exec(context, command, timeoutMs) ?: return ""
        return (r.stdout + "\n" + r.stderr).trim()
    }

    /** Kills whatever still matches [pattern]. */
    suspend fun kill(context: Context, pattern: String) {
        exec(context, "pkill -f -- " + quote(pattern) + " ; true", 20_000L)
    }
}
