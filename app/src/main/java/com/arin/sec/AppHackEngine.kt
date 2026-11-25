package com.arin.sec

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.arin.terminal.Linux
import com.arin.terminal.Termux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/** A step that couldn't continue, with a message worth showing. */
class HackFail(message: String) : Exception(message)

/**
 * App Hacking engine. Everything runs in Arin's built-in Linux with the tools that are already
 * installed (apktool 3, jadx, apkleaks, dex2jar, frida, objection, mitmproxy, aapt2, the
 * rebuild-sign helper). Nothing here downloads tools by itself.
 *
 * Layout of a session: /root/files/apphack/<pkg>-<time>/
 *   original/  the untouched base + split APKs (never modified)
 *   merged.apk the merged copy everything else is done on
 *   work/      apktool output (manifest, resources, smali) — where patches are written
 *   java/      jadx output, for reading
 *   built/     rebuilt and signed APKs
 */
object AppHackEngine {
    const val BASE = "/root/files/apphack"
    const val HOOKS = "/root/.arin/sec/hooks"
    private const val PICKED = "$BASE/_picked"

    data class Target(
        val packageName: String,
        val label: String,
        val dir: String,
        val originalDir: String,
        val mergedApk: String,
        val versionCode: String,
        val versionName: String,
    )

    data class Component(val kind: String, val name: String, val exported: Boolean)
    data class Flag(val name: String, val found: Boolean, val evidence: String)
    data class Recon(
        val packageName: String,
        val versionName: String,
        val versionCode: String,
        val debuggable: Boolean,
        val allowBackup: Boolean,
        val cleartext: Boolean,
        val components: List<Component>,
        val permissions: List<String>,
        val certificates: List<String>,
        val nativeLibs: List<String>,
        val flags: List<Flag>,
    )

    /** One file change of a patch: [old] is the text it replaces, [new] what it becomes. */
    data class Edit(
        val linuxPath: String,
        val old: String,
        val new: String,
        val bytes: ByteArray? = null,
        val newFile: Boolean = false,
    ) {
        override fun equals(other: Any?) = other is Edit && other.linuxPath == linuxPath && other.new == new
        override fun hashCode() = linuxPath.hashCode() * 31 + new.hashCode()
    }

    data class PatchOptions(
        val label: String = "",
        val iconBytes: ByteArray? = null,
        val removePermissions: Set<String> = emptySet(),
    )

    data class Applied(val linuxPath: String, val backup: String, val title: String)

    // ---- helpers ----

    fun host(ctx: Context, linux: String) = File(Linux.rootfs(ctx), linux.removePrefix("/"))

    fun linuxPath(ctx: Context, file: File) =
        "/" + file.absolutePath.removePrefix(Linux.rootfs(ctx).absolutePath).trimStart('/')

    private fun workDir(t: Target) = "${t.dir}/work"
    private fun manifestIn(ctx: Context, t: Target) = host(ctx, "${workDir(t)}/AndroidManifest.xml")

    private val componentKinds = setOf("activity", "activity-alias", "service", "receiver", "provider")

    suspend fun sh(
        ctx: Context,
        cmd: String,
        timeoutMs: Long = 5 * 60_000L,
        log: (String) -> Unit = {},
    ): Termux.Result = Linux.run(ctx, cmd, timeoutMs = timeoutMs, onOutput = { line -> log(line) })

    private fun copy(from: File, to: File) {
        to.parentFile?.mkdirs()
        from.inputStream().use { input -> to.outputStream().use { input.copyTo(it, 1 shl 16) } }
    }

    // ---- 1) target ----

    /** All APK paths of [pkg]: pm path, the package manager itself, and split files on disk. */
    private suspend fun resolvePaths(ctx: Context, pkg: String, log: (String) -> Unit): List<String> {
        val found = LinkedHashSet<String>()
        runCatching {
            val r = sh(ctx, "pm path '$pkg' 2>/dev/null", 60_000L)
            r.stdout.lines().map { it.trim() }.filter { it.startsWith("package:") }
                .forEach { found += it.removePrefix("package:").trim() }
            if (found.isNotEmpty()) log("pm path found ${found.size} APK file(s)")
        }
        runCatching {
            val pm = ctx.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
            }
            info.applicationInfo?.sourceDir?.let { found += it }
            info.applicationInfo?.splitSourceDirs?.forEach { found += it }
        }
        runCatching {
            val r = sh(ctx, "ls -1 /data/app/*/*${pkg}-*/split_config.* /data/app/*/${pkg}*/split_config.* 2>/dev/null", 60_000L)
            r.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { found += it }
        }
        val existing = found.filter { File(it).exists() }
        val base = existing.firstOrNull { it.endsWith("base.apk") } ?: existing.firstOrNull()
        return (listOfNotNull(base) + existing.filter { it != base }).distinct()
    }

    /** Copies the app's APKs into a fresh session folder, merges them and records the version. */
    suspend fun pull(ctx: Context, pkg: String, label: String, log: (String) -> Unit): Target {
        if (!Linux.installed(ctx)) throw HackFail("The built-in Linux isn't installed, so the tools can't run.")
        val paths = resolvePaths(ctx, pkg, log)
        if (paths.isEmpty()) throw HackFail("Couldn't find the APK of $pkg (the pull failed).")
        val stamp = SimpleDateFormat("yyMMdd-HHmmss", Locale.US).format(Date())
        val dir = "$BASE/$pkg-$stamp"
        val originalDir = "$dir/original"
        host(ctx, originalDir).mkdirs()
        val base = paths.first()
        copy(File(base), host(ctx, "$originalDir/base.apk"))
        val splits = paths.drop(1)
        splits.forEachIndexed { i, p -> copy(File(p), host(ctx, "$originalDir/split_$i-${File(p).name}")) }
        log("Copied ${1 + splits.size} file(s) into original/ (the originals stay untouched)")
        return finish(ctx, pkg, label, dir, originalDir, splits.size, log)
    }

    /** Uses an APK the person picked from their files instead of an installed app. */
    suspend fun adopt(ctx: Context, picked: File, log: (String) -> Unit): Target {
        if (!Linux.installed(ctx)) throw HackFail("The built-in Linux isn't installed, so the tools can't run.")
        val tmp = "$PICKED/stage.apk"
        copy(picked, host(ctx, tmp))
        val badging = sh(ctx, "aapt2 dump badging '$tmp'", 120_000L).stdout
        val pkg = Regex("""package: name='([^']*)'""").find(badging)?.groupValues?.get(1)
            ?: throw HackFail("That file isn't an APK (aapt2 couldn't read it).")
        val stamp = SimpleDateFormat("yyMMdd-HHmmss", Locale.US).format(Date())
        val dir = "$BASE/$pkg-$stamp"
        val originalDir = "$dir/original"
        host(ctx, originalDir).mkdirs()
        copy(picked, host(ctx, "$originalDir/base.apk"))
        log("Copied the picked APK into original/ (the original stays untouched)")
        return finish(ctx, pkg, picked.nameWithoutExtension, dir, originalDir, 0, log)
    }

    private suspend fun finish(
        ctx: Context,
        pkg: String,
        label: String,
        dir: String,
        originalDir: String,
        splitCount: Int,
        log: (String) -> Unit,
    ): Target {
        val merged = "$dir/merged.apk"
        if (splitCount > 0) {
            log("Merging $splitCount split APK(s) into one file")
            var ok = sh(ctx, "apkeditor m -i '$originalDir' -o '$merged'", 10 * 60_000L, log).exitCode == 0
            if (!ok || !host(ctx, merged).exists()) {
                val jar = firstExisting(ctx, listOf("/opt/APKEditor.jar", "/opt/apkeditor/APKEditor.jar"))
                if (jar != null) {
                    log("apkeditor command missing; using $jar")
                    ok = sh(ctx, "java -jar '$jar' m -i '$originalDir' -o '$merged'", 10 * 60_000L, log).exitCode == 0
                }
            }
            if (!ok || !host(ctx, merged).exists()) throw HackFail("Merging the split APKs failed.")
        } else {
            copy(host(ctx, "$originalDir/base.apk"), host(ctx, merged))
        }

        val badging = sh(ctx, "aapt2 dump badging '$originalDir/base.apk'", 180_000L).stdout
        val code = Regex("""versionCode='([^']*)'""").find(badging)?.groupValues?.get(1)
            ?: throw HackFail("The pull failed: aapt2 couldn't read the original APK.")
        val name = Regex("""versionName='([^']*)'""").find(badging)?.groupValues?.get(1).orEmpty()
        log("Original recorded: $pkg v$name ($code)")
        host(ctx, "$dir/AndroidManifest.xml").writeText(
            "package=$pkg\nlabel=$label\nversionCode=$code\nversionName=$name\n",
        )
        return Target(pkg, label, dir, originalDir, merged, code, name)
    }

    private fun firstExisting(ctx: Context, paths: List<String>): String? = paths.firstOrNull { host(ctx, it).exists() }

    /** Reopens a saved session folder (SharedPreferences remembers the last one). */
    suspend fun resume(ctx: Context, dir: String): Target? {
        val info = host(ctx, "$dir/AndroidManifest.xml")
        if (!info.exists()) return null
        val map = info.readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val pkg = map["package"] ?: return null
        if (!host(ctx, "$dir/merged.apk").exists()) return null
        return Target(pkg, map["label"].orEmpty(), dir, "$dir/original", "$dir/merged.apk", map["versionCode"].orEmpty(), map["versionName"].orEmpty())
    }

    // ---- 2) read-only recon ----

    /** Reads the merged manifest, certificates and libraries without changing anything. */
    suspend fun recon(ctx: Context, t: Target, log: (String) -> Unit): Recon {
        val apk = host(ctx, t.mergedApk)
        if (!apk.exists()) throw HackFail("The merged APK is missing — pull the app again.")
        log("Reading the manifest of ${t.packageName}")
        val tree = sh(ctx, "aapt2 dump xmltree --file AndroidManifest.xml '${t.mergedApk}'", 5 * 60_000L).stdout
        if (tree.isBlank()) throw HackFail("Couldn't read the merged manifest (aapt2 gave no output).")

        val components = mutableListOf<Component>()
        val permissions = mutableListOf<String>()
        val byName = mutableMapOf<String, MutableList<Component>>()
        var debuggable = false
        var backup = false
        var cleartext = false
        var current = ""
        var pending: Component? = null
        val pkgName = Regex("""A: package="([^"]*)"""").find(tree)?.groupValues?.get(1).orEmpty()
        for (raw in tree.lines()) {
            Regex("""^\s*E: ([\w.-]+)""").find(raw)?.let { m ->
                current = m.groupValues[1]
                if (current in componentKinds) pending = Component(current, "", false)
                return@let
            }
            val name = Regex("""A: (?:android:)?name\(0x[0-9a-f]+\)="([^"]*)"""").find(raw)?.groupValues?.get(1)
            val bool = Regex("""A: android:([A-Za-z]+)\(0x[0-9a-f]+\)=\(type 0x12\)(0x[0-9a-f]+)""").find(raw)
            if (bool != null) {
                val on = !bool.groupValues[2].endsWith("0") || bool.groupValues[2] == "0x0"
                when (bool.groupValues[1]) {
                    "debuggable" -> debuggable = on
                    "allowBackup" -> backup = on
                    "usesCleartextTraffic" -> cleartext = on
                    "exported" -> pending?.let { p ->
                        val fixed = p.copy(exported = on)
                        components.remove(p)
                        components += fixed
                        pending = fixed
                    }
                }
            }
            if (current.startsWith("uses-permission") && name != null) permissions += name
            if (current in componentKinds && name != null && pending != null) {
                val fixed = pending!!.copy(name = name)
                components.remove(pending)
                components += fixed
                pending = fixed
                byName.getOrPut(current) { mutableListOf() } += fixed
            }
        }

        val certs = sh(ctx, "apksigner verify --print-certs '${t.mergedApk}'", 3 * 60_000L)
            .stdout.lines().map { it.trim() }.filter { it.contains("certificate", true) || it.contains("SHA-256", true) }
        if (certs.isEmpty()) log("apksigner printed no certificate info (the APK may be unsigned or signed with v1 only).")

        val libs = runCatching {
            ZipFile(apk).use { z -> z.entries().toList().map { it.name }.filter { it.endsWith(".so") }.sorted() }
        }.getOrDefault(emptyList())

        val flags = findFlags(apk)
        flags.forEach { f -> log((if (f.found) "⚠ " else "✓ ") + f.name + (if (f.found) " — " + f.evidence else "")) }
        return Recon(
            packageName = if (pkgName.isBlank()) t.packageName else pkgName,
            versionName = t.versionName,
            versionCode = t.versionCode,
            debuggable = debuggable,
            allowBackup = backup,
            cleartext = cleartext,
            components = components,
            permissions = permissions.distinct(),
            certificates = certs,
            nativeLibs = libs,
            flags = flags,
        )
    }

    /** Looks for the usual reason an app is worth patching, and says where each sign was found. */
    private fun findFlags(apk: File): List<Flag> {
        val needles = linkedMapOf(
            "Root detection" to listOf("/system/bin/su", "Superuser.apk", "test-keys", "magisk", "ro.debuggable"),
            "Emulator detection" to listOf("goldfish", "genymotion", "ro.kernel.qemu", "isEmulator", "blueStacks"),
            "SSL pinning" to listOf("CertificatePinner", "X509TrustManager", "pin-set", "network_security_config"),
            "Play Integrity / SafetyNet" to listOf("SafetyNet", "IntegrityManager", "play.core.integrity", "attest"),
            "PairIP" to listOf("pairipcore", "pairip"),
            "Server-side licence check" to listOf("LicenseChecker", "licensing", "isLicensed", "verifyPurchase", "billing"),
        )
        val hits = mutableMapOf<String, MutableSet<String>>()
        runCatching {
            ZipFile(apk).use { z ->
                for (e in z.entries()) {
                    val n = e.name
                    val textish = n.endsWith(".xml") || n.startsWith("assets/") || n.endsWith(".so") || n.endsWith(".dex") || n.endsWith(".json")
                    if (!textish || e.size > 8L * 1024 * 1024) continue
                    val text = runCatching { z.getInputStream(e).use { it.readBytes().toString(Charsets.ISO_8859_1) } }.getOrNull() ?: continue
                    needles.forEach { (kind, list) ->
                        if (list.any { text.contains(it) }) hits.getOrPut(kind) { linkedSetOf() } += n
                    }
                    // The PairIP licence blob is a top-level asset literally named V3.
                    if (n == "assets/V3") hits.getOrPut("PairIP") { linkedSetOf() } += n
                }
            }
        }
        return needles.keys.map { kind ->
            val where = hits[kind].orEmpty()
            Flag(kind, where.isNotEmpty(), where.take(3).joinToString(", "))
        }
    }

    // ---- 3) analyse ----

    /** Unpacks the app for reading (resources + smali), reads it with jadx, and scans it for secrets. */
    suspend fun analyse(ctx: Context, t: Target, log: (String) -> Unit): String {
        host(ctx, workDir(t)).mkdirs()
        log("Unpacking resources and smali with apktool into work/")
        val dec = sh(ctx, "apktool d -f '${t.mergedApk}' -o '${workDir(t)}'", 20 * 60_000L, log)
        if (dec.exitCode != 0 || !manifestIn(ctx, t).exists()) {
            log("apktool failed: ${dec.stderr.takeLast(300)}")
            throw HackFail("Unpacking failed — there's nothing to patch.")
        }
        log("unpacked: ${manifestIn(ctx, t).parentFile?.listFiles()?.size ?: 0} entries in work/")

        var secrets = ""
        val jadx = sh(ctx, "jadx -d '${t.dir}/java' '${t.mergedApk}'", 20 * 60_000L, log)
        val javaFiles = host(ctx, "${t.dir}/java").walkTopDown().count { it.isFile && it.name.endsWith(".java") }
        if (jadx.exitCode != 0 || javaFiles == 0) {
            log("jadx gave no readable code; converting the dex files with dex2jar instead")
            val jar = "${t.dir}/app.jar"
            sh(ctx, "d2j-dex2jar.sh -f '${t.mergedApk}' -o '$jar'", 20 * 60_000L, log)
            if (host(ctx, jar).exists()) log("dex2jar wrote app.jar (${host(ctx, jar).length() / 1024} KB) — read it on a computer.")
        } else {
            log("jadx wrote $javaFiles java file(s) into java/ for reading")
        }

        log("Scanning for secrets with apkleaks")
        val out = "${t.dir}/secrets.txt"
        sh(ctx, "apkleaks -f '${t.mergedApk}' -o '$out'", 20 * 60_000L, log)
        val f = host(ctx, out)
        secrets = if (f.exists()) f.readText().take(20_000) else ""
        if (secrets.isBlank()) log("apkleaks found nothing (or wrote nothing) — see the log above.")
        return secrets
    }

    // ---- 4) patches ----

    private val boolMethod = Regex("""(?m)^\.method\s+(?:public|protected)[^\n(]*\s([A-Za-z0-9_$]+)\(\)Z\s*$""")

    /** Builds the file changes of a patch, without writing anything yet. */
    suspend fun patch(ctx: Context, t: Target, id: String, opts: PatchOptions, log: (String) -> Unit): List<Edit> {
        val man = manifestIn(ctx, t)
        if (!man.exists()) throw HackFail("Run Analyse first: the app isn't unpacked yet.")
        val manPath = "${workDir(t)}/AndroidManifest.xml"
        val manText = man.readText()
        val edits = mutableListOf<Edit>()

        fun withApplicationAttr(attr: String): String {
            if (manText.contains(attr.substringBefore('='))) return manText
            val at = Regex("""<application\b""").find(manText) ?: throw HackFail("No <application> tag in the manifest.")
            return manText.substring(0, at.range.last + 1) + " $attr" + manText.substring(at.range.last + 1)
        }

        when (id) {
            "DEBUGGABLE" -> {
                val next = withApplicationAttr("""android:debuggable="true"""")
                if (next == manText) log("The app is already debuggable.") else edits += Edit(manPath, manText, next)
            }
            "TRUST_CA" -> {
                val nscPath = "${workDir(t)}/res/xml/arin_nsc.xml"
                val nsc = """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </base-config>
    <debug-overrides>
        <trust-anchors>
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
"""
                val hostNsc = host(ctx, nscPath)
                edits += Edit(nscPath, if (hostNsc.exists()) hostNsc.readText() else "", nsc, newFile = !hostNsc.exists())
                val next = withApplicationAttr("""android:networkSecurityConfig="@xml/arin_nsc"""")
                if (next != manText) edits += Edit(manPath, manText, next)
            }
            "PREMIUM" -> {
                val re = Regex("(?i)is(pro|premium|vip|subscri|purchas)|has(Pro|Premium|Purchas)")
                val body = "\n    .locals 1\n\n    const/4 v0, 0x1\n\n    return v0\n\n"
                var found = 0
                workDirFiles(ctx, t).forEach { f ->
                    val text = f.readText()
                    val methods = boolMethod.findAll(text).filter { re.containsMatchIn(it.groupValues[1]) }.toList()
                    if (methods.isEmpty()) return@forEach
                    var out = text
                    methods.reversed().forEach { m ->
                        val end = out.indexOf(".end method", m.range.last)
                        val head = out.indexOf('\n', m.range.last)
                        if (end < 0 || head < 0) return@forEach
                        out = out.substring(0, head + 1) + body + out.substring(end)
                        found++
                        log("→ ${f.name}: ${m.groupValues[1]}() forced to true")
                    }
                    edits += Edit(linuxPath(ctx, f), text, out)
                }
                if (found == 0) throw HackFail("No public/protected isX()/hasX() boolean methods found.")
                log("$found method(s) in ${edits.size} smali file(s) will return true")
            }
            "ADS" -> {
                val adClasses = listOf(
                    "Lcom/google/android/gms/ads/MobileAds;",
                    "Lcom/applovin/",
                    "Lcom/unity3d/ads/",
                    "Lcom/unity3d/services/",
                    "Lcom/ironsource/",
                )
                workDirFiles(ctx, t).filter { it.name.contains("App", true) }.forEach { f ->
                    val text = f.readText()
                    if (adClasses.none { text.contains(it) }) return@forEach
                    val out = text.lines().joinToString("\n") { line ->
                        val hit = adClasses.any { line.contains(it) } && (line.contains("->initialize(") || line.contains("->init(") || line.contains("->start("))
                        if (hit && !line.trim().startsWith("#")) "# $line" else line
                    }
                    if (out != text) {
                        edits += Edit(linuxPath(ctx, f), text, out)
                        log("→ ${f.name}: ad SDK start commented out")
                    }
                }
                if (edits.isEmpty()) throw HackFail("No ad SDK start-up calls found in smali.")
            }
            "UPDATE" -> {
                val re = Regex("(?i)(is|has|needs)?update")
                workDirFiles(ctx, t).filter { it.name.contains("update", true) }.forEach { f ->
                    val text = f.readText()
                    val methods = boolMethod.findAll(text).filter { re.containsMatchIn(it.groupValues[1]) }.toList()
                    if (methods.isEmpty()) return@forEach
                    var out = text
                    methods.reversed().forEach { m ->
                        val end = out.indexOf(".end method", m.range.last)
                        val head = out.indexOf('\n', m.range.last)
                        if (end < 0 || head < 0) return@forEach
                        out = out.substring(0, head + 1) + "\n    .locals 1\n\n    const/4 v0, 0x0\n\n    return v0\n\n" + out.substring(end)
                        log("→ ${f.name}: ${m.groupValues[1]}() now false")
                    }
                    edits += Edit(linuxPath(ctx, f), text, out)
                }
                // Update checks opened from a browser deep link.
                val blocks = Regex("""(?s)[ \t]*<intent-filter>.*?</intent-filter>""").findAll(manText)
                    .filter { it.value.contains("UPDATE", true) }.toList()
                if (blocks.isNotEmpty()) {
                    var out = manText
                    blocks.reversed().forEach { b ->
                        val commented = b.value.lines().joinToString("\n") { line ->
                            if (line.isBlank()) line else "<!-- " + line.trimStart() + " -->"
                        }
                        out = out.substring(0, b.range.first) + commented + out.substring(b.range.last + 1)
                        log("→ manifest: update intent filter commented out")
                    }
                    edits += Edit(manPath, manText, out)
                }
                if (edits.isEmpty()) throw HackFail("No update check found.")
            }
            "RENAME" -> {
                val newPkg = t.packageName + ".mod"
                val out = manText.replace(Regex("""package="[^"]*""""), """package="$newPkg"""")
                edits += Edit(manPath, manText, out)
                val yml = host(ctx, "${workDir(t)}/apktool.yml")
                if (yml.exists()) {
                    val y = yml.readText()
                    val ny = if (Regex("""(?m)^applicationId:""").containsMatchIn(y)) {
                        y.replace(Regex("""(?m)^applicationId:.*$"""), "applicationId: $newPkg")
                    } else y + "\napplicationId: $newPkg\n"
                    edits += Edit(linuxPath(ctx, yml), y, ny)
                }
                log("Package becomes $newPkg (installs next to the original)")
            }
            "NAME_ICON" -> {
                val label = opts.label.ifBlank { t.label }
                val out = manText.replace(Regex("""android:label="[^"]*""""), """android:label="$label"""")
                if (out != manText) edits += Edit(manPath, manText, out)
                val icon = opts.iconBytes
                if (icon != null) {
                    val mipmaps = host(ctx, "${workDir(t)}/res").walkTopDown()
                        .filter { it.isFile && it.parentFile?.name?.startsWith("mipmap") == true && it.name.endsWith(".png") &&
                            (it.name.startsWith("ic_launcher") || it.name.startsWith("ic_launcher_round") || it.name.contains("ic_launcher")) }
                        .toList()
                    if (mipmaps.isEmpty()) log("No launcher icons found in res/mipmap-* — only the name was changed.")
                    mipmaps.forEach { f ->
                        edits += Edit(linuxPath(ctx, f), "", "image ${icon.size / 1024} KB", bytes = icon, newFile = false)
                        log("→ ${f.parentFile?.name}/${f.name} replaced")
                    }
                }
                log("Name becomes \"$label\"")
            }
            "PERMISSIONS" -> {
                var out = manText
                opts.removePermissions.forEach { p ->
                    val line = Regex("""[ \t]*<uses-permission[^>]*name="\Q$p\E"[^>]*/>\n?""")
                    if (line.containsMatchIn(out)) {
                        out = line.replace(out, "")
                        log("→ removed $p")
                    }
                }
                if (out == manText) throw HackFail("Tick at least one permission.")
                edits += Edit(manPath, manText, out)
            }
            else -> throw HackFail("Unknown patch $id")
        }
        return edits
    }

    private fun workDirFiles(ctx: Context, t: Target): List<File> =
        host(ctx, workDir(t)).walkTopDown().filter { it.isFile && it.name.endsWith(".smali") }.toList()

    /** The permissions currently in the unpacked manifest, for the strip-permissions toggles. */
    fun permissionList(ctx: Context, t: Target): List<String> {
        val man = manifestIn(ctx, t)
        if (!man.exists()) return emptyList()
        return Regex("""<uses-permission[^>]*name="([^"]*)"""").findAll(man.readText())
            .map { it.groupValues[1] }.distinct().sorted().toList()
    }

    /** Writes the changes, keeping a copy of every file first so it can be reverted. */
    fun applyEdits(ctx: Context, t: Target, title: String, edits: List<Edit>, applied: MutableList<Applied>) {
        val backupDir = host(ctx, "${t.dir}/.backup")
        edits.forEachIndexed { i, e ->
            val f = host(ctx, e.linuxPath)
            if (f.exists()) {
                val backup = File(backupDir, "${System.currentTimeMillis()}-$i-${f.name}")
                backup.parentFile?.mkdirs()
                copy(f, backup)
                applied += Applied(e.linuxPath, linuxPath(ctx, backup), title)
            }
            f.parentFile?.mkdirs()
            val bytes = e.bytes
            if (bytes != null) f.writeBytes(bytes) else f.writeText(e.new)
        }
    }

    /** Puts the files of the last applied patch back. */
    fun revertLast(ctx: Context, applied: MutableList<Applied>): String? {
        val group = applied.map { it.title }.lastOrNull() ?: return null
        applied.filter { it.title == group }.reversed().forEach { a ->
            val backup = host(ctx, a.backup)
            if (backup.exists()) copy(backup, host(ctx, a.linuxPath))
        }
        applied.removeAll { it.title == group }
        return group
    }

    // ---- 5) rebuild, sign, verify ----

    suspend fun rebuild(ctx: Context, t: Target, log: (String) -> Unit): File {
        if (!manifestIn(ctx, t).exists()) throw HackFail("Run Analyse first.")
        val outDir = "${t.dir}/built"
        host(ctx, outDir).mkdirs()
        val unsigned = "$outDir/unsigned.apk"
        log("Rebuilding with apkeditor")
        var ok = sh(ctx, "apkeditor b -i '${workDir(t)}' -o '$unsigned'", 25 * 60_000L, log).exitCode == 0 && host(ctx, unsigned).exists()
        if (!ok) {
            log("apkeditor couldn't build it; trying apktool")
            ok = sh(ctx, "apktool b '${workDir(t)}' -o '$unsigned'", 25 * 60_000L, log).exitCode == 0 && host(ctx, unsigned).exists()
        }
        if (!ok) throw HackFail("The rebuild failed — see the log.")
        val aligned = "$outDir/aligned.apk"
        sh(ctx, "zipalign -p 4 '$unsigned' '$aligned'", 5 * 60_000L, log)
        val toSign = if (host(ctx, aligned).exists()) aligned else unsigned
        return sign(ctx, t, toSign, log)
    }

    suspend fun sign(ctx: Context, t: Target, apkLinux: String, log: (String) -> Unit): File {
        val expected = "${t.dir}/built/signed.apk"
        log("Signing with the shared Arin keystore")
        val r = sh(ctx, "bash ${Linux.REBUILD_SIGN_SCRIPT} '$apkLinux' '$expected'", 20 * 60_000L, log)
        val printed = r.stdout.trim().lines().lastOrNull { it.isNotBlank() }?.trim()
        val src = listOf(expected, printed.orEmpty()).filter { it.isNotBlank() }
            .map { host(ctx, it) }.firstOrNull { it.exists() }
            ?: throw HackFail("Signing failed: ${r.stderr.trim().takeLast(300)}")
        val dest = File(File(ctx.cacheDir, "patched").apply { mkdirs() }, "apphack-signed.apk")
        copy(src, dest)
        log("Signed APK ready: ${dest.length() / 1024} KB")
        return dest
    }

    /** Verifies the signature and reports the signing certificate and version. */
    suspend fun verify(ctx: Context, t: Target, log: (String) -> Unit): String {
        val signed = "${t.dir}/built/signed.apk"
        val r = sh(ctx, "apksigner verify --print-certs '$signed'", 3 * 60_000L, log)
        if (r.exitCode != 0 && r.stdout.isBlank()) throw HackFail("Verification failed: ${r.stderr.trim().takeLast(300)}")
        return "Exit ${r.exitCode}\n" + r.stdout.trim()
    }

    // ---- 6) frida lab ----

    fun hookFile(ctx: Context, name: String): File = host(ctx, "$HOOKS/$name")

    fun readHook(ctx: Context, name: String): String {
        val f = hookFile(ctx, name)
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            f.writeText(defaultHook(name))
        }
        return runCatching { f.readText() }.getOrDefault("")
    }

    fun writeHook(ctx: Context, name: String, body: String) {
        val f = hookFile(ctx, name)
        f.parentFile?.mkdirs()
        f.writeText(body)
    }

    private fun defaultHook(name: String): String = when (name) {
        "ssl_bypass.js" -> """// Trust-all TrustManager + OkHttp hostname check, for apps with SSL pinning.
Java.perform(function () {
    var X509TrustManager = Java.use('javax.net.ssl.X509TrustManager');
    var TrustAll = Java.registerClass({
        name: 'org.arin.TrustAll',
        implements: [X509TrustManager],
        methods: {
            checkClientTrusted: function (c, a) {},
            checkServerTrusted: function (c, a) {},
            getAcceptedIssuers: function () { return []; }
        }
    });
    var trustAll = [TrustAll.${'$'}new()];
    var SSLContext = Java.use('javax.net.ssl.SSLContext');
    SSLContext.init.overload('[Ljavax.net.ssl.KeyManager;', '[Ljavax.net.ssl.TrustManager;', '[Ljava.security.SecureRandom;')
        .implementation = function (km, tm, sr) { this.init(km, trustAll, sr); };
    try {
        var P = Java.use('okhttp3.CertificatePinner');
        P.check.overload('java.lang.String', 'java.util.List').implementation = function () { };
    } catch (e) {}
    console.log('[*] SSL pinning bypass active');
});
"""
        else -> """// Force the premium/subscription checks to true.
Java.perform(function () {
    Java.enumerateLoadedClasses({
        onMatch: function (name) {
            if (!/Pro|Premium|Subscri|Purchas|Licen/.test(name)) return;
            try {
                var C = Java.use(name);
                ['isPro', 'isPremium', 'isPurchased', 'isSubscribed', 'isLicensed'].forEach(function (m) {
                    if (C[m] && C[m].overloads.length === 1 && C[m].overload('').returnType.name === 'boolean') {
                        C[m].implementation = function () { console.log('[*] ' + name + '.' + m + ' -> true'); return true; };
                    }
                });
            } catch (e) {}
        },
        onComplete: function () { console.log('[*] premium hooks installed'); }
    });
});
"""
    }

    // ---- 7) tools ----

    val TOOLS = listOf("apktool", "jadx", "apkleaks", "d2j-dex2jar.sh", "frida", "objection", "mitmproxy", "aapt2", "apksigner", "zipalign", "apkeditor")

    /** Green/red dots: which tools answer `command -v`. */
    suspend fun toolsStatus(ctx: Context): Map<String, Boolean> {
        val r = sh(ctx, TOOLS.joinToString("; ") { "command -v ${it.replace(" ", "")} >/dev/null && echo 'y $it' || echo 'n $it'" }, 120_000L)
        val map = TOOLS.associateWith { false }.toMutableMap()
        r.stdout.lines().map { it.trim() }.filter { it.length > 2 }.forEach { line ->
            val on = line.startsWith("y ")
            val tool = line.substring(2).trim()
            if (tool in map) map[tool] = on
        }
        return map
    }

    private fun installCommand(tool: String): String = when (tool) {
        "apktool" -> "curl -sSLo /opt/apktool.jar https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_3.0.3.jar && " +
            "printf '#!/bin/sh\\nexec java -jar /opt/apktool.jar \"\$@\"\\n' > /usr/local/bin/apktool && chmod +x /usr/local/bin/apktool"
        "jadx" -> "curl -sSLo /tmp/jadx.zip https://github.com/skylot/jadx/releases/download/v1.5.7/jadx-1.5.7.zip && " +
            "unzip -o /tmp/jadx.zip -d /opt/jadx && ln -sf /opt/jadx/bin/jadx /usr/local/bin/jadx"
        "apkleaks" -> "pip install --upgrade apkleaks"
        "d2j-dex2jar.sh" -> "curl -sSLo /tmp/d2j.zip https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip && " +
            "unzip -o /tmp/d2j.zip -d /opt && ln -sf /opt/dex-tools-v2.4/d2j-dex2jar.sh /usr/local/bin/d2j-dex2jar.sh"
        "frida" -> "pip install --upgrade frida-tools"
        "objection" -> "pip install --upgrade objection"
        "mitmproxy" -> "pip install --upgrade mitmproxy"
        "apkeditor" -> "curl -sSLo /opt/APKEditor.jar https://github.com/REAndroid/APKEditor/releases/download/V1.4.3/APKEditor-1.4.3.jar && " +
            "printf '#!/bin/sh\\nexec java -jar /opt/APKEditor.jar \"\$@\"\\n' > /usr/local/bin/apkeditor && chmod +x /usr/local/bin/apkeditor"
        else -> "apt-get update && apt-get install -y --no-install-recommends aapt zipalign apksigner unzip curl"
    }

    suspend fun installTool(ctx: Context, tool: String, log: (String) -> Unit) {
        log("Installing $tool")
        val r = sh(ctx, "bash -c \"${installCommand(tool).replace("\"", "\\\"")}\"", 15 * 60_000L, log)
        if (r.exitCode != 0) log("$tool install exited with ${r.exitCode}: ${r.stderr.trim().takeLast(200)}")
    }
}
