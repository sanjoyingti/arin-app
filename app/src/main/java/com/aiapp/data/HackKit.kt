package com.aiapp.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * Hack Suite engine. Installs the command-line reversing tools once (apktool, uber-apk-signer,
 * Objection + Frida tools via pip inside Arin's built-in Linux), analyses an APK (identity,
 * permissions, components, embedded secrets, security checklist) and holds the bundled Frida hook
 * scripts. Everything is idempotent: already-installed tools are skipped.
 */
object HackToolsShim {

    /** Logged lines shown in the Hack Suite screen. */
    @Volatile
    var log: (String) -> Unit = {}

    private fun toolsDir(ctx: Context) = File(ctx.filesDir, "hacktools").apply { mkdirs() }
    fun apktoolJar(ctx: Context) = File(toolsDir(ctx), "apktool.jar")
    fun signerJar(ctx: Context) = File(toolsDir(ctx), "uber-apk-signer.jar")
    private fun workDir(ctx: Context) = File(ctx.cacheDir, "hack").apply { mkdirs() }

    private fun run(cmd: List<String>, timeoutSec: Long = 300): Pair<Int, String> {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = p.inputStream.bufferedReader()
            val deadline = System.currentTimeMillis() + timeoutSec * 1000
            val buf = CharArray(2048)
            while (true) {
                if (!reader.ready()) {
                    if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) break
                    if (System.currentTimeMillis() > deadline) { p.destroyForcibly(); break }
                    continue
                }
                val n = reader.read(buf)
                if (n < 0) break
                out.appendRange(buf, 0, n)
            }
            reader.close()
            p.waitFor().also { out.append(reader.readText()) } to out.toString()
        } catch (e: Exception) {
            -1 to e.message.orEmpty()
        }
    }

    private fun fetch(url: String, out: File): Boolean = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        if (conn.responseCode !in 200..299) return false
        conn.inputStream.use { i -> out.outputStream().use { i.copyTo(it, 1 shl 16) } }
        out.length() > 1000
    }.getOrDefault(false)

    private fun latestGithubJar(repo: String): String? = runCatching {
        val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.jar)\"").findAll(body)
            .map { it.groupValues[1] }.firstOrNull { it.contains("uber-apk-signer") || !it.contains("sources") }
    }.getOrNull()

    private fun latestApktoolUrl(): String = runCatching {
        val conn = URL("https://bitbucket.org/iBotPeaches/apktool/downloads/?tab=tags").openConnection() as HttpURLConnection
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        Regex("href=\"(/iBotPeaches/apktool/downloads/[^\"]+\\.jar)\"").findAll(body)
            .map { "https://bitbucket.org" + it.groupValues[1] }.firstOrNull()
    }.getOrNull() ?: "https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_2.9.3.jar"

    /**
     * Installs everything that isn't installed yet, verifying each tool with --version afterwards.
     * Safe to call repeatedly.
     */
    fun ensureTools(ctx: Context) {
        log("Ensuring hack tools are installed…")
        val apktool = apktoolJar(ctx)
        if (apktool.exists() && apktool.length() > 100_000) {
            log("✔ apktool.jar already present")
        } else {
            log("Downloading latest apktool from Bitbucket…")
            if (fetch(latestApktoolUrl(), apktool) && run(listOf("java", "-jar", apktool.absolutePath, "--version")).first == 0) {
                log("✔ apktool ${apktool.length() / 1024} KB installed")
            } else { apktool.delete(); log("✖ apktool download/install failed") }
        }

        val signer = signerJar(ctx)
        if (signer.exists() && signer.length() > 100_000) {
            log("✔ uber-apk-signer already present")
        } else {
            log("Downloading uber-apk-signer from GitHub…")
            val url = latestGithubJar("patrickfav/uber-apk-signer") ?: "https://github.com/patrickfav/uber-apk-signer/releases/latest"
            if (fetch(url, signer) && run(listOf("java", "-jar", signer.absolutePath, "--version")).first == 0) {
                log("✔ uber-apk-signer installed")
            } else { signer.delete(); log("✖ uber-apk-signer download/install failed") }
        }

        log("Installing Objection + Frida tools via pip (this can take a few minutes)…")
        val (code, out) = run(listOf("bash", "-c", "pip install objection frida-tools"), timeoutSec = 900)
        if (code == 0 || out.contains("already satisfied")) {
            val fv = run(listOf("frida", "--version")).second.trim().lineSequence().firstOrNull()
            log("✔ Objection + Frida installed${fv?.let { " (Frida $it)" }.orEmpty()}")
        } else {
            log("✖ pip install failed: ${out.lineSequence().lastOrNull()?.take(200).orEmpty()}")
        }
        log("Hack tools ready.")
    }

    /** True when aapt2 is on PATH (it comes with the Android build tools). */
    private fun aapt2Available(): Boolean = run(listOf("aapt2", "--version"), timeoutSec = 15).first == 0

    /**
     * Full recon of [apkPath]. Keys: package, app_name, version, permissions, activities, services,
     * secrets, vulnerabilities. Values are human-readable multi-line text.
     */
    fun analyze(apkPath: String): Map<String, String> {
        val results = LinkedHashMap<String, String>()
        val apk = File(apkPath)
        if (!apk.exists()) { results["error"] = "APK not found: $apkPath"; return results }

        // --- Identity + permissions via aapt2 dump badging ---
        val badging = run(listOf("aapt2", "dump", "badging", apk.absolutePath)).second
        if (badging.isNotBlank() && !aapt2Available().let { false } || badging.contains("package:")) {
            Regex("""package:\s+name='([^']+)'""").find(badging)?.let { results["package"] = it.groupValues[1] }
            Regex("""application-label:'([^']*)'""").find(badging)?.let { results["app_name"] = it.groupValues[1] }
            Regex("""versionName='([^']*)'""").find(badging)?.let { results["version"] = it.groupValues[1] }
            results["permissions"] = """uses-permission:[^\s]+=?\s*'?([^'\s]+)'?""".toRegex()
                .findAll(badging).map { it.groupValues[1] }.distinct().joinToString("\n")
            results["activities"] = """launchable-activity:[^\s]*name='([^']+)'""".toRegex()
                .findAll(badging).map { it.groupValues[1] }.distinct().joinToString("\n")
        }

        // --- Decode for the deeper scans (manifest attrs, embedded secrets) ---
        val outDir = File(workDir(System.getProperty("user.dir") ?: "."), "decoded_" + apk.nameWithoutExtension)
        outDir.deleteRecursively()
        val manifestTxt: String?
        if (apktoolJar(File("/data/data/com.arin.app/files")).exists() || run(listOf("which", "apktool")).first == 0) {
            val dec = run(listOf("bash", "-c", "apktool d -f -o '${outDir.absolutePath}' '${apk.absolutePath}' >/dev/null 2>&1"))
            manifestTxt = File(outDir, "AndroidManifest.xml").takeIf { it.exists() }?.readText()
        } else {
            manifestTxt = null
        }

        if (manifestTxt != null) {
            if (!results.containsKey("package")) {
                Regex("""package="([^"]+)"""").find(manifestTxt)?.let { results["package"] = it.groupValues[1] }
            }
            Regex("""<activity\b[^>]*android:name="([^"]+)"""").findAll(manifestTxt)
                .map { it.groupValues[1] }.distinct().joinToString("\n")
                .takeIf { it.isNotBlank() }?.let { results["activities"] = it }
            Regex("""<service\b[^>]*android:name="([^"]+)"""").findAll(manifestTxt)
                .map { it.groupValues[1] }.distinct().joinToString("\n")
                .takeIf { it.isNotBlank() }?.let { results["services"] = it }
            results["vulnerabilities"] = vulnChecklist(manifestTxt)
            results["secrets"] = secretScan(outDir)
            log("✔ Analysis complete")
        } else if (badging.isNotBlank()) {
            results["note"] = "Deep scan unavailable (apktool failed); identity and permissions above come from aapt2."
        } else {
            results["error"] = "Couldn't analyse the APK (aapt2 and apktool both unavailable)."
        }
        return results
    }

    private fun vulnChecklist(manifest: String): String {
        val findings = mutableListOf<String>()
        if (Regex("""android:debuggable="true"""").containsMatchIn(manifest)) findings += "• DEBUGGABLE build: on by default, exposes internals to any USB debugger."
        if (!Regex("""android:allowBackup="false"""").containsMatchIn(manifest)) findings += "• allowBackup is ON (default): private data is copied by adb backup/cloud backups."
        if (!Regex("""android:usesCleartextTraffic="false"""").containsMatchIn(manifest)) findings += "• Cleartext HTTP traffic not explicitly disabled: network calls may be intercepted."
        Regex("""<(activity|service|receiver|provider)\b[^>]*>\s*""", Pattern.DOTALL)
        val exported = Regex("""<(activity|service|receiver|provider)\b[^>]*android:exported="true"[^>]*>""", Pattern.DOTALL)
            .findAll(manifest).count { true }
        if (exported > 0) findings += "• $exported exported component(s): reachable by other apps — check intent filters."
        if (Regex("""<activity\b[^>]*javascript="true"""").containsMatchIn(manifest) || manifest.contains("JavascriptInterface")) {
            findings += "• WebView JavaScript bridge/interface detected: check addJavascriptInterface exposure."
        }
        if (findings.isEmpty()) findings += "None — clean configuration checklist."
        return findings.joinToString("\n")
    }

    private val SECRET_PATTERNS = listOf(
        "Google API key" to Regex("""AIza[0-9A-Za-z\-_]{35}"""),
        "Firebase database URL" to Regex("""https://[a-zA-Z0-9\-]+\.firebaseio\.com"""),
        "Firebase config" to Regex("""1://[0-9]{6,}:[a-z0-9]+:[a-zA-Z0-9\-_]+"""),
        "JWT token" to Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"""),
        "Bearer/API token" to Regex("""(?i)(token|secret|apikey|api_key|access_key)["']?\s*[:=]\s*["'][A-Za-z0-9\-_.]{16,}["']"""),
        "AWS key" to Regex("""AKIA[0-9A-Z]{16}"""),
        "Private key block" to Regex("""-----BEGIN PRIVATE KEY-----"""),
    )

    private fun secretScan(decodedDir: File): String {
        val found = LinkedHashMap<String, MutableSet<String>>()
        decodedDir.walkTopDown()
            .filter { it.isFile && (it.parentFile?.name == "strings" || it.parentFile?.parentFile?.name == "values" || it.extension !in setOf("arsc", "dex")) }
            .take(4000).forEach { f ->
                val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
                for ((label, rx) in SECRET_PATTERNS) {
                    rx.findAll(text).forEach { m -> found.getOrPut(label) { LinkedHashSet() }.add(m.value.take(80)) }
                }
            }
        if (found.isEmpty()) return "No embedded secrets matched the known patterns."
        return found.entries.joinToString("\n\n") { (label, vals) ->
            "$label:\n" + vals.take(8).joinToString("\n") { "  • $it" } + if (vals.size > 8) "\n  … (+${vals.size - 8} more)" else ""
        }
    }

    /** Bundled Frida hook scripts, keyed by friendly name. */
    val FRIDA_SCRIPTS: Map<String, String> = mapOf(
        "ssl_pin_bypass.js" to """/*
 * Standard Frida SSL-pinning bypass: replaces the default X509TrustManager so any
 * certificate chain is accepted, and neutralizes common Pinning TrustManager hooks.
 */
Java.perform(function () {
    var X509TrustManager = Java.use('javax.net.ssl.X509TrustManager');
    var HostnameVerifier = Java.use('javax.net.ssl.HostnameVerifier');

    var TrustManager = Java.registerClass({
        name: 'com.hook.NSTrustManager',
        implements: [X509TrustManager],
        methods: {
            checkClientTrusted: function (chain, authType) { },
            checkServerTrusted: function (chain, authType) { },
            getAcceptedIssuers: function () { return []; }
        }
    });

    var Context = Java.use('android.app.ActivityThread');
    Context.currentApplication().getApplicationContext();
    var SSLContext = Java.use('javax.net.ssl.SSLContext');
    var init = SSLContext.init.overload('[Ljavax.net.ssl.KeyManager;', '[Ljavax.net.ssl.TrustManager;', 'java.security.SecureRandom');
    init.implementation = function (km, tm, sr) {
        console.log('[+] SSLContext.init patched — trust manager replaced');
        init.call(this, km, [TrustManager.$new()], sr);
    };

    var HP = Java.use('okhttp3.OkHttpClient$Builder').hostnameVerifier;
    if (HP) {
        HP.implementation = function (hv) {
            console.log('[+] OkHttp hostname verifier patched');
            return HVStub.$new();
        };
    }
});
""",
        "root_bypass.js" to """/*
 * Root-detection bypass: hides su/busybox binaries, strips the standard root apps from
 * getPackages and makes Runtime.exec / File.exists claims about root paths harmless.
 */
Java.perform(function () {
    var F = Java.use('java.io.File');
    var roots = ['/system/bin/su', '/system/xbin/su', '/sbin/su', '/system/sd/xbin/su',
                 '/vendor/bin/su', '/su/bin/su', '/data/local/xbin/su', '/data/local/bin/su',
                 '/system/bin/busybox', '/system/xbin/busybox'];
    F.exists.implementation = function () {
        var p = this.getAbsolutePath();
        for (var i = 0; i < roots.length; i++) if (p === roots[i]) return false;
        return this.exists();
    };

    var R = Java.use('java.lang.Runtime');
    R.exec.overload('java.lang.String').implementation = function (cmd) {
        if (cmd.indexOf('su') === 0 || cmd.indexOf('which') !== -1) {
            console.log('[+] hid exec: ' + cmd);
            return R.exec.call(this, 'echo ');
        }
        return R.exec.call(this, cmd);
    };

    try {
        var PM = Java.use('android.app.ApplicationPackageManager');
        PM.getPackageInfo.overload('java.lang.String', 'int').implementation = function (name, flags) {
            if (['com.topjohnwu.magisk', 'eu.chainfire.supersu', 'com.noshufou.android.su',
                 'com.koushikdutta.superuser'].indexOf(name) !== -1) {
                throw Java.use('android.content.pm.PackageManager$NameNotFoundException').$new(name);
            }
            return this.getPackageInfo(name, flags);
        };
    } catch (e) { console.log('[-] package hook skipped: ' + e); }

    try {
        var BS = Java.use('android.os.Build');
        var TAGS = Java.use('java.lang.String').$new(BS.TAGS.value);
        if (TAGS.toString().indexOf('test-keys') !== -1) {
            BS.TAGS.value = Java.use('java.lang.String').$new(TAGS.toString().replace('test-keys', 'release-keys'));
            console.log('[+] Build.TAGS patched (test-keys hidden)');
        }
    } catch (e) { }
    console.log('[+] Root detection bypass armed');
});
""",
        "license_bypass.js" to """/*
 * License-check bypass: stubs the common LicenseChecker.isLic... pattern to always return
 * true, plus a generic "is*" boolean patch for obfuscated packages when configured.
 */
Java.perform(function () {
    // Known public licensing APIs first.
    try {
        var LC = Java.use('com.google.android.vending.licensing.LicenseChecker');
        LC.checkAccess.implementation = function (cb) {
            console.log('[+] Google Licensing checkAccess stubbed');
            try {
                var Policy = cb.getClass().getMethod('allow').invoke(cb);
            } catch (e) { }
            return;
        };
    } catch (e) { }

    // Generic probe: every class whose name contains LicenseChecker/isLicensed-style members.
    Java.enumerateLoadedClasses({
        onMatch: function (name) {
            if (name.indexOf('License') === -1 && name.toLowerCase().indexOf('licence') === -1) return;
            try {
                var cls = Java.use(name);
                var methods = cls.class.getDeclaredMethods();
                for (var i = 0; i < methods.length; i++) {
                    var m = methods[i];
                    var mn = m.getName();
                    var rt = m.getReturnType().getName();
                    if (/licensed|valid|activated|premium|registered|ok$/i.test(mn) && rt === 'boolean') {
                        var ov = cls[mn];
                        for (var j = 0; j < ov.overloads.length; j++) {
                            ov.overloads[j].implementation = function () {
                                console.log('[+] ' + mn + ' forced to TRUE in ' + name);
                                return true;
                            };
                        }
                    }
                }
            } catch (err) { }
        },
        onComplete: function () { console.log('[+] License stubbing complete'); }
    });
});
""",
        "mem_dump.js" to """/*
 * Memory dump: walks the heaps of the target process and prints printable ASCII strings
 * of length >= 6 that appear in readable allocations (tokens, urls, keys, traces...).
 */
console.log('[+] Starting heap string scan…');
function printable(bytes, minLen) {
    var s = '', ch;
    for (var i = 0; i < bytes.length && s.length < 400; i++) {
        ch = bytes[i];
        if (ch >= 32 && ch < 127) s += String.fromCharCode(ch);
        else if (s.length >= minLen) break;
        else s = '';
    }
    return s.length >= minLen ? s : '';
}
Java.perform(function () {
    var seen = {};
    Java.choose('java.lang.String', {
        onMatch: function (inst) {
            var v = inst.toString();
            if (v.length < 6 || v.length > 512) return;
            if (seen[v]) return; seen[v] = true;
            console.log('[str] ' + v);
        },
        onComplete: function () { console.log('[+] Java heap string scan done'); }
    });
    console.log('[+] Native heap walk:');
    Process.enumerateRanges({ protection: 'r--', coalesce: false }).slice(0, 200).forEach(function (range) {
        try {
            var d = Memory.readByteArray(range.base, Math.min(range.size, 65536));
            var u8 = new Uint8Array(d);
            var s = printable(u8, 6);
            if (s) console.log('[mem @' + range.base + '] ' + s.substring(0, 400));
        } catch (e) { }
    });
});
""",
    )
}
