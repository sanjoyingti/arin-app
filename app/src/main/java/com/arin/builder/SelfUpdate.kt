package com.arin.builder

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.arin.terminal.Linux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Guards Arin's self-updates. Android only installs an APK whose versionCode is at least the installed
 * one and whose signing certificate matches, and it reports "App not installed" for both — which looks
 * exactly like a signature error. So every self-update is numbered from a fixed floor, and the built
 * APK is checked (version number + signer) right before it is handed over:
 * the build fails loudly, with both values printed, instead of publishing something uninstallable.
 */
object SelfUpdate {

    /** Version numbers of self-updates never go below this, so the counter can't run backwards again. */
    const val MIN_VERSION_CODE = 1000

    /** SHA-256 of Arin's signing certificate — the key every Arin build is signed with. */
    const val SIGNER_SHA256 = "238cd762592ff8c82bd8209ddc041893a98fd3a5e5f2ad265e6759488430b0be"

    /** The outcome of the pre-install check. [message] always shows the version numbers and signer. */
    data class Check(
        val ok: Boolean,
        val message: String,
        val log: String,
        val versionCode: Int? = null,
        val signer: String? = null,
    )

    /** versionCode of the Arin installed on this phone, or 0 when it can't be read. */
    fun installedVersionCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }.getOrDefault(0)

    /** The next versionCode for a self-update: never below the floor, always above the installed app. */
    fun nextVersionCode(context: Context): Int = maxOf(MIN_VERSION_CODE, installedVersionCode(context) + 1)

    /** Downloads the published APK into the cache so it can be checked; null if it doesn't arrive. */
    suspend fun download(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "self-update").apply { mkdirs() }
            val out = File(dir, url.substringAfterLast('/').substringBefore('?').ifBlank { "update.apk" })
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                check(r.isSuccessful) { "HTTP ${r.code}" }
                val body = r.body ?: error("empty download")
                body.byteStream().use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
            }
            out.takeIf { it.length() > 1000 }
        }.getOrNull()
    }

    /** Checks [apk] is really a newer Arin signed with Arin's key. [expectedVersionCode] is what this build set. */
    suspend fun verify(context: Context, apk: File, expectedVersionCode: Int): Check = withContext(Dispatchers.IO) {
        val notes = StringBuilder()
        val installed = installedVersionCode(context)
        val pm = context.packageManager
        val badging = aaptBadging(context, apk, notes)
        val info = archiveInfo(pm, apk)
        val versionCode = badging?.let { Regex("""versionCode='(\d+)'""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: info?.let { versionCodeOf(it) }
        val packageName = info?.packageName
            ?: badging?.let { Regex("""package: name='([^']*)'""").find(it)?.groupValues?.get(1) }
        val signer = info?.let { signerSha256(it) }

        val problems = mutableListOf<String>()
        if (versionCode == null) problems += "Couldn't read the new APK's version number (both aapt2 and Android's package reader failed)."
        if (packageName != null && packageName != context.packageName) {
            problems += "The new APK's package is $packageName, not ${context.packageName}, so it would install as a different app."
        }
        if (versionCode != null && versionCode <= installed) {
            problems += "The new APK is versionCode $versionCode but versionCode $installed is already installed; Android refuses that."
        }
        if (expectedVersionCode > 0 && versionCode != null && versionCode != expectedVersionCode) {
            problems += "The new APK is versionCode $versionCode but this build set $expectedVersionCode."
        }
        if (signer == null) problems += "Couldn't read the new APK's signing certificate."
        if (signer != null && !signer.equals(SIGNER_SHA256, ignoreCase = true)) {
            problems += "The new APK is signed by $signer instead of Arin's key $SIGNER_SHA256; Android would refuse the update."
        }

        val values = "new versionCode ${versionCode ?: "?"} · installed versionCode $installed · signer ${signer ?: "?"}"
        val log = buildString {
            if (notes.isNotBlank()) appendLine(notes.toString().trim())
            appendLine(values)
        }
        if (problems.isEmpty()) {
            Check(true, "Checked before installing: $values", log, versionCode, signer)
        } else {
            Check(false, problems.joinToString(" ") + " ($values)", log, versionCode, signer)
        }
    }

    /** "aapt2 dump badging" inside the built-in Linux; null (with a note) when that isn't possible. */
    private suspend fun aaptBadging(context: Context, apk: File, notes: StringBuilder): String? = runCatching {
        if (!Linux.installed(context)) {
            notes.appendLine("Built-in Linux isn't installed: the version number was read with Android's package reader instead of aapt2.")
            return null
        }
        val staged = File(File(Linux.rootfs(context), "root/files").apply { mkdirs() }, "self-update-check.apk")
        apk.copyTo(staged, overwrite = true)
        val r = Linux.run(context, "aapt2 dump badging /root/files/self-update-check.apk", timeoutMs = 3 * 60_000L)
        val out = r.stdout
        if (r.exitCode != 0 || out.isBlank()) {
            notes.appendLine("aapt2 gave no output: the version number was read with Android's package reader instead.")
            return null
        }
        notes.appendLine("Version number read with aapt2 dump badging, as required.")
        out
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Int =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode

    /** SHA-256 of the APK's signer, lowercase hex; null when it can't be read. */
    @Suppress("DEPRECATION")
    private fun signerSha256(info: PackageInfo): String? = runCatching {
        val bytes = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: return@runCatching null
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrNull()
}
