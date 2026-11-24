package com.arin.rea

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel
import com.arin.mcp.Mcp
import com.arin.terminal.Linux
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private data class Workflow(val title: String, val description: String, val prompt: String)

@Composable
fun ReaWorkbenchScreen(vm: ChatViewModel, onBack: () -> Unit, onOpenChat: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf("") }
    var changeRequest by remember { mutableStateOf("") }
    var autoStatus by remember { mutableStateOf<String?>(null) }
    var launchingAuto by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var expandedAdvanced by remember { mutableStateOf(false) }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !launchingAuto) {
            launchingAuto = true
            autoStatus = "Preparing the APK and checking the complete REA toolchain…"
            scope.launch {
                try {
                    if (!Linux.installed(context) || !vm.linuxReady || Mcp.status(Mcp.REA.name) == null) {
                        autoStatus = "Setting up Ubuntu, current REA, JADX, Apktool, APKEditor and signing tools…"
                        if (!vm.linuxBusy) vm.setUpRea()
                        withTimeout(45 * 60_000L) {
                            snapshotFlow { vm.linuxBusy to (vm.linuxReady && Mcp.status(Mcp.REA.name) != null) }
                                .first { (busy, ready) -> !busy && ready }
                        }
                    }
                    autoStatus = "Importing the APK into the REA workspace…"
                    val before = vm.pending.size
                    vm.openReverseEngineering()
                    vm.attach(listOf(uri))
                    withTimeout(180_000L) {
                        snapshotFlow { vm.readingFile to vm.pending.size }
                            .first { (reading, count) -> reading == null && count > before }
                    }
                    autoStatus = "APK attached. The autonomous investigation and modification job is starting…"
                    vm.send(autoModderPrompt(changeRequest.trim()))
                    onOpenChat()
                } catch (e: Throwable) {
                    autoStatus = "Could not start: ${e.message ?: "setup or file import timed out"}. Your original APK is unchanged."
                    launchingAuto = false
                }
            }
        }
    }

    val workflows = listOf(
        Workflow("Android APK / split APK analysis", "Manifest, package, SDK, permissions, signing, DEX, classes, resources, strings, split APKs and decompilation evidence.",
            "Investigate the supplied Android APK or split-APK set at TARGET. Discover the exact connected REA tools and schemas first. Inspect package/app info, manifest, signing identity, SDK/ABI, DEX inventory, classes, methods, strings, resources and protection/packing. Use JADX/Apktool tools when available. Distinguish loader stubs from recovered app code. Preserve evidence and confidence. Do not bypass DRM, authentication, paid entitlements or access controls. TARGET: "),
        Workflow("Native libraries and binary analysis", "Inventory ELF libraries, architectures, imports, exports, strings and native call paths; use native engines only when installed and licensed.",
            "Analyze native libraries in TARGET. Inventory ELF headers, ABI, imports/exports, symbols, strings, dependencies and JNI entry points. Discover REA native-analysis providers and their real availability. Use Ghidra/Kuna/IDA/Hopper only when already installed and properly licensed. On this phone, report unavailable providers rather than pretending to run them. Produce evidence-backed findings and safe implementation options. TARGET: "),
        Workflow("Flutter / React Native / web bundles", "Identify frameworks and inspect accessible assets, JavaScript bundles, routes and application structure.",
            "Analyze TARGET for Flutter, React Native, Cordova, Electron or web-bundle structure. Identify framework and version evidence, enumerate assets and bundles, inspect source maps when present, map routes/API references only where plaintext evidence exists, and report obfuscation limits. Use available REA tools and schemas; do not invent recovered source. TARGET: "),
        Workflow("Network capture investigation", "Review supported PCAP, HAR or proxy exports; distinguish decrypted requests from TLS metadata.",
            "Analyze network capture TARGET. First identify the file format and available REA parsers. Extract HTTP request/response details only when actually present or decrypted in the supplied capture. For TLS-only traffic report SNI, endpoints, timing and byte counts as metadata only. Never infer plaintext paths, credentials, tokens or response schemas from encrypted records. Produce a host/flow table with confidence. TARGET: "),
        Workflow("Trace and recreate a feature", "Trace evidence through the app and implement an independent feature while preserving the rest of the project.",
            "Investigate the requested feature in TARGET. Trace the relevant activities, services, classes, methods, resources and data flow with exact evidence. Explain behavior and edge cases, then create an independent implementation plan for my project. Preserve unrelated functionality, licenses and attribution. Do not copy proprietary code or bypass access controls. TARGET: "),
        Workflow("GitHub tool and library research", "Search official upstream projects, check release status, licenses, dependencies and Android ARM64 compatibility.",
            "Research current GitHub projects relevant to TARGET. Search official upstream repositories and documentation; verify repository identity, recent commits/releases, license, dependencies, supported OS/CPU architecture, Android/PRoot feasibility and checksums where published. Compare alternatives and recommend only tools that can actually run in this environment. Do not execute untrusted downloads. TARGET: ")
    )

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text("REA Workbench", style = MaterialTheme.typography.titleLarge)
                Text("Integrated reverse-engineering workspace", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onOpenChat) { Text("Open REA chat") }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Toolchain and capability status", style = MaterialTheme.typography.titleMedium)
                    StatusLine("Ubuntu / PRoot", Linux.installed(context))
                    StatusLine("REA MCP connected", Mcp.status(Mcp.REA.name) != null)
                    StatusLine("Android analysis tools", Linux.installed(context) && vm.linuxReady)
                    Text("The setup routine installs the latest published rea-agents package, plus the Android helpers configured by this project. A connected MCP server does not mean every optional native engine is installed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { vm.setUpRea() }, enabled = !vm.linuxBusy, modifier = Modifier.weight(1f)) {
                            if (vm.linuxBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Install / repair full REA")
                        }
                        OutlinedButton(onClick = {
                            if (!checking) {
                                checking = true
                                diagnostics = "Checking…"
                                scope.launch {
                                    diagnostics = runCatching {
                                        if (!Linux.installed(context)) "Ubuntu is not installed. Use Install / repair full REA first."
                                        else Linux.run(context, "echo '=== REA ==='; (rea --version 2>&1 || true); echo '=== Node ==='; (node -v 2>&1 || true); echo '=== Java ==='; (java -version 2>&1 | head -1 || true); echo '=== JADX MCP ==='; ls -lh /opt/rea-android/jadx-headless-mcp-*.jar 2>&1 || true; echo '=== Apktool ==='; (apktool --version 2>&1 || true); echo '=== APKEditor ==='; ls -lh /opt/rea-android/APKEditor.jar 2>&1 || true; echo '=== Signer ==='; ls -lh /opt/rea-android/uber-apk-signer.jar 2>&1 || true; echo '=== Signing key ==='; test -f /root/.arin/arin-rebuild.keystore && echo present || echo missing; echo '=== REA tools env ==='; cat /root/.arin/rea.env 2>/dev/null || true", timeoutMs = 90_000).combined()
                                    }.getOrElse { "Capability check failed: ${it.message}" }
                                    checking = false
                                }
                            }
                        }, enabled = !checking && Linux.installed(context), modifier = Modifier.weight(1f)) {
                            if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Check tools")
                        }
                    }
                    diagnostics?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (vm.linuxLog.isNotEmpty()) {
                        Text("Latest setup log", style = MaterialTheme.typography.labelLarge)
                        vm.linuxLog.takeLast(8).forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Auto APK Modder", style = MaterialTheme.typography.titleLarge)
                    Text("Attach one APK, describe the change in plain language, and let the agent investigate, plan, patch, rebuild, sign and validate it. The original file must remain untouched.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = changeRequest,
                        onValueChange = { changeRequest = it },
                        label = { Text("What should be changed?") },
                        placeholder = { Text("Example: Add a dark theme and a settings option. Keep all other features unchanged.") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                        enabled = !launchingAuto && changeRequest.isNotBlank() && !vm.linuxBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (launchingAuto) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Attach APK and start automatically")
                    }
                    autoStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                    Text("The agent must prove every stage with tool output. Protected code, inaccessible private servers, incompatible native code or missing build dependencies may prevent a finished APK. No success message is shown without a verified output.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text("Target path or investigation subject") }, supportingText = { Text("Optional for guided investigations. Example: /root/files/sample.apk") }, modifier = Modifier.fillMaxWidth())
            Text("Advanced investigations", style = MaterialTheme.typography.titleMedium)
            workflows.forEach { workflow ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(workflow.title, style = MaterialTheme.typography.titleSmall)
                        Text(workflow.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = {
                            vm.openReverseEngineering()
                            vm.send(workflow.prompt + target.trim().ifBlank { "the attached file or selected target" } + "\n\nRequirements: discover exact tool names and schemas before use; cite evidence and confidence; distinguish facts, inference and unknowns; check current official GitHub documentation when relevant; do not claim unsupported capabilities or successful operations without tool output.")
                            onOpenChat()
                        }, modifier = Modifier.fillMaxWidth()) { Text("Start investigation") }
                    }
                }
            }

            TextButton(onClick = { expandedAdvanced = !expandedAdvanced }) {
                Text(if (expandedAdvanced) "Hide phone limitations" else "Show phone limitations")
            }
            if (expandedAdvanced) {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("What this phone can and cannot do", style = MaterialTheme.typography.titleSmall)
                        Text("The built-in Ubuntu/PRoot environment can run REA, Java-based APK inspection helpers and some local rebuild/sign operations when storage, memory and CPU permit.", style = MaterialTheme.typography.bodySmall)
                        Text("Ghidra, IDA, Hopper, JEB, Frida, ADB-connected device control, kernel instrumentation and some native/runtime workflows may require separately installed licensed providers, root, a connected device or a desktop/cloud worker. The app must check availability rather than silently pretend these tools exist.", style = MaterialTheme.typography.bodySmall)
                        Text("APK signing uses Arin's own key. It normally cannot update an app signed by its original developer unless the same signing identity is available.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(if (ready) "Available" else "Not verified", color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

private fun autoModderPrompt(request: String): String = """
AUTONOMOUS APK MODIFICATION JOB — user supplied this APK and requested the change below.
REQUESTED CHANGE:
$request

Work autonomously. Do not ask the user to operate a terminal, manually edit files, or choose technical tools unless an essential decision is genuinely missing.

PHASE A — INTAKE AND SAFETY
1. Locate the attached APK in the Linux workspace. Record original SHA-256, size and path. Never modify or overwrite the original. Create a unique job directory and checkpoints.
2. Discover the exact available REA MCP tool names and input schemas before calling them. Also inspect the installed local helpers and their documented usage. Never guess tool names or command-line flags.
3. Confirm the requested change is within the user's authority. Do not bypass DRM, licensing, paid entitlements, authentication, anti-cheat, certificate pinning, security controls, or extract credentials/secrets. If the request requires such action, stop that part and offer a legitimate alternative.

PHASE B — REVERSE ENGINEERING
4. Inspect APK package, app/version, SDK, ABIs, manifest, activities/services/receivers, permissions, signing certificate, DEX inventory, resources, strings, assets, native libraries, dependencies and protection/packing.
5. Use the available REA Android/JADX tools and Apktool/APKEditor only if installed. Distinguish genuine recovered app code from packer/loader stubs. Use smali evidence when Java decompilation fails. Report protected or missing sections honestly.
6. If helpful, research official upstream GitHub tools. Verify repository identity, release, license, supported architecture and published checksums. Do not execute untrusted scripts or binaries. Native providers such as Ghidra/IDA/Hopper/JEB must be installed and licensed separately.
7. Build a feature map and identify the smallest set of files/resources that need changing. If source recovery is incomplete, explain whether a safe resource-level patch or clean-room replacement is possible.

PHASE C — PLAN AND PATCH
8. Produce a short internal plan, then make the minimal change matching the user's request. Preserve unrelated behavior, package identity, resources and features. Keep a patch/diff and checkpoint before each risky operation.
9. Do not invent backend endpoints, API schemas, stream URLs or credentials. Do not claim private-server access from TLS metadata alone. If the change depends on inaccessible server-side functionality, stop and explain the blocker.

PHASE D — REBUILD, SIGN AND VERIFY
10. Inspect the actual APKEditor/rebuild/sign helper usage. Rebuild in a separate output directory. Run safe repair attempts for real errors, preserving logs and checkpoints; stop after three materially different failed attempts.
11. Verify the output exists, is a valid APK, has a readable manifest, expected package name, expected changed resource/code evidence, and a valid signing certificate. Compare original and output package/signature identities. Explain that Arin's signing key normally cannot update the developer-signed original installation.
12. Copy only a successfully verified APK to the supported Download/Arin output location. Report exact path, SHA-256, package name, signer, validation checks and any limitations. Never fabricate a build result or output path.

FINAL RESPONSE
Give the user a concise report: requested change, actual files changed, tests performed, APK output location and hash, install/update compatibility, and remaining blockers. If no valid APK was produced, say so and identify the last successful phase. The original APK must remain unchanged.
""".trimIndent()
