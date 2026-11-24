package com.arin.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Apps installed on this phone, so their APK can be attached to a reverse-engineering (REA) chat. */
object InstalledApps {
    data class App(
        val label: String,
        val packageName: String,
        val version: String,
        val system: Boolean,
        /** Path of the main APK (base.apk). */
        val apk: String,
        /** Extra split APKs (language, screen size, native code), if the app was installed as a bundle. */
        val splits: List<String>,
        val sizeBytes: Long,
    )

    suspend fun list(context: Context, includeSystem: Boolean): List<App> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val infos = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getInstalledApplications(0)
        }
        infos.asSequence()
            .filter { it.packageName != context.packageName }
            .map { ai ->
                val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val version = runCatching {
                    if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(ai.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
                    else @Suppress("DEPRECATION") pm.getPackageInfo(ai.packageName, 0).versionName
                }.getOrNull().orEmpty()
                val splits = ai.splitSourceDirs?.toList().orEmpty()
                App(
                    label = pm.getApplicationLabel(ai).toString(),
                    packageName = ai.packageName,
                    version = version,
                    system = system,
                    apk = ai.sourceDir,
                    splits = splits,
                    sizeBytes = (listOf(ai.sourceDir) + splits).sumOf { File(it).length() },
                )
            }
            .filter { includeSystem || !it.system }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * Copies the app's APK (and its split APKs, if any) into the app cache under readable names, ready to
     * attach. Returns the copied files: the main APK first.
     */
    suspend fun copyApks(context: Context, app: App): List<File> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "installed-apks").apply { deleteRecursively(); mkdirs() }
        val base = app.label.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { app.packageName }
        val v = app.version.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        val out = mutableListOf<File>()
        val main = File(dir, "$base-${app.packageName}${if (v.isNotBlank()) "-v$v" else ""}.apk")
        File(app.apk).inputStream().use { input -> main.outputStream().use { input.copyTo(it, 1 shl 16) } }
        out += main
        app.splits.forEach { s ->
            val f = File(dir, "$base-${File(s).name}")
            File(s).inputStream().use { input -> f.outputStream().use { input.copyTo(it, 1 shl 16) } }
            out += f
        }
        out
    }
}
