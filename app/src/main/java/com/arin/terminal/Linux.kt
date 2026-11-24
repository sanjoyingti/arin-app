package com.arin.terminal

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/**
 * Linux built into Arin: an Ubuntu system in the app's own storage, run with PRoot (no root needed).
 * Commands, Node.js, npm and tools such as REA run inside it and see an ordinary Linux system.
 *
 * PRoot binaries ship as lib*.so files in the APK (jniLibs), built by
 * github.com/green-green-avk/build-proot-android from github.com/green-green-avk/proot (GPL-2.0).
 * Running programs from app storage needs targetSdk 28, the same as Termux.
 */
object Linux {
    const val HOME = "/root"

    /** Where attached files are copied, inside Linux. */
    const val FILES = "/root/files"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    private fun base(context: Context) = File(context.filesDir, "linux")
    fun rootfs(context: Context) = File(base(context), "rootfs")
    private fun tmp(context: Context) = File(base(context), "tmp").apply { mkdirs() }
    private fun fake(context: Context) = File(base(context), "fake").apply { mkdirs() }
    private fun marker(context: Context) = File(base(context), "installed")
    private fun nodeMarker(context: Context) = File(base(context), "node-ready")

    fun installed(context: Context): Boolean = marker(context).exists() && File(rootfs(context), "bin").exists()
    fun nodeReady(context: Context): Boolean = installed(context) && nodeMarker(context).exists()

    /** Ubuntu's name for this phone's processor family, or null if PRoot isn't bundled for it. */
    private fun ubuntuArch(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a" -> "armhf"
        "x86_64" -> "amd64"
        else -> null
    }

    fun supported(): Boolean = ubuntuArch() != null

    private fun proot(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    // ------------------------------------------------------------------ install

    /**
     * Downloads Ubuntu 24.04 (about 30 MB), unpacks it, then installs Node.js 24, git, Python and
     * the basics. [log] receives progress lines. Takes a few minutes; safe to run again.
     */
    suspend fun install(context: Context, log: (String) -> Unit) = withContext(Dispatchers.IO) {
        val arch = ubuntuArch() ?: throw IllegalStateException("This phone's processor (${Build.SUPPORTED_ABIS.firstOrNull()}) isn't supported.")
        if (!proot(context).exists()) {
            throw IllegalStateException("PRoot is missing from this build of Arin (looked in ${context.applicationInfo.nativeLibraryDir}).")
        }
        if (!installed(context)) {
            log("Finding the latest Ubuntu 24.04 base image…")
            val listing = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
            val page = get(listing).use { it.body!!.string() }
            // Newest point release, e.g. ubuntu-base-24.04.3-base-arm64.tar.gz.
            val file = Regex("""ubuntu-base-24\.04(?:\.(\d+))?-base-$arch\.tar\.gz""").findAll(page)
                .maxByOrNull { it.groupValues[1].toIntOrNull() ?: 0 }?.value
                ?: throw IllegalStateException("Couldn't find the Ubuntu image for $arch at $listing")
            log("Downloading $file…")
            val root = rootfs(context)
            root.deleteRecursively()
            root.mkdirs()
            get(listing + file).use { resp ->
                val total = resp.body!!.contentLength()
                val counting = CountingStream(resp.body!!.byteStream()) { n ->
                    if (total > 0) log("Downloading Ubuntu… ${n * 100 / total}% of ${total / (1024 * 1024)} MB")
                }
                log("Unpacking Ubuntu…")
                extract(counting, root)
            }
            configure(context)
            marker(context).writeText(file)
            log("Ubuntu is unpacked.")
        } else {
            configure(context)
        }
        log("Installing basics with apt (this takes a few minutes)…")
        val script = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y --no-install-recommends ca-certificates curl xz-utils procps git python3 python3-pip nano less unzip zip
            case "${'$'}(uname -m)" in aarch64) NA=arm64;; armv7l|armv8l) NA=armv7l;; x86_64) NA=x64;; *) echo "unknown arch"; exit 1;; esac
            if ! node -v 2>/dev/null | grep -Eq '^v24\.(2[2-9]|[3-9][0-9])\.'; then
              V=${'$'}(curl -fsSL https://nodejs.org/dist/latest-v24.x/ | grep -o "node-v24[0-9.]*-linux-${'$'}NA.tar.xz" | head -1)
              echo "Installing ${'$'}V"
              curl -fsSL "https://nodejs.org/dist/latest-v24.x/${'$'}V" | tar -xJ -C /usr/local --strip-components=1
            fi
            echo "node ${'$'}(node -v), npm ${'$'}(npm -v)"
        """.trimIndent()
        val r = run(context, script, timeoutMs = 45 * 60_000L, onOutput = { line -> log(line) })
        if (r.exitCode != 0 || r.error != null) {
            throw IllegalStateException("Setting up Linux failed (exit ${r.exitCode}): ${r.error ?: r.combined().takeLast(600)}")
        }
        nodeMarker(context).writeText("ok")
        log("Linux is ready: Ubuntu 24.04 with Node.js, npm, git and Python.")
    }

    private fun devMarker(context: Context) = File(base(context), "devtools-ready")

    fun devToolsReady(context: Context): Boolean = installed(context) && devMarker(context).exists()

    /** Versions installed by [installDevTools] (checked November 2026). */
    val KOTLINC_VERSION: String get() = com.arin.builder.Toolchain.get("KOTLIN")
    val GRADLE_VERSION: String get() = com.arin.builder.Toolchain.get("GRADLE")

    /**
     * Installs a Java/Kotlin toolchain: OpenJDK 21, the Kotlin compiler and Gradle, so Java and Kotlin
     * programs (and Gradle projects that don't need the Android SDK) build and run in Linux.
     */
    suspend fun installDevTools(context: Context, log: (String) -> Unit): Termux.Result {
        log("Installing Java 21, Kotlin $KOTLINC_VERSION and Gradle $GRADLE_VERSION…")
        val script = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y --no-install-recommends openjdk-21-jdk-headless unzip zip
            mkdir -p /opt
            if [ ! -x /opt/kotlinc/bin/kotlinc ]; then
              curl -fsSL -o /tmp/kotlinc.zip https://github.com/JetBrains/kotlin/releases/download/v$KOTLINC_VERSION/kotlin-compiler-$KOTLINC_VERSION.zip
              rm -rf /opt/kotlinc && unzip -q /tmp/kotlinc.zip -d /opt && rm /tmp/kotlinc.zip
            fi
            if [ ! -x /opt/gradle-$GRADLE_VERSION/bin/gradle ]; then
              curl -fsSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip
              unzip -q /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip
            fi
            ln -sf /opt/kotlinc/bin/kotlinc /opt/kotlinc/bin/kotlin /usr/local/bin/
            ln -sf /opt/gradle-$GRADLE_VERSION/bin/gradle /usr/local/bin/gradle
            java -version 2>&1 | head -1
            kotlinc -version 2>&1 | tail -1
            gradle --version 2>/dev/null | grep -m1 Gradle
        """.trimIndent()
        val r = run(context, script, timeoutMs = 45 * 60_000L, onOutput = log)
        if (r.exitCode == 0 && r.error == null) devMarker(context).writeText("ok")
        return r
    }

    private fun androidBuildMarker(context: Context) = File(base(context), "android-build-ready")

    /** True when apps can be compiled to APKs right here in Linux (JDK, Gradle, Android SDK, aapt2). */
    fun androidBuildReady(context: Context): Boolean = installed(context) && androidBuildMarker(context).exists()

    /**
     * Installs everything needed to compile Android apps on the phone: JDK 21, Gradle, the Android SDK
     * (command-line tools, platform ${com.arin.builder.ProjectTemplate.SDK}), an aapt2 that runs on ARM, and the
     * arin-build command. Build mode then checks each build here before GitHub.
     */
    suspend fun installAndroidBuild(context: Context, log: (String) -> Unit): Termux.Result {
        log("Installing Android build tools: JDK 21, Gradle ${com.arin.builder.ProjectTemplate.GRADLE}, Android SDK ${com.arin.builder.ProjectTemplate.SDK}, aapt2…")
        configure(context)
        val script = context.assets.open("android-build-setup.sh").bufferedReader().use { it.readText() }
            .replace("@@GRADLE@@", com.arin.builder.ProjectTemplate.GRADLE)
            .replace("@@SDK@@", com.arin.builder.ProjectTemplate.SDK.toString())
        File(rootfs(context), "root/.arin/setup-android-build.sh").writeText(script)
        val r = run(context, "bash /root/.arin/setup-android-build.sh", timeoutMs = 60 * 60_000L, onOutput = log)
        if (r.exitCode == 0 && r.error == null && r.combined().contains("ANDROID_BUILD_READY")) {
            androidBuildMarker(context).writeText("ok")
            devMarker(context).writeText("ok")
        }
        return r
    }

    /** Script (inside Linux) that installs what REA needs to analyse APKs. */
    const val ANDROID_TOOLS_SCRIPT = "/root/.arin/setup-android-tools.sh"

    /**
     * REA analyses APKs through jadx-headless-mcp (a Java program, so it runs on ARM too) and decodes resources
     * with apktool; both need a full JDK. REA finds them through REA_JADX_MCP_JAR, JAVA_HOME and
     * REA_APKTOOL_COMMAND, written to ~/.arin/rea.env, which REA's start command loads.
     * JADX version and checksum are REA's pinned ones (docs/guides/android/android-analysis.md).
     *
     * For CHANGING an app and getting an installable APK back, two more pure-Java tools go in:
     * APKEditor (decompiles AND rebuilds with no arm64 aapt2 needed) and uber-apk-signer (zipalign + sign).
     * A signing key is generated once. [rebuildSignScript] (written to [REBUILD_SIGN_SCRIPT]) ties them together.
     */
    private val androidToolsScript = listOf(
        "#!/bin/bash",
        "# Installs JDK 21, latest JADX headless MCP, apktool, APKEditor and uber-apk-signer for REA's Android tools.",
        "set -e",
        "export DEBIAN_FRONTEND=noninteractive",
        "if ! command -v javac >/dev/null 2>&1; then",
        "  apt-get update",
        "  apt-get install -y --no-install-recommends openjdk-21-jdk-headless",
        "fi",
        "mkdir -p /opt/rea-android",
        "cd /opt/rea-android",
        "# Resolve the latest official JADX MCP release and verify its published SHA-256.",
        "REL=/tmp/rea-jadx-release.json",
        "curl -fsSL https://api.github.com/repos/1013503897/jadx-headless-mcp/releases/latest -o \"\$REL\"",
        "JADX=\$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(next(a[\"name\"] for a in d[\"assets\"] if a[\"name\"].endswith(\"-all.jar\")))' \"\$REL\")",
        "JADX_URL=\$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(next(a[\"browser_download_url\"] for a in d[\"assets\"] if a[\"name\"].endswith(\"-all.jar\")))' \"\$REL\")",
        "JADX_SUM=\$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); a=next(a for a in d[\"assets\"] if a[\"name\"].endswith(\"-all.jar\")); print((a.get(\"digest\") or \"\").removeprefix(\"sha256:\"))' \"\$REL\")",
        "if [ -z \"\$JADX_SUM\" ]; then",
        "  SUM_URL=\$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(next(a[\"browser_download_url\"] for a in d[\"assets\"] if a[\"name\"]==\"jadx-headless-mcp.sha256\"))' \"\$REL\")",
        "  curl -fsSL \"\$SUM_URL\" -o /tmp/rea-jadx.sha256",
        "  JADX_SUM=\$(awk '{print \$1}' /tmp/rea-jadx.sha256)",
        "fi",
        "[ -n \"\$JADX_SUM\" ] || { echo 'Official JADX release has no published SHA-256; refusing unverified download.'; exit 1; }",
        "if [ ! -f \"\$JADX\" ] || ! echo \"\$JADX_SUM  \$JADX\" | sha256sum -c - >/dev/null 2>&1; then",
        "  rm -f \"\$JADX\"",
        "  curl -fL -o \"\$JADX\" \"\$JADX_URL\"",
        "  echo \"\$JADX_SUM  \$JADX\" | sha256sum -c -",
        "fi",
        "rm -f /tmp/rea-jadx-release.json /tmp/rea-jadx.sha256",
        "# Always the newest release: download again whenever a newer one is out.",
        "URL=\$(curl -fsSL https://api.github.com/repos/iBotPeaches/Apktool/releases/latest | grep -o 'https://[^\"]*\\.jar' | head -1 || true)",
        "if [ ! -f apktool.jar ] || { [ -n \"\$URL\" ] && [ \"\$(cat apktool.jar.url 2>/dev/null)\" != \"\$URL\" ]; }; then",
        "  [ -n \"\$URL\" ] || { echo \"Couldn't find the apktool download\"; exit 1; }",
        "  curl -fL -o apktool.jar.new \"\$URL\" && mv apktool.jar.new apktool.jar && echo \"\$URL\" > apktool.jar.url && echo \"Updated apktool.jar: \$URL\"",
        "fi",
        "printf '#!/bin/sh\\nexec java -jar /opt/rea-android/apktool.jar \"\$@\"\\n' > /usr/local/bin/apktool",
        "chmod +x /usr/local/bin/apktool",
        "# APKEditor: pure-Java decompile/rebuild, so rebuilding works on arm64 without aapt2.",
        "# Always the newest release: download again whenever a newer one is out.",
        "URL=\$(curl -fsSL https://api.github.com/repos/REAndroid/APKEditor/releases/latest | grep -o 'https://[^\"]*APKEditor-[^\"]*\\.jar' | head -1 || true)",
        "if [ ! -f APKEditor.jar ] || { [ -n \"\$URL\" ] && [ \"\$(cat APKEditor.jar.url 2>/dev/null)\" != \"\$URL\" ]; }; then",
        "  [ -n \"\$URL\" ] || { echo \"Couldn't find the APKEditor download\"; exit 1; }",
        "  curl -fL -o APKEditor.jar.new \"\$URL\" && mv APKEditor.jar.new APKEditor.jar && echo \"\$URL\" > APKEditor.jar.url && echo \"Updated APKEditor.jar: \$URL\"",
        "fi",
        "printf '#!/bin/sh\\nexec java -jar /opt/rea-android/APKEditor.jar \"\$@\"\\n' > /usr/local/bin/apkeditor",
        "chmod +x /usr/local/bin/apkeditor",
        "# uber-apk-signer: pure-Java zipalign + sign.",
        "# Always the newest release: download again whenever a newer one is out.",
        "URL=\$(curl -fsSL https://api.github.com/repos/patrickfav/uber-apk-signer/releases/latest | grep -o 'https://[^\"]*uber-apk-signer-[^\"]*\\.jar' | head -1 || true)",
        "if [ ! -f uber-apk-signer.jar ] || { [ -n \"\$URL\" ] && [ \"\$(cat uber-apk-signer.jar.url 2>/dev/null)\" != \"\$URL\" ]; }; then",
        "  [ -n \"\$URL\" ] || { echo \"Couldn't find the uber-apk-signer download\"; exit 1; }",
        "  curl -fL -o uber-apk-signer.jar.new \"\$URL\" && mv uber-apk-signer.jar.new uber-apk-signer.jar && echo \"\$URL\" > uber-apk-signer.jar.url && echo \"Updated uber-apk-signer.jar: \$URL\"",
        "fi",
        "JH=\$(dirname \"\$(dirname \"\$(readlink -f \"\$(command -v javac)\")\")\")",
        "mkdir -p /root/.arin",
        "# One signing key, reused so each rebuild installs over the previous one.",
        "KS=/root/.arin/arin-rebuild.keystore",
        "if [ ! -f \"\$KS\" ]; then",
        "  keytool -genkeypair -v -keystore \"\$KS\" -storepass arinrebuild -keypass arinrebuild -alias arin -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Arin, OU=Arin, O=Arin, L=Arin, ST=Arin, C=IN'",
        "fi",
        "{ echo \"REA_JADX_MCP_JAR=/opt/rea-android/\$JADX\"; echo \"JAVA_HOME=\$JH\"; echo \"REA_APKTOOL_COMMAND=/usr/local/bin/apktool\"; echo \"REA_APKEDITOR_COMMAND=/usr/local/bin/apkeditor\"; echo COLUMNS=120; echo LINES=40; } > /root/.arin/rea.env",
        "java -version 2>&1 | head -1",
        "apktool --version 2>/dev/null | head -1",
        "echo \"Android tools ready: \$JADX, apktool, APKEditor, uber-apk-signer, JAVA_HOME=\$JH\"",
        "# REA must restart to pick up the new settings; Arin starts it again on the next tool call.",
        "pkill -f mcp-bridge.mjs || true",
    ).joinToString("\n", postfix = "\n")

    /** Helper (inside Linux) that rebuilds a decoded APKEditor project and signs it. */
    const val REBUILD_SIGN_SCRIPT = "/root/.arin/rebuild-sign.sh"

    /**
     * Rebuilds a decoded APKEditor project (a folder) into an APK and signs it, or just signs an APK that is
     * already built. Prints the path of the finished, installable APK on its last line.
     * Usage: bash rebuild-sign.sh <decoded-dir|apk> [out.apk]
     */
    private val rebuildSignScript = """
        #!/bin/bash
        set -e
        IN=${'$'}{1:?usage: rebuild-sign.sh <decoded-dir|apk> [out.apk]}
        OUT=${'$'}{2:-/root/files/rebuilt-signed.apk}
        KS=/root/.arin/arin-rebuild.keystore
        [ -e "${'$'}IN" ] || { echo "No such path: ${'$'}IN" >&2; exit 1; }
        [ -f "${'$'}KS" ] || { echo "No signing key yet — install the Android tools first." >&2; exit 1; }
        work=${'$'}(mktemp -d)
        if [ -d "${'$'}IN" ]; then
          built="${'$'}work/rebuilt.apk"
          echo "Rebuilding ${'$'}IN with APKEditor…" >&2
          java -jar /opt/rea-android/APKEditor.jar b -f -i "${'$'}IN" -o "${'$'}built" >&2
        else
          built="${'$'}IN"
        fi
        echo "Signing…" >&2
        java -jar /opt/rea-android/uber-apk-signer.jar --allowResign -a "${'$'}built" --ks "${'$'}KS" --ksAlias arin --ksPass arinrebuild --ksKeyPass arinrebuild --out "${'$'}work" >&2
        signed=${'$'}(ls "${'$'}work"/*-signed*.apk 2>/dev/null | head -1)
        [ -n "${'$'}signed" ] || { echo "Signing produced no APK." >&2; exit 1; }
        mkdir -p "${'$'}(dirname "${'$'}OUT")"
        cp "${'$'}signed" "${'$'}OUT"
        echo "${'$'}OUT"
    """.trimIndent() + "\n"

    /** Installs JDK, JADX, apktool, APKEditor and uber-apk-signer, and writes the rebuild helper. */
    suspend fun installAndroidTools(context: Context, log: (String) -> Unit): Termux.Result {
        log("Installing REA's Android tools (JDK 21, JADX, apktool, APKEditor, signer)…")
        configure(context)
        File(rootfs(context), "root/.arin").mkdirs()
        File(rootfs(context), "root/.arin/rebuild-sign.sh").writeText(rebuildSignScript)
        return run(context, "bash $ANDROID_TOOLS_SCRIPT", timeoutMs = 30 * 60_000L, onOutput = log)
    }

    /**
     * Installs REA (npm package rea-agents) in Linux, repairing whatever gets in the way: it installs missing
     * prerequisites, clears npm's cache (a corrupt cache crashes Node 24 on ARM with "malloc(): invalid size"
     * or "double free"), retries, and if Node keeps crashing it switches to another supported Node line
     * (24 → 22 → 26) and tries again.
     */
    suspend fun installRea(context: Context, log: (String) -> Unit): Termux.Result {
        log("Checking what REA needs (Node.js 22.23.4+, 24.22.1+ or 26.12.1+, procps, xz)…")
        val script = """
            need() { command -v "${'$'}1" >/dev/null 2>&1; }
            if ! need ps || ! need xz || ! need curl; then
              echo "Installing prerequisites (procps, xz-utils, curl)…"
              apt-get update -qq >/dev/null 2>&1
              apt-get install -y -qq procps xz-utils curl ca-certificates >/dev/null 2>&1 || echo "Warning: couldn't install some prerequisites"
            fi
            case "${'$'}(uname -m)" in aarch64) NA=arm64;; armv7l|armv8l) NA=armv7l;; x86_64) NA=x64;; *) echo "Unknown CPU type ${'$'}(uname -m)"; exit 1;; esac
            node_ok() {
              node -e 'const [a,b,c]=process.versions.node.split(".").map(Number);const v=a*1e6+b*1e3+c;process.exit((a===22&&v>=22023004)||(a===24&&v>=24022001)||(a>=26&&v>=26012001)?0:1)' 2>/dev/null
            }
            put_node() {
              V=${'$'}(curl -fsSL "https://nodejs.org/dist/latest-v${'$'}1.x/" | grep -o "node-v${'$'}1[0-9.]*-linux-${'$'}NA.tar.xz" | head -1)
              [ -n "${'$'}V" ] || { echo "Couldn't find Node.js ${'$'}1 for ${'$'}NA"; return 1; }
              echo "Installing ${'$'}V…"
              rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack
              curl -fsSL "https://nodejs.org/dist/latest-v${'$'}1.x/${'$'}V" | tar -xJ -C /usr/local --strip-components=1 || return 1
              hash -r
              echo "node ${'$'}(node -v), npm ${'$'}(npm -v)"
            }
            clean() {
              npm cache clean --force >/dev/null 2>&1
              rm -rf /root/.npm/_cacache /root/.npm/_logs /usr/local/lib/node_modules/rea-agents /usr/local/lib/node_modules/.rea-agents-* /usr/local/bin/rea
            }
            try_rea() {
              clean
              npm install --global rea-agents --omit=optional --no-fund --no-audit --maxsockets=2 --prefer-online 2>&1 | tail -15 && COLUMNS=120 rea --version
            }
            if ! node_ok; then echo "Node.js ${'$'}(node -v 2>/dev/null || echo missing) is too old or missing."; put_node 24; fi
            echo "node ${'$'}(node -v 2>/dev/null)"
            current=${'$'}(node -p 'process.versions.node.split(".")[0]' 2>/dev/null)
            tried=""
            for line in "${'$'}current" 22 26; do
              case " ${'$'}tried " in *" ${'$'}line "*) continue;; esac
              tried="${'$'}tried ${'$'}line"
              if [ "${'$'}line" != "${'$'}current" ]; then
                echo ""; echo "Node.js ${'$'}current keeps crashing during the install; switching to Node.js ${'$'}line…"
                put_node "${'$'}line" || continue
                node_ok || continue
                current=${'$'}line
              fi
              for n in 1 2; do
                echo ""; echo "Installing REA with Node.js ${'$'}(node -v) (attempt ${'$'}n)…"
                if try_rea; then echo ""; echo "REA installed."; exit 0; fi
                sleep 3
              done
            done
            echo "REA still couldn't be installed after trying Node.js 24, 22 and 26."
            exit 1
        """.trimIndent()
        return run(context, script, timeoutMs = 60 * 60_000L, onOutput = log)
    }

    /** Deletes the Linux system and everything in it. */
    suspend fun remove(context: Context) = withContext(Dispatchers.IO) {
        // PRoot may have made folders read-only; make them writable so they can be deleted.
        base(context).walkBottomUp().forEach { runCatching { it.setWritable(true) } }
        base(context).deleteRecursively()
    }

    private fun get(url: String): okhttp3.Response {
        val resp = http.newCall(Request.Builder().url(url).header("User-Agent", "Arin").build()).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw IllegalStateException("Download failed: HTTP ${resp.code} for $url")
        }
        return resp
    }

    private class CountingStream(private val inner: InputStream, private val onProgress: (Long) -> Unit) : InputStream() {
        private var count = 0L
        private var lastReport = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) bump(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) bump(it.toLong()) }
        override fun close() = inner.close()
        private fun bump(n: Long) {
            count += n
            if (count - lastReport > 2L * 1024 * 1024) {
                lastReport = count
                onProgress(count)
            }
        }
    }

    /** Unpacks a .tar.gz, keeping symlinks and permissions; hard links become copies. */
    private suspend fun extract(input: InputStream, root: File) {
        val hardLinks = mutableListOf<Pair<File, String>>()
        TarArchiveInputStream(GZIPInputStream(input.buffered(256 * 1024))).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val e: TarArchiveEntry = tar.nextEntry ?: break
                val name = e.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty() || name.split('/').any { it == ".." }) continue
                val out = File(root, name)
                when {
                    e.isDirectory -> out.mkdirs()
                    e.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        runCatching { out.delete() }
                        runCatching { Os.symlink(e.linkName, out.absolutePath) }
                    }
                    e.isLink -> hardLinks += out to e.linkName.removePrefix("./")
                    e.isFile -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it, 64 * 1024) }
                    }
                    else -> continue // device nodes, fifos: not possible or needed in app storage
                }
                if (!e.isSymbolicLink) runCatching { Os.chmod(out.absolutePath, e.mode and 0x1FF or (if (e.isDirectory) 0x1C0 else 0x180)) }
            }
        }
        hardLinks.forEach { (out, target) ->
            val src = File(root, target)
            if (src.isFile) {
                out.parentFile?.mkdirs()
                src.copyTo(out, overwrite = true)
                runCatching { Os.chmod(out.absolutePath, Os.stat(src.absolutePath).st_mode and 0x1FF) }
            }
        }
    }

    /** Settings that make Ubuntu work inside an app: DNS, hosts, apt without a sandbox user, a home folder. */
    private fun configure(context: Context) {
        val root = rootfs(context)
        File(root, "etc").mkdirs()
        File(root, "etc/resolv.conf").apply { runCatching { delete() } }.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        File(root, "etc/apt/apt.conf.d").mkdirs()
        File(root, "etc/apt/apt.conf.d/01-arin").writeText(
            "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\nDPkg::Use-Pty \"false\";\n",
        )
        File(root, "etc/profile.d").mkdirs()
        File(root, "etc/profile.d/arin.sh").writeText(
            "# Set by Arin: PRoot can't translate io_uring or handle glibc's rseq; both can crash Node/npm.\n" +
                "export UV_USE_IO_URING=0\nexport GLIBC_TUNABLES=glibc.pthread.rseq=0\n" +
                "# No real terminal: give a size, or REA's CLI crashes (double free) when it asks for one.\n" +
                "export COLUMNS=\${COLUMNS:-120} LINES=\${LINES:-40}\n",
        )
        File(root, "root/.npmrc").takeIf { !it.exists() }?.writeText("fund=false\naudit=false\nupdate-notifier=false\n")
        File(root, "root/.arin").mkdirs()
        File(root, ANDROID_TOOLS_SCRIPT.removePrefix("/")).writeText(androidToolsScript)
        File(root, "root/files").mkdirs()
        File(root, "root/.arin").mkdirs()
        File(root, "tmp").mkdirs()
        runCatching { Os.chmod(File(root, "tmp").absolutePath, 0x1FF) }
        // Android hides some /proc files from apps; give programs harmless stand-ins.
        val f = fake(context)
        File(f, "loadavg").writeText("0.12 0.07 0.02 2/165 765\n")
        File(f, "uptime").writeText("7524.15 22341.88\n")
        File(f, "version").writeText("Linux version ${System.getProperty("os.version")} (arin@android) #1 SMP PREEMPT\n")
        File(f, "stat").writeText(
            "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\n" +
                "intr 63361 0 0 0\nctxt 38014093\nbtime 1694292441\nprocesses 26442\nprocs_running 1\nprocs_blocked 0\nsoftirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n",
        )
        File(f, "vmstat").writeText("nr_free_pages 136341\nnr_zone_inactive_anon 4\nnr_zone_active_anon 0\n")
    }

    // ------------------------------------------------------------------ running

    /**
     * Runs [command] with bash in Linux (home /root, as root) and waits until it finishes, at most
     * [timeoutMs]. Background jobs it starts (nohup … &) keep running after it returns.
     */
    suspend fun run(
        context: Context,
        command: String,
        timeoutMs: Long = 15 * 60_000L,
        onOutput: ((String) -> Unit)? = null,
    ): Termux.Result = withContext(Dispatchers.IO) {
        if (!installed(context)) return@withContext Termux.Result("", "", null, "Linux isn't installed. Open Settings → Linux and tap Install Linux.")
        val root = rootfs(context)
        // Installs from older Arin versions get the newer fixes too.
        if (File(root, "etc/profile.d/arin.sh").let { !it.exists() || !it.readText().contains("COLUMNS") } ||
            !File(root, ANDROID_TOOLS_SCRIPT.removePrefix("/")).exists()
        ) runCatching { configure(context) }
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val done = "__ARIN_DONE_${id}__"
        // The command goes in a script file, so no quoting is needed. Its exit code is printed on a
        // marker line, so background jobs it leaves running don't keep us waiting.
        val script = File(root, "tmp/arin-$id.sh")
        // pipefail: a crash before "| head" must not look like success.
        script.writeText("cd $HOME 2>/dev/null\n(\nset -o pipefail\n$command\n)\nrc=$?\nprintf '\\n$done%s\\n' \"\$rc\" >&2\n")
        val cmd = mutableListOf(
            proot(context).absolutePath,
            "-0", "-r", root.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${root.absolutePath}/tmp:/dev/shm",
            "--link2symlink",
            "-w", HOME,
        )
        listOf("loadavg", "stat", "uptime", "version", "vmstat").forEach { name ->
            if (!File("/proc/$name").canRead()) cmd += listOf("-b", "${File(fake(context), name).absolutePath}:/proc/$name")
        }
        cmd += listOf(
            "/usr/bin/env", "-i",
            "HOME=$HOME", "USER=root", "LANG=C.UTF-8", "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp", "CI=1", "DEBIAN_FRONTEND=noninteractive", "NPM_CONFIG_UPDATE_NOTIFIER=false",
            // Node's libuv uses io_uring for files; PRoot can't translate io_uring paths, which corrupts
            // memory (malloc(): invalid size, double free) in npm and REA. Use plain syscalls instead.
            "UV_USE_IO_URING=0",
            // glibc 2.35+ registers rseq, which PRoot doesn't handle; it can corrupt the heap.
            "GLIBC_TUNABLES=glibc.pthread.rseq=0",
            // No real terminal here: programs that ask for the terminal size (REA's CLI does) crash under PRoot
            // with "double free or corruption" unless a size is given.
            "COLUMNS=120", "LINES=40",
            "NPM_CONFIG_FUND=false", "NPM_CONFIG_AUDIT=false",
            "/bin/bash", "-l", "/tmp/arin-$id.sh",
        )
        val pb = ProcessBuilder(cmd).directory(root)
        pb.environment().apply {
            clear()
            put("PROOT_TMP_DIR", tmp(context).absolutePath)
            put("PROOT_LOADER", File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so").absolutePath)
            val l32 = File(context.applicationInfo.nativeLibraryDir, "libproot-loader32.so")
            if (l32.exists()) put("PROOT_LOADER_32", l32.absolutePath)
            put("PATH", "/system/bin")
            put("ANDROID_ROOT", "/system")
            put("ANDROID_DATA", "/data")
        }
        val process = try {
            pb.start()
        } catch (e: Exception) {
            return@withContext Termux.Result("", "", null, "Couldn't start Linux: ${e.message}")
        }
        val out = StringBuffer()
        val err = StringBuffer()
        val finished = kotlinx.coroutines.CompletableDeferred<Int?>()
        fun pump(stream: InputStream, into: StringBuffer, isErr: Boolean) = Thread {
            runCatching {
                stream.bufferedReader().forEachLine { line ->
                    if (isErr && line.startsWith(done)) {
                        finished.complete(line.removePrefix(done).trim().toIntOrNull())
                    } else if (!finished.isCompleted) {
                        if (into.length < 2_000_000) into.append(line).append('\n')
                        onOutput?.invoke(line)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        pump(process.inputStream, out, false)
        pump(process.errorStream, err, true)
        Thread {
            val code = runCatching { process.waitFor() }.getOrNull()
            finished.complete(code)
        }.apply { isDaemon = true; start() }
        try {
            val code = withTimeout(timeoutMs) { finished.await() }
            Termux.Result(out.toString(), err.toString(), code, null)
        } catch (e: TimeoutCancellationException) {
            process.destroy()
            Termux.Result(out.toString(), err.toString(), null, "Stopped after ${timeoutMs / 60_000} min without finishing.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            process.destroy() // Stop was pressed
            throw e
        } finally {
            runCatching { script.delete() }
        }
    }

    /** Copies an attached file into Linux (/root/files) and returns its path there, or null. */
    fun importFile(context: Context, input: InputStream, name: String): String? = runCatching {
        if (!installed(context)) return null
        val safe = name.replace('/', '_').ifBlank { "file" }
        val dir = File(rootfs(context), "root/files").apply { mkdirs() }
        var target = File(dir, safe)
        var n = 1
        while (target.exists()) {
            target = File(dir, safe.substringBeforeLast('.', safe) + " ($n)" + (if ('.' in safe) "." + safe.substringAfterLast('.') else ""))
            n++
        }
        target.outputStream().use { input.copyTo(it, 64 * 1024) }
        "$FILES/${target.name}"
    }.getOrNull()
}

/** Where commands run: Arin's built-in Linux when installed, otherwise Termux. */
object Shell {
    fun usesLinux(context: Context): Boolean = Linux.installed(context)

    fun home(context: Context): String = if (usesLinux(context)) Linux.HOME else Termux.HOME

    fun problem(context: Context): String? =
        if (usesLinux(context)) null
        else Termux.problem(context)?.let {
            "No terminal is set up. Open Settings → Terminal and tap Install Linux (built in), or set up Termux."
        }

    suspend fun run(context: Context, command: String, timeoutMs: Long = 15 * 60_000L): Termux.Result =
        if (usesLinux(context)) Linux.run(context, command, timeoutMs) else Termux.run(context, command, timeoutMs = timeoutMs)

    /** A line describing the environment, for the model. */
    fun describe(context: Context): String =
        if (usesLinux(context)) {
            "Ubuntu 24.04 Linux built into the Arin app (PRoot, ${System.getProperty("os.arch")}), you are root, home is /root. " +
                "Install packages with apt-get install -y; Node.js 24, npm, git and Python 3 are installed. " +
                "Files the person attaches are copied to ${Linux.FILES}. " +
                (if (Linux.devToolsReady(context)) "Developer tools are installed: Java 21 (javac, java), Kotlin (kotlinc), Gradle. " else "") +
                (if (Linux.androidBuildReady(context))
                    "Android build tools are installed (JDK 21, Gradle, Android SDK at /opt/android-sdk, ARM aapt2): compile any Android " +
                        "project folder to an APK with: arin-build /path/to/project (prints ARIN_APK=<path> on success, errors otherwise). " +
                        "Build mode (<build>) also compiles and fixes apps here first, then on GitHub for download links. "
                else "Android APKs can't be compiled on the phone until the Android build tools are installed (Settings → Linux → " +
                    "Install Android build tools); meanwhile use Build mode (<build>), which builds on GitHub. ") +
                "If an REA Android/APK tool says JADX, a JDK or apktool is missing, run: bash ${Linux.ANDROID_TOOLS_SCRIPT} " +
                "(then call the tool again; REA restarts automatically). Other APK tools you can install yourself: " +
                "pip install androguard, or use unzip/apktool on files in /root/files. " +
                "To CHANGE an app and produce an installable APK: decode it for editing with apkeditor d -i <app.apk> -o " +
                "/root/files/rea/<name>, edit the smali/resources under that folder, then run bash ${Linux.REBUILD_SIGN_SCRIPT} " +
                "/root/files/rea/<name> — it rebuilds (APKEditor, no aapt2 needed) and signs (uber-apk-signer), printing the " +
                "finished signed APK path on its last line. The person can install that APK from the Rebuild & install button, " +
                "or you can tell them the path. Re-signed APKs install as a separate app; they can't update a Play-signed install."
        } else {
            "Termux on Android (${System.getProperty("os.arch")}, no root), home ~ = ${Termux.HOME}. Install packages with pkg install -y."
        }
}
