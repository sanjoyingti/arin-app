@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.aiapp.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aiapp.data.HackToolsShim
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Hack Suite: analyse, scan, patch and hook an APK picked from the phone's files. */
@Composable
fun HackSuiteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val logLines = remember { mutableStateListOf<String>() }
    var apkPath by remember { mutableStateOf<String?>(null) }
    var analysis by remember { mutableStateOf<Map<String, String>?>(null) }
    var busyStage by remember { mutableStateOf<String?>(null) }
    var patchedApk by remember { mutableStateOf<File?>(null) }
    var showScripts by remember { mutableStateOf(false) }

    val addLog: (String) -> Unit = { line ->
        logLines.add(line)
        if (logLines.size > 800) logLines.removeRange(0, logLines.size - 800)
    }

    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val dest = File(File(context.cacheDir, "picked").apply { mkdirs() }, "target.apk")
            context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 16) } }
            apkPath = dest.absolutePath
            analysis = null
            patchedApk = null
            addLog("Picked APK: ${dest.name} (${dest.length() / 1024} KB)")
        }.onFailure { addLog("Couldn't copy the picked APK: ${it.message}") }
    }

    fun installPatched(f: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
            context.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { addLog("Install failed: ${it.message}") }
    }

    fun shareScript(name: String, body: String) {
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, name)
                        .putExtra(Intent.EXTRA_TEXT, body),
                    "Share $name",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun runStage(stage: String, work: (android.content.Context, String) -> Unit) {
        if (busyStage != null) { addLog("Another stage is running."); return }
        val target = apkPath
        if (target == null) { addLog("Pick an APK first."); return }
        val appContext = context.applicationContext
        busyStage = stage
        CoroutineScope(Dispatchers.IO).launch {
            addLog("--- $stage started ---")
            try {
                HackToolsShim.ensureTools(appContext, addLog)
                work(appContext, target)
                addLog("--- $stage done ---")
            } catch (e: Throwable) {
                addLog("Failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busyStage = null
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(logLines.size) { if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1) }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hack Suite") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pickApk.launch("application/vnd.android.package-archive") },
                    enabled = busyStage == null,
                ) { Text("Pick APK") }
                Text(
                    when {
                        busyStage != null -> busyStage + "…"
                        apkPath != null -> "target ready"
                        else -> "no APK selected yet"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            analysis?.forEach { (title, body) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        SelectionContainer {
                            Text(body, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            StageRow(
                title = "Recon",
                subtitle = "Package, app name, version, permissions, activities and services",
                enabled = busyStage == null && apkPath != null,
                onRun = {
                    runStage("Recon") { ctx, apk -> analysis = HackToolsShim.analyze(ctx, apk, addLog) }
                },
            )
            StageRow(
                title = "Secrets Scan",
                subtitle = "Regex scan for API keys, Firebase URLs, JWTs and bearer tokens",
                enabled = busyStage == null && apkPath != null,
                onRun = {
                    runStage("Secrets Scan") { ctx, apk ->
                        val secs = HackToolsShim.analyze(ctx, apk, addLog)
                        addLog(secs["Embedded secrets"].orEmpty())
                    }
                },
            )
            StageRow(
                title = "Vuln Scan",
                subtitle = "debuggable, allowBackup, cleartext, exported components, WebView bridges",
                enabled = busyStage == null && apkPath != null,
                onRun = {
                    runStage("Vuln Scan") { ctx, apk ->
                        val secs = HackToolsShim.analyze(ctx, apk, addLog)
                        addLog(secs["Vulnerability checklist"].orEmpty())
                    }
                },
            )
            StageRow(
                title = "Patch Forge",
                subtitle = "Ensure tools, then re-sign the APK so it installs cleanly",
                enabled = busyStage == null && apkPath != null,
                onRun = {
                    runStage("Patch Forge") { ctx, apk ->
                        val out = HackToolsShim.patchAndSign(ctx, apk, addLog)
                        patchedApk = out
                        if (out != null) addLog("Tap \"Install patched APK\" below to install it.")
                    }
                },
            )
            StageRow(
                title = "Frida Hooks",
                subtitle = "Four bundled hook scripts, ready to copy or share",
                enabled = busyStage == null,
                onRun = { showScripts = !showScripts },
            )

            patchedApk?.takeIf { it.exists() }?.let { f ->
                Button(onClick = { installPatched(f) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Install patched APK")
                }
            }

            if (showScripts) {
                Text("Frida scripts", style = MaterialTheme.typography.titleSmall)
                HackToolsShim.FRIDA_SCRIPTS.forEach { (name, body) ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(name, style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { clipboard.setText(AnnotatedString(body)); addLog("Copied $name") }) { Text("Copy") }
                                TextButton(onClick = { shareScript(name, body) }) { Text("Share") }
                            }
                            SelectionContainer {
                                Text(body.take(700), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            Text("Log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 340.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(logLines.toList()) { line ->
                    SelectionContainer {
                        Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** One stage: a card with its description and a Run button. */
@Composable
private fun StageRow(title: String, subtitle: String, enabled: Boolean, onRun: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onRun, enabled = enabled) { Text("Run") }
        }
    }
}
