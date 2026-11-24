package com.aiapp.data

import android.content.Context
import com.arin.terminal.Linux
import com.arin.terminal.Termux
import java.io.File
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking

/**
 * Tools and analysis for the Hack Suite. Jar tools live in the app's storage (and are mirrored
 * into the built-in Linux), Python tools are pip-installed inside the built-in Linux (PRoot), and
 * the chosen APK is analysed with aapt2 plus a plain-text scan. Everything is idempotent.
 */
object HackToolsShim {

    private fun toolsDir(ctx: Context) = File(ctx.filesDir, "hacktools").apply { mkdirs() }
    fun apktoolJar(ctx: Context) = File(toolsDir(ctx), "apktool.jar")
    fun signerJar(ctx: Context) = File(toolsDir(ctx), "uber-apk-signer.jar")

    private const val APKTOOL_URL = "https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_2.10.0.jar"
    private const val SIGNER_URL = "https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar"

    /** Fetches [url] into [dest]; true when a non-trivial file landed. */
    private fun download(url: String, dest: File): Boolean = runCatching {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 16) } }
        dest.length() > 10_000
    }.getOrDefault(false)

    /** The mirror of the tools inside the Linux filesystem, so java/pip inside Linux can use them. */
    private fun exposedDir(ctx: Context) = File(File(Linux.rootfs(ctx), "root"), ".arin-hacktools").apply { mkdirs() }

    /** Copies the APK into the Linux filesystem at a fixed path; returns that path or null. */
    private fun stageApk(ctx: Context, apkPath: String): String? {
        val apk = File(apkPath)
        if (!apk.exists()) return null
        return runCatching {
            val dest = File(File(Linux.rootfs(ctx), "root/files"), "hack-target.apk")
            dest.parentFile?.mkdirs()
            apk.copyTo(dest, overwrite = true)
            "/root/files/hack-target.apk"
        }.getOrNull()
    }

    /**
     * Runs [cmd] inside the built-in Linux and returns the result (or null on failure). The terminal
     * helper suspends, so the call is wrapped here and the caller can stay a plain function.
     */
    private fun runLinux(ctx: Context, cmd: String, timeoutMs: Long, log: ((String) -> Unit)? = null): Termux.Result? =
        runBlocking {
            runCatching { Linux.run(ctx, cmd, timeoutMs = timeoutMs, onOutput = { line -> log?.invoke(line) }) }.getOrNull()
        }

    /** Verifies a jar runs by passing it --version inside Linux; returns the printed version or null. */
    private fun jarVersion(ctx: Context, jar: File): String? {
        val exposed = File(exposedDir(ctx), jar.name)
        runCatching { jar.copyTo(exposed, overwrite = true) }
        if (!exposed.exists()) return null
        val r = runLinux(ctx, "java -jar /root/.arin-hacktools/${jar.name} --version", timeoutMs = 120_000L)
        return r?.stdout?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }
    }

    /**
     * Ensures apktool.jar, uber-apk-signer.jar (downloaded) and objection + frida-tools (pip inside
     * the built-in Linux) are present, verifying each with --version. Safe to call repeatedly.
     */
    fun ensureTools(ctx: Context, log: (String) -> Unit) {
        exposedDir(ctx)

        if (!apktoolJar(ctx).exists()) {
            log("Downloading apktool (Bitbucket, latest release)…")
            val ok = download(APKTOOL_URL, apktoolJar(ctx))
            log(if (ok) "apktool downloaded." else "apktool download failed — decompilation will be limited.")
        } else log("apktool: already installed.")
        jarVersion(ctx, apktoolJar(ctx))?.let { log("apktool --version → $it") }

        if (!signerJar(ctx).exists()) {
            log("Downloading uber-apk-signer (GitHub patrickfav release)…")
            val ok = download(SIGNER_URL, signerJar(ctx))
            log(if (ok) "uber-apk-signer downloaded." else "uber-apk-signer download failed — signing unavailable.")
        } else log("uber-apk-signer: already installed.")
        jarVersion(ctx, signerJar(ctx))?.let { log("uber-apk-signer --version → $it") }

        if (Linux.installed(ctx)) {
            log("Installing Python tools in the built-in Linux: pip install objection frida-tools …")
            runLinux(ctx, """bash -c "pip install objection frida-tools"""", timeoutMs = 10 * 60_000L, log = log)
            val check = runLinux(ctx, """bash -c "objection version 2>/dev/null | head -1; frida --version 2>/dev/null"""", timeoutMs = 120_000L)
            val verified = check?.stdout?.isNotBlank() == true
            log(
                if (verified && check != null) "Verified: ${check.stdout.trim().replace("\n", " · ")}"
                else "Python tools install attempted (they'll be verified the first time a hook runs).",
            )
        } else {
            log("Built-in Linux isn't installed — objection/frida-tools skipped.")
        }
    }

    /**
     * Analyses the APK at [apkPath]. Sections: Identity, Permissions, Activities, Services,
     * Embedded secrets, Vulnerability checklist.
     */
    fun analyze(ctx: Context, apkPath: String, log: (String) -> Unit): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val linuxApk = stageApk(ctx, apkPath)
        val badging = linuxApk?.let {
            runLinux(ctx, "aapt2 dump badging $it", timeoutMs = 3 * 60_000L)?.stdout.orEmpty()
        }.orEmpty()
        if (badging.isBlank()) log("aapt2 gave no output (is the built-in Linux with Android tools installed?).")

        val pkg = Regex("""package:\s*name='([^']*)'[^']*versionCode='([^']*)'\s*versionName='([^']*)'""").find(badging)
        val appName = Regex("""application-label:'([^']*)'""").find(badging)?.groupValues?.get(1)
	out["Identity"] = buildString {
            appendLine("Package: ${pkg?.groupValues?.get(1) ?: "?"}")
            appendLine("App name: ${appName ?: "?"}")
            appendLine("Version: ${pkg?.groupValues?.get(3) ?: "?"} (code ${pkg?.groupValues?.get(2) ?: "?"})")
        }.trim()

        out["Permissions"] = Regex("""uses-permission:'([^']*)'""").findAll(badging)
            .map { it.groupValues[1] }.distinct().joinToString("\n").ifBlank { "(none declared)" }

        val manifestRaw = manifestRawStrings(apkPath)
        out["Activities"] = (
            Regex("""launchable-activity:\s*name='([^']*)'""").findAll(badging).map { it.groupValues[1] } +
                componentNames(manifestRaw, "activity")
            ).distinct().joinToString("\n").ifBlank { "(none found)" }
        out["Services"] = componentNames(manifestRaw, "service").ifBlank { "(none found — decompile with apktool for details)" }

        out["Embedded secrets"] = scanSecrets(apkPath, log)
        out["Vulnerability checklist"] = vulnChecklist(badging, manifestRaw)
        return out
    }

    /** Pulls the compressed XML string pool of AndroidManifest.xml as a latin-1 soup of names. */
    private fun manifestRawStrings(apkPath: String): String = runCatching {
        val z = java.util.zip.ZipFile(File(apkPath))
        val entry = z.getEntry("AndroidManifest.xml") ?: return ""
        z.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
    }.getOrDefault("")

    /** Finds name='…' attributes belonging to components of [kind] in the raw manifest soup. */
    private fun componentNames(manifestRaw: String, kind: String): String =
        Regex("""<(?:$kind)[^>]*?(?:android[\s\x00-\x20]*)?name=['\x00][^'\x00]*['\x00]""").findAll(manifestRaw)
            .map { it.value.substringAfter('=').trim('\'', '\u0000', ' ', '"') }
            .filter { it.isNotBlank() && it.contains('.') }
            .distinct().joinToString("\n")

    /** Scans XML/text entries of the APK for secret-looking strings. */
    private fun scanSecrets(apkPath: String, log: (String) -> Unit): String {
        val findings = linkedMapOf<String, MutableSet<String>>()
        fun add(kind: String, v: String) = findings.getOrPut(kind) { linkedSetOf() }.add(v)
        runCatching {
            java.util.zip.ZipFile(File(apkPath)).use { z ->
                for (e in z.entries()) {
                    val name = e.name
                    val looksText = name.endsWith(".xml") || name.startsWith("res/") || name.startsWith("assets/") ||
                        name.endsWith(".json") || name.endsWith(".properties") || name.endsWith(".txt")
                    if (!looksText || e.size > 4L * 1024 * 1024) continue
                    val text = runCatching { z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: continue
                    SECRET_PATTERNS.forEach { (kind, rx) -> rx.matcher(text).let { m -> while (m.find()) add(kind, m.group().take(140)) } }
                }
            }
        }
        if (findings.isEmpty()) return "No obvious secrets found in strings/ and res/."
        return findings.entries.joinToString("\n\n") { (kind, vals) ->
            kind + ":\n" + vals.take(12).joinToString("\n") { "  • $it" }
        }
    }

    /** Human-readable weakness checklist built from the manifest and badging output. */
    private fun vulnChecklist(badging: String, manifestRaw: String): String {
        val items = mutableListOf<String>()
        fun flag(name: String, risky: Boolean, why: String) = items.add((if (risky) "⚠ " else "✓ ") + name + " — " + why)

        flag("Debuggable", containsFlagTrue(manifestRaw, "debuggable"), "release builds should not set android:debuggable=true")
        flag("allowBackup", containsFlagTrue(manifestRaw, "allowBackup"), "allowBackup=true lets adb backup extract private data")
        flag(
            "Cleartext traffic",
            containsFlagTrue(manifestRaw, "usesCleartextTraffic") || !manifestRaw.contains("networkSecurityConfig"),
            "HTTP (not HTTPS) may be permitted app-wide; prefer TLS everywhere or a strict network security config",
        )
        val exported = Regex("""exported['\x00=\s]{1,4}(true|1)""").findAll(manifestRaw).count()
        flag("Exported components", exported > 0, "$exported component attribute(s) marked exported — review each as an attack surface")
        flag(
            "WebView JS bridge",
            manifestRaw.contains("JavascriptInterface"),
            "addJavascriptInterface exposes native methods to web content — check it only loads trusted pages",
        )
        val perms = Regex("""uses-permission:'([^']*)'""").findAll(badging).map { it.groupValues[1] }.toSet()
        val risky = listOf(
            "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_CONTACTS", "WRITE_CONTACTS",
            "ACCESS_FINE_LOCATION", "CAMERA", "RECORD_AUDIO", "MANAGE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW",
        ).filter { p -> perms.any { it.endsWith(p) } }
        if (risky.isNotEmpty()) items.add("⚠ Powerful permissions granted: " + risky.joinToString(", "))
        return items.joinToString("\n")
    }

    /** Very small heuristic for binary-XML attribute presence followed by a true-ish value. */
    private fun containsFlagTrue(manifestRaw: String, attr: String): Boolean {
        val idx = manifestRaw.indexOf(attr)
        if (idx < 0) return false
        val tail = manifestRaw.substring(idx, minOf(manifestRaw.length, idx + attr.length + 40))
        return Regex("(true)|(\\x00true)|(=true)").containsMatchIn(tail)
    }

    /** Signs the staged APK with uber-apk-signer; copies the signed APK to the app cache. */
    fun patchAndSign(ctx: Context, apkPath: String, log: (String) -> Unit): File? {
        if (!signerJar(ctx).exists()) { log("uber-apk-signer isn't installed — run the setup stage first."); return null }
        val linuxApk = stageApk(ctx, apkPath)
        if (linuxApk == null) { log("Couldn't stage the APK."); return null }
        runCatching { signerJar(ctx).copyTo(File(exposedDir(ctx), signerJar(ctx).name), overwrite = true) }
        log("Signing with uber-apk-signer…")
        val r = runLinux(ctx, "java -jar /root/.arin-hacktools/${signerJar(ctx).name} --apks $linuxApk --overwrite", timeoutMs = 10 * 60_000L, log = log)
        val built = File(Linux.rootfs(ctx), "root/files/hack-target.apk")
        if (r == null || !built.exists()) { log("Signing failed."); return null }
        val dest = File(File(ctx.cacheDir, "patched").apply { mkdirs() }, "patched-signed.apk")
        built.inputStream().use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 16) } }
        log("Signed APK ready: ${dest.name} (${dest.length() / 1024} KB)")
        return dest
    }

    /** The four bundled Frida hooks, by filename. */
    val FRIDA_SCRIPTS: Map<String, String> = linkedMapOf(
        "ssl_pin_bypass.js" to """// Standard Frida SSL unpinning: trust-all SSLContext + TrustManager, plus OkHttp hostname checks.
Java.perform(function () {
    var X509TrustManager = Java.use('javax.net.ssl.X509TrustManager');
    var TrustAll = Java.registerClass({
        name: 'org.arin.TrustAll',
        implements: [X509TrustManager],
        methods: {
            checkClientTrusted: function (chain, authType) {},
            checkServerTrusted: function (chain, authType) {},
            getAcceptedIssuers: function () { return []; }
        }
    });
    var trustAll = [TrustAll.$new()];
    var SSLContext = Java.use('javax.net.ssl.SSLContext');
    SSLContext.init.overload('[Ljavax.net.ssl.KeyManager;', '[Ljavax.net.ssl.TrustManager;', '[Ljava.security.SecureRandom;')
        .implementation = function (km, tm, sr) {
            console.log('[*] Swapping TrustManagers for trust-all');
            this.init(km, trustAll, sr);
        };
    try {
        var OkHostnameVerifier = Java.use('okhttp3.internal.tls.OkHostnameVerifier');
        OkHostnameVerifier.verify.overload('java.lang.String', 'javax.net.ssl.SSLSession').implementation = function () { return true; };
        OkHostnameVerifier.verify.overload('java.lang.String', 'java.security.cert.X509Certificate').implementation = function () { return true; };
    } catch (e) {}
    try {
        var HostnameVerifier = Java.use('javax.net.ssl.HostnameVerifier');
        Java.registerClass({
            name: 'org.arin.TrustHosts',
            implements: [HostnameVerifier],
            methods: { verify: function (h, s) { return true; } }
        });
    } catch (e) {}
    console.log('[*] SSL pinning bypass active');
});
""",
        "root_bypass.js" to """// Hides root indicators: su/busybox files, shell-outs to su, and test-keys build tags.
Java.perform(function () {
    var File = Java.use('java.io.File');
    File.exists.implementation = function () {
        var p = this.getAbsolutePath();
        if (p.indexOf('su') !== -1 || p.indexOf('busybox') !== -1 || p.indexOf('Superuser') !== -1 || p.indexOf('magisk') !== -1) return false;
        return this.exists();
    };
    var Runtime = Java.use('java.lang.Runtime');
    Runtime.exec.overload('java.lang.String').implementation = function (cmd) {
        if (cmd === 'su' || cmd.indexOf('su ') !== -1 || cmd.indexOf('which') !== -1) {
            console.log('[*] Blocked exec: ' + cmd);
            throw Java.use('java.io.IOException').$new('blocked by root_bypass');
        }
        return this.exec(cmd);
    };
    var Build = Java.use('android.os.Build');
    var tags = Build.TAGS.value;
    if (tags && tags.indexOf('test-keys') !== -1) { Build.TAGS.value = 'release-keys'; console.log('[*] TAGS -> release-keys'); }
    console.log('[*] Root hiding active');
});
""",
        "license_bypass.js" to """// Stubs licence checks to licensed: Google Play Licensing's LicenseChecker and any .isLicensed method found.
Java.perform(function () {
    try {
        var Policy = Java.use('com.google.android.vending.licensing.Policy');
        var licensed = Policy.LICENSED.value;
        var LC = Java.use('com.google.android.vending.licensing.LicenseChecker');
        LC.checkAccess.overload('com.google.android.vending.licensing.LicenseCheckerCallback').implementation = function (cb) {
            console.log('[*] LicenseChecker.checkAccess -> LICENSED (' + licensed + ')');
            cb.allow(licensed);
        };
    } catch (e) {}
    var seen = {};
    function stub(name) {
        if (seen[name]) return;
        seen[name] = true;
        try {
            var C = Java.use(name);
            if (C.isLicensed) {
                C.isLicensed.implementation = function () {
                    console.log('[*] ' + name + '.isLicensed -> true');
                    return true;
                };
            }
        } catch (e) {}
    }
    ['LicenseChecker', 'LicenseValidator', 'com.google.android.vending.licensing.LicenseChecker'].forEach(stub);
    Java.enumerateLoadedClasses({
        onMatch: function (name) { if (name.indexOf('License') !== -1) stub(name); },
        onComplete: function () { console.log('[*] Licence checks stubbed to licensed (' + Object.keys(seen).length + ' classes)'); }
    });
});
""",
        "mem_dump.js" to """// Dumps readable heap strings (tokens, credentials and keys often linger here).
Java.perform(function () {
    var out = [];
    Java.choose('java.lang.String', {
        onMatch: function (s) {
            try {
                var v = s.toString();
                if (v.length >= 6 && v.length <= 200 && /[A-Za-z0-9+/=_-]{8,}/.test(v)) out.push(v);
            } catch (e) {}
        },
        onComplete: function () {
            console.log('[*] Dumping ' + out.length + ' candidate strings (first 500):');
            out.slice(0, 500).forEach(function (s) { console.log(s); });
        }
    });
});
""",
    )

    private val SECRET_PATTERNS: Map<String, Pattern> = linkedMapOf(
        "AWS access key" to Pattern.compile("AKIA[0-9A-Z]{16}"),
        "Google API key" to Pattern.compile("AIza[0-9A-Za-z_-]{35}"),
        "Firebase URL" to Pattern.compile("https://[a-zA-Z0-9_-]+(\\.[-a-zA-Z0-9_]+)*\\.firebaseio\\.com"),
        "JWT" to Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
        "Generic token/key" to Pattern.compile("(?i)(api[_-]?key|secret|token)[\"'\\s:=]{1,4}[A-Za-z0-9+/=_-]{16,}"),
        "Private key block" to Pattern.compile("-----BEGIN[ A-Z]*PRIVATE KEY-----"),
    )
}
