package com.arin.seclab

import android.content.Context

/**
 * Installs tools in the built-in Linux, verifies them and repairs broken ones. Every command runs in PRoot, so no
 * root and no Termux are needed. Each step writes its output into the install log.
 */
class SecurityLabInstaller(
    private val context: Context,
    private val store: SecurityStore,
    private val log: (String) -> Unit,
) {
    private var prepared = false

    /** Result of checking a tool. */
    data class Verify(val installed: Boolean, val version: String)

    /** First-time setup: folders and a fresh package list. Runs once per session. */
    suspend fun prepare() {
        if (prepared) return
        log("Preparing the built-in Linux (folders, package list)…")
        LinuxShell.exec(
            context,
            "export DEBIAN_FRONTEND=noninteractive; mkdir -p /root/wordlists /root/captures /root/loot /root/reports /root/apk /root/frida; apt-get update -q 2>&1 | tail -3",
            10 * 60_000L,
        ) { log(it) }
        prepared = true
    }

    /** True when [tool] answers its check command. */
    suspend fun verify(tool: SecurityTool): Verify {
        if (tool.verify.isBlank()) return Verify(false, "")
        if (!LinuxShell.available(context)) return Verify(false, "")
        val out = LinuxShell.output(
            context,
            "timeout 25 bash -c " + LinuxShell.quote(tool.verify) + " 2>&1 | head -3",
            60_000L,
        )
        val broken = out.isBlank() || out.contains("not found", true) || out.contains("No such file", true) ||
            out.contains("command not found", true)
        val version = out.lineSequence().firstOrNull { it.isNotBlank() }?.take(80).orEmpty()
        val ok = !broken
        store.setTool(tool.name, if (ok) version else "", if (ok) "installed" else "missing")
        return Verify(ok, version)
    }

    /** Installs [tool] and checks it. Returns true when it now works. */
    suspend fun install(tool: SecurityTool): Boolean {
        if (!LinuxShell.available(context)) {
            log("The built-in Linux isn't installed. Open Settings and install it first.")
            return false
        }
        prepare()
        log("Installing ${tool.name} (${tool.size}, ${tool.category})…")
        if (tool.install.isEmpty()) {
            log("No install command for ${tool.name}.")
            return false
        }
        for (command in tool.install) {
            log("$ ${command.take(160)}")
            val r = LinuxShell.exec(context, "export DEBIAN_FRONTEND=noninteractive; $command 2>&1", 25 * 60_000L) { log(it) }
            if (r == null) {
                log("! ${tool.name}: the command didn't run")
                store.setTool(tool.name, "", "missing")
                return false
            }
            if (r.exitCode != 0) log("! ${tool.name}: the last command exited with ${r.exitCode} — checking anyway")
        }
        val v = verify(tool)
        if (v.installed) {
            log("✓ ${tool.name} works: ${v.version.ifBlank { "ready" }}")
        } else {
            log("✗ ${tool.name} was installed but its check still fails. Try Repair, or look at the log above.")
        }
        return v.installed
    }

    /** Reinstalls [tool] when its check fails; true when it works afterwards. */
    suspend fun repair(tool: SecurityTool): Boolean {
        log("Checking ${tool.name}…")
        if (verify(tool).installed) {
            log("${tool.name} is fine.")
            return true
        }
        log("${tool.name} is broken or missing: reinstalling.")
        store.clearTool(tool.name)
        return install(tool)
    }
}
