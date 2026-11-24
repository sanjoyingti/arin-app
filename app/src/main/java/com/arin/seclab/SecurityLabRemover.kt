package com.arin.seclab

import android.content.Context

/** Takes installed tools back out of the Linux again (keeps wordlists). */
object SecurityLabRemover {
    suspend fun remove(context: Context, tool: SecurityTool, log: (String) -> Unit): Boolean {
        if (tool.name.endsWith("-downloader")) {
            log("${tool.name} only downloads files; they are kept in /root/wordlists.")
            return true
        }
        val pkg = tool.install.lastOrNull()?.substringAfterLast("--no-install-recommends ")?.trim()?.substringBefore(' ')
        if (pkg.isNullOrBlank()) {
            log("Don't know the package name of ${tool.name}; run apt-get remove by hand.")
            return false
        }
        log("Removing $pkg…")
        val r = LinuxShell.exec(context, "export DEBIAN_FRONTEND=noninteractive; apt-get remove -y -q $pkg 2>&1", 5 * 60_000L) { log(it) }
        return (r?.exitCode ?: 1) == 0
    }
}
