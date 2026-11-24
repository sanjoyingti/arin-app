@file:OptIn(ExperimentalMaterial3Api::class)

package com.aiapp.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.aiapp.data.HackToolsShim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Stage(val label: String) {
    Recon("Recon"),
    Secrets("Secrets Scan"),
    Vulns("Vuln Scan"),
    Patch("Patch Forge"),
    Frida("Frida Hooks"),
}

/** Hack Suite: inspect, scan and patch an APK, then attach Frida hooks. */
@Composable
fun HackSuiteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lines = remember { mutableStateListOf<String>() }
    var pickedApk by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var patchedApk by remember { mutableStateOf<File?>(null) }
    val listState = rememberLazyListState()

    fun log(msg: String) = lines.add(msg)

    // The engine writes log lines during background work; route them into our list.
    LaunchedEffect(Unit) {
        val original = HackToolsShim.log
        HackToolsShim.log = { msg -> launch { lines.add(msg); if (lines.size > 500) lines.removeAt(0) } }
    }

    BackHandler { onBack() }

    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        LaunchedEffect(uri) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val dst = File(context.cacheDir, "picked_${System.currentTimeMillis()}.apk")
                    context.contentResolver.openInputStream(uri)!!.use { i -> dst.outputStream().use { i.copyTo(it) } }
                    dst
                }.onSuccess { f ->
                    withContext(Dispatchers.Main) { pickedApk = f; patchedApk = null; analysis = emptyMap(); log("Picked APK: ${f.name} (${f.length() / 1024} KB)") }
                }.onFailure { e -> withContext(Dispatchers.Main) { log("✖ Couldn't copy the APK: ${e.message}") } }
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hack Suite") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(enabled = lines.isNotEmpty(), onClick = { lines.clear() }) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize().imePadding().navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Button(
                    onClick = { pickApk.launch("application/vnd.android.package-archive") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (pickedApk == null) "Pick APK" else "Picked: ${pickedApk!!.name}") }
            }

            items(items = Stage.entries.toList()) { stage ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stage.label, style = MaterialTheme.typography.titleSmall)
                        when (stage) {
                            Stage.Frida -> {
                                HackToolsShim.FRIDA_SCRIPTS.forEach { (name, script) ->
                                    Row {
                                        Column(Modifier.weight(1f)) {
                                            Text(name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                        }
                                        TextButton(onClick = { clipboard.setText(AnnotatedString(script)) }) { Text("Copy") }
                                        TextButton(onClick = {
                                            val send = Intent(Intent.ACTION_SEND).setType("text/javascript")
                                                .putExtra(Intent.EXTRA_SUBJECT, name)
                                                .putExtra(Intent.EXTRA_TEXT, script)
                                            context.startActivity(Intent.createChooser(send, "Share $name"))
                                        }) { Text("Share") }
                                    }
                                }
                            }
                            Stage.Patch -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        val apk = pickedApk
                                        if (apk == null) log("Pick an APK first."); else {
                                            busy = true
                                            LaunchedEffect(apk) {
                                                withContext(Dispatchers.IO) {
                                                    HackToolsShim.ensureTools(context)
                                                    val sign = HackToolsShim.signerJar(context)
                                                    val outDir = File(context.cacheDir, "patched").apply { mkdirs() }
                                                    val dest = File(outDir, "patched-${apk.nameWithoutExtension}-signed.apk")
                                                    val proc = ProcessBuilder(
                                                        "bash", "-c",
                                                        "java -jar '${sign.absolutePath}' -a '${apk.absolutePath}' --out '${outDir.absolutePath}'",
                                                    ).start().inputStream.bufferedReader().readText()
                                                    log(proc.lines().lastOrNull { it.isNotBlank() }.orEmpty())
                                                    val result = File(outDir, apk.nameWithoutExtension + "-aligned-signed.apk")
                                                    withContext(Dispatchers.Main) {
                                                        patchedApk = if (result.exists()) result else outDir.listFiles { f -> f.name.endsWith(".apk") }?.firstOrNull()
                                                        log(if (patchedApk != null) "✔ Patch Forge done: ${patchedApk!!.name}" else "✖ Patching produced no APK")
                                                        busy = false
                                                    }
                                                }
                                            }
                                        }
                                    }, enabled = !busy) { Text("Run") }
                                    patchedApk?.let { apk ->
                                        Button(onClick = {
                                            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
                                            val install = Intent(Intent.ACTION_INSTALL_PACKAGE)
                                                .setDataAndType(uri, "application/vnd.android.package-archive")
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                            runCatching { context.startActivity(install) }.onFailure { log("✖ Installer failed: ${it.message}") }
                                        }) { Text("Install patched APK") }
                                    }
                                }
                            }
                            else -> {
                                Button(
                                    onClick = {
                                        val apk = pickedApk
                                        if (apk == null) log("Pick an APK first.") else {
                                            busy = true
                                            LaunchedEffect(apk, stage) {
                                                withContext(Dispatchers.IO) {
                                                    HackToolsShim.ensureTools(context)
                                                    val result = runCatching { HackToolsShim.analyze(apk.absolutePath) }
                                                    withContext(Dispatchers.Main) {
                                                        analysis = analysis + result.getOrElse { mapOf("error" to (it.message ?: "analysis failed")) }
                                                        analysis.entries.forEach { (k, v) -> log("$k:\n$v") }
                                                        busy = false
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    enabled = !busy,
                                ) { Text(if (busy) "Running…" else "Run") }
                                // Show this stage's result inline.
                                analysis.entries
                                    .filter { (k, _) ->
                                        (stage == Stage.Recon && k in listOf("package", "app_name", "version", "permissions", "activities", "services", "note", "error")) ||
                                            (stage == Stage.Secrets && k == "secrets") ||
                                            (stage == Stage.Vulns && k == "vulnerabilities")
                                    }
                                    .forEach { (_, v) ->
                                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
                                            Text(v, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                                        }
                                    }
                            }
                        }
                    }
                }
            }

            if (lines.isNotEmpty()) {
                item {
                    Text("Log", style = MaterialTheme.typography.titleSmall)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            linesIndexed(lines) { idx ->
                                itemsIndexed(listOf(lines[idx])) { _, _ ->
                                    Text(
                                        lines[idx],
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
