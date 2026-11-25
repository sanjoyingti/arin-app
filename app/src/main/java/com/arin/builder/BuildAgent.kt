package com.arin.builder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BuildRequest(
    /** The new app's description, or the change to make to [BuildRunner.project]. */
    val description: String,
    val appName: String,
    val provider: com.arin.llm.Provider,
    val model: String,
    val apiKey: String,
    val effort: Effort,
    val githubToken: String,
    /** Let the agent download files from sites outside the built-in safe list. */
    val allowOtherSites: Boolean,
    /** Continue this project instead of starting a new one. */
    val project: Project? = null,
    /** An uploaded Android Studio project (.zip) to build instead of writing a new app. */
    val zipPath: String? = null,
    val zipName: String? = null,
    /** Files attached to this request: shown to the model, and images/fonts/sounds added to the app. */
    val attachments: List<com.arin.data.Attachment> = emptyList(),
    /** Pick up where the last build of [project] stopped instead of starting over. */
    val resume: Boolean = false,
    /** Change Arin itself: edit [selfRepo] on GitHub and build it with Arin's own workflow. */
    val selfUpdate: Boolean = false,
    /** Arin's source: owner/repo and branch. */
    val selfRepo: String = "",
    val selfBranch: String = "",
    /** Force the build to run on the phone (built-in Linux), never GitHub. */
    val localOnly: Boolean = false,
    /** Build in the cloud and also open a GitHub Codespace on the project; the APK is saved straight to the phone. */
    val codespace: Boolean = false,
    /**
     * Everything that was asked for, in full (e.g. the whole chat about the app). Blank: [description] is all there is.
     */
    val requirements: String = "",
    /** The custom API this build uses (its own, even if another chat switches APIs). Blank = the app's current one. */
    val customBaseUrl: String = "",
    val customFormat: String = "",
    /** After the APK builds, open it on GitHub's Android emulator and check it doesn't crash (fixed like build errors). */
    val testApp: Boolean = true,
    /** [requirements] already covers earlier requests too (a chat), so it replaces what the project has saved. */
    val requirementsComplete: Boolean = false,
)

data class BuildResult(
    val success: Boolean,
    val message: String,
    val repoUrl: String? = null,
    val apkUrl: String? = null,
    /** Why it failed: the build's error lines, or what the model sent back. */
    val log: String = "",
    /** The GitHub Actions run with the full log. */
    val runUrl: String? = null,
    /** Where the APK compiled on the phone (built-in Linux) was saved, if it was. */
    val phoneApk: String? = null,
    /** The source code as a zip on the phone (for builds made only on the phone). */
    val phoneSourceZip: String? = null,
    /** The phone-built APK in Arin's storage, ready for Android's installer. */
    val phoneInstallPath: String? = null,
    /** The Codespace opened for this project, if any. */
    val codespaceUrl: String? = null,
    /** Save the APK to the phone automatically (used for codespace builds). */
    val autoDownloadApk: Boolean = false,
    /** What the emulator test found, in plain words (blank when the app wasn't tested). */
    val testNote: String = "",
    /** Screenshot of the app's first screen on the emulator, if the test took one. */
    val testScreenshot: String? = null,
)

/**
 * Build Mode agent: the model writes or edits the app, GitHub Actions builds it, and build errors
 * go back to the model to fix, until the APK builds or the fix rounds for the Brain level run out.
 */
/** The fixed API address for [BuildRequest] (custom APIs only). */
fun BuildRequest.endpoint(): com.arin.llm.CustomApi.Endpoint? =
    if (provider == com.arin.llm.Provider.Custom && customBaseUrl.isNotBlank())
        com.arin.llm.CustomApi.Endpoint(customBaseUrl, runCatching { com.arin.llm.ApiFormat.valueOf(customFormat) }.getOrDefault(com.arin.llm.ApiFormat.OpenAI))
    else null

class BuildAgent(
    private val req: BuildRequest,
    private val emit: (BuildEvent) -> Unit,
    /** Called whenever the project changes, so it can be saved. */
    private val onProject: (Project) -> Unit = {},
    /** Live progress for long steps (null = done), with a short description. */
    private val onProgress: (Float?, String) -> Unit = { _, _ -> },
    /** For building on the phone in the built-in Linux, when its Android build tools are installed. */
    private val context: android.content.Context? = null,
) {
    private val gh = GitHubClient(req.githubToken)
    private val slug = (req.project?.appName ?: req.appName).lowercase().replace(Regex("[^a-z0-9]+"), "").ifEmpty { "app" }.take(30)
    /** The package the person asked for ("package com.example.calendar"), else one made from the app name. */
    private val packageName = req.project?.packageName
        ?: Regex("""(?i)\bpackage(?:\s+name)?\s*[:=]?\s*`?([a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){1,5})`?""")
            .find(req.description)?.groupValues?.get(1)?.lowercase()
        ?: ("com.aibuilt." + (if (slug.first().isLetter()) slug else "a$slug"))
    private val appName get() = req.project?.appName ?: importedName ?: req.appName

    /** Updating Arin itself (its own repo, workflow and signing key). */
    private val self = req.selfUpdate || req.project?.selfUpdate == true
    private val imported = req.project?.imported == true || req.zipPath != null || self
    /** Test the built app on GitHub's emulator (not for Arin itself, nor builds made only on the phone). */
    private val testing get() = false   // app-test temporarily disabled (restoring reliable builds)
    private val workflowFile = when {
        self -> SELF_WORKFLOW
        imported -> ProjectTemplate.IMPORT_WORKFLOW_FILE
        else -> "build.yml"
    }
    private var importedName: String? = null
    private var uploadedOnce = false
    private var currentSha: String? = req.project?.lastSha
    private var currentRound = 0
    /** The versionCode this self-update sets (and which the published APK must really have). */
    private var expectedSelfVersion = 0

    private val files = linkedMapOf<String, String>().apply { req.project?.files?.let { putAll(it) } }
    private val deps = linkedSetOf<String>().apply { req.project?.deps?.let { addAll(it) } }

    /** Files changed since the last commit (null value = delete). */
    private val pendingFiles = linkedMapOf<String, ByteArray?>()
    /** Big uploaded files waiting to be committed straight from storage. */
    private val pendingDisk = linkedMapOf<String, java.io.File>()
    private var depsChanged = false

    /** Everything the app must do: the first specification plus every change asked for since, so nothing gets lost. */
    private val requirements: String = kotlin.run {
        val now = req.requirements.ifBlank { req.description }.trim()
        val before = (req.project?.requirements?.ifBlank { null } ?: req.project?.description).orEmpty().trim()
        val all = when {
            req.selfUpdate || req.project?.selfUpdate == true -> now
            req.requirementsComplete || before.isEmpty() -> now.ifBlank { before }
            req.resume || now.isEmpty() || before.contains(now) -> before
            else -> before + "\n\nCHANGE ASKED FOR LATER (do this too; it wins where it conflicts with the above):\n" + now
        }
        if (all.length > 36_000) all.take(12_000) + "\n[…]\n" + all.takeLast(24_000) else all
    }

    /** How many times the finished code is checked against the requirements before building. */
    private val reviewRounds: Int = when (req.effort) {
        Effort.LOW -> 0
        Effort.MAX -> 2
        else -> 1
    }

    private val maxFixRounds: Int = when (req.effort) {
        Effort.LOW -> 1
        Effort.MEDIUM, Effort.AUTO -> 3
        Effort.HIGH -> 6
        Effort.MAX -> 10
    } + if (false) 2 else 0

    private fun step(kind: BuildEvent.Kind, text: String) = emit(BuildEvent(kind, text))

    /** The model's last answer, kept so a failure report can show what it sent. */
    private var lastReply = ""

    private var lastThinkingNote = 0L

    /** The Codespace opened for this build, if any. */
    private var codespaceUrl: String? = null

    /** Parts written so far, numbered for the plain-English progress. */
    private var part = 0

    private fun modelReplyLog(): String =
        if (lastReply.isBlank()) "(The model sent back an empty answer: it may have spent its whole answer thinking, or the service cut it off.)"
        else "Last answer from the model (end):\n" + lastReply.takeLast(1_500)

    /** The phone can compile apps itself (Linux with the Android build tools). */
    private val phoneCan = !self && context != null && LocalBuild.available(context)

    /** No GitHub for this build: compile, fix and hand over the APK on the phone. */
    private var localOnly = req.project?.owner == LOCAL_OWNER || (req.localOnly && !self)

    private fun localRepo(name: String) = GitHubClient.Repo(LOCAL_OWNER, name, "main", "")

    /** Creates the repo on GitHub, or switches to a phone-only build if GitHub refuses and the phone can build. */
    private suspend fun createRepoOrLocal(name: String, description: String): GitHubClient.Repo {
        if (localOnly) return localRepo(name)
        step(BuildEvent.Kind.STEP, "Creating GitHub repo $name")
        return try {
            gh.createRepo(name, description)
        } catch (e: ProviderException) {
            if (!phoneCan) throw e
            step(BuildEvent.Kind.ERROR, e.message ?: "GitHub refused to create the repository")
            step(BuildEvent.Kind.STEP, "Building on this phone instead (no GitHub needed)")
            localOnly = true
            localRepo(name)
        }
    }

    suspend fun run(): BuildResult {
        // Always build with the newest stable toolchain (looked up online; falls back to the last good set if needed).
        Toolchain.refresh { step(BuildEvent.Kind.STEP, it) }
        if (!localOnly) {
            step(BuildEvent.Kind.STEP, "Checking your GitHub token")
            try {
                if (req.githubToken.isBlank()) throw ProviderException(401, "Not signed in to GitHub")
                gh.login()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!phoneCan || req.project != null && req.project.owner != LOCAL_OWNER) throw e
                step(BuildEvent.Kind.STEP, "GitHub isn't available (${e.message}); building on this phone only")
                localOnly = true
            }
        }

        val existing = req.project
        val repo: GitHubClient.Repo
        var notes: List<String>
        val neverUploaded = existing != null && existing.imported && !existing.uploaded && existing.lastApkUrl == null
        val resumeZip = existing?.takeIf { neverUploaded }?.zipPath?.takeIf { java.io.File(it).exists() }
        val zipToRead = req.zipPath ?: resumeZip
        var checkSha: String? = null
        var startRound = 0
        if (req.resume && existing != null && resumeZip == null) {
            // Retry: continue from the saved checkpoint instead of starting over.
            repo = GitHubClient.Repo(existing.owner, existing.repo, existing.branch, existing.repoUrl)
            notes = emptyList()
            startRound = existing.round
            val restored = existing.pendingPaths.mapNotNull { p ->
                val text = files[p] ?: (if (!imported) ProjectTemplate.files(appName, packageName, deps.toList())[p] else null)
                text?.let { p to it.toByteArray() }
            }
            when {
                restored.isNotEmpty() -> {
                    restored.forEach { (p, b) -> pendingFiles[p] = b }
                    step(BuildEvent.Kind.STEP, "Resuming: uploading ${restored.size} file(s) that didn't reach GitHub last time")
                }
                existing.lastSha != null -> {
                    checkSha = existing.lastSha
                    step(BuildEvent.Kind.STEP, "Resuming: checking the last build on GitHub (no code is rewritten)")
                }
                else -> step(BuildEvent.Kind.STEP, "Resuming: starting a fresh build of the current code")
            }
        } else if ((existing == null && req.zipPath != null) || resumeZip != null) {
            val zipLabel = req.zipName ?: existing?.zipName ?: "the project"
            if (resumeZip != null) step(BuildEvent.Kind.STEP, "The first upload didn't finish — uploading the whole project again")
            step(BuildEvent.Kind.STEP, "Unpacking $zipLabel")
            onProgress(-1f, "Reading ${mb(java.io.File(zipToRead!!).length())}…")
            val zip = withContext(Dispatchers.IO) { ZipImport.read(java.io.File(zipToRead), zipLabel) }
            onProgress(null, "")
            importedName = zip.name
            zip.files.forEach { (path, bytes) ->
                pendingFiles[path] = bytes
                if (ZipImport.isText(path) && bytes.size < 200_000) files[path] = bytes.toString(Charsets.UTF_8)
            }
            pendingDisk.putAll(zip.bigFiles)
            if (zip.tooBig.isNotEmpty()) {
                step(BuildEvent.Kind.ERROR, "Skipped ${zip.tooBig.size} file(s) over 95 MB (GitHub's limit): ${zip.tooBig.take(3).joinToString { it.substringAfterLast('/') }}")
            }
            pendingFiles[".github/workflows/${ProjectTemplate.IMPORT_WORKFLOW_FILE}"] = ProjectTemplate.importWorkflow.toByteArray()
            step(BuildEvent.Kind.STEP, "Found ${zip.count} files in \"${zip.name}\"" + if (zip.skipped > 0) " (skipped ${zip.skipped} build/cache files)" else "")
            val repoName = zip.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "project" }.take(40) +
                "-" + SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
            repo = if (existing != null) {
                GitHubClient.Repo(existing.owner, existing.repo, existing.branch, existing.repoUrl)
            } else {
                createRepoOrLocal(repoName, "Uploaded to Arin: ${zip.name}")
            }
            notes = emptyList()
        } else if (self) {
            // Arin updating itself: read its source from GitHub, change it, build it with its own workflow.
            val (owner, name) = req.selfRepo.split('/').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            if (owner.isBlank() || name.isBlank()) return BuildResult(false, "Set Arin's source repository in Settings → GitHub first.")
            repo = GitHubClient.Repo(owner, name, req.selfBranch.ifBlank { "main" }, "https://github.com/$owner/$name/tree/${req.selfBranch}")
            importedName = "Arin"
            step(BuildEvent.Kind.STEP, "Reading Arin's source from ${req.selfRepo} (${repo.branch})")
            onProgress(-1f, "Downloading source files…")
            files.clear()
            files.putAll(gh.readTextFiles(repo))
            onProgress(null, "")
            if (files.isEmpty()) return BuildResult(false, "Couldn't read Arin's source from ${req.selfRepo}@${repo.branch}. Check the repository in Settings → GitHub.")
            // The version number must always go up: Android refuses an APK with a lower versionCode ("App not installed").
            pinSelfVersionCode()
            step(BuildEvent.Kind.DONE, "Read ${files.size} source files")
            step(BuildEvent.Kind.THINKING, "Working out the changes to Arin with ${req.provider.label}")
            val assets = addAttachedAssets()
            notes = apply(ask(changePrompt() + assets, req.attachments))
            if (pendingFiles.isEmpty() && !depsChanged) {
                return BuildResult(false, "The model didn't change any of Arin's files. Try describing the change differently.", repo.url, log = modelReplyLog())
            }
            uploadedOnce = true
        } else if (existing == null) {
            step(BuildEvent.Kind.THINKING, "Planning and writing the app with ${req.provider.label}")
            val assets = addAttachedAssets()
            notes = apply(ask(firstPrompt() + assets, req.attachments))
            // Some models plan in prose and forget to write the files: ask again, strictly, up to twice.
            var retries = 0
            while (files.isEmpty() && retries < 2) {
                retries++
                step(BuildEvent.Kind.STEP, "No files came back. Asking ${req.provider.label} again for the code (try $retries of 2)")
                notes = apply(ask(firstPrompt() + assets + formatReminder(), req.attachments))
            }
            if (files.isNotEmpty()) {
                notes = finishWriting(notes)
                notes = reviewAgainstRequirements(notes)
            }
            if (files.isEmpty()) {
                return BuildResult(
                    false,
                    "The model didn't return any files, even after asking again, so there was nothing to build. " +
                        "Below is the end of what it sent back. Try again, or pick another model.",
                    log = modelReplyLog(),
                )
            }
            val repoName = "$slug-" + SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
            repo = createRepoOrLocal(repoName, "Built by Arin: ${req.description}")
            ProjectTemplate.files(appName, packageName, deps.toList()).forEach { (p, c) -> pendingFiles[p] = c.toByteArray() }
            // Sign every built app with the one shared key, so a rebuild updates over the installed app cleanly.
            pendingFiles["app/signing.keystore"] = android.util.Base64.decode(ProjectTemplate.SHARED_KEYSTORE_B64, android.util.Base64.DEFAULT)
            if (req.codespace && !localOnly) {
                // Make an opened codespace ready to build Android, and open one now (if the sign-in allows it).
                pendingFiles[".devcontainer/devcontainer.json"] = ProjectTemplate.devcontainer.toByteArray()
                step(BuildEvent.Kind.STEP, "Opening a GitHub Codespace on the project")
                codespaceUrl = gh.createCodespace(repo) ?: gh.codespaceLink(repo)
            }
            if ("app/src/main/AndroidManifest.xml" !in files) {
                pendingFiles["app/src/main/AndroidManifest.xml"] = ProjectTemplate.defaultManifest(appName).toByteArray()
            }
        } else {
            repo = GitHubClient.Repo(existing.owner, existing.repo, existing.branch, existing.repoUrl)
            step(BuildEvent.Kind.THINKING, "Working out the changes with ${req.provider.label}")
            val assets = addAttachedAssets()
            notes = apply(ask(changePrompt() + assets, req.attachments))
            if (pendingFiles.isEmpty() && !depsChanged) {
                return BuildResult(false, "The model didn't change any files. Try describing the change differently.", repo.url, existing.lastApkUrl, log = modelReplyLog())
            }
            if (!imported) {
                notes = finishWriting(notes)
                notes = reviewAgainstRequirements(notes)
            }
        }
        saveProject(repo, null)

        // Build on the phone first when Linux has the Android build tools: errors are found and fixed here,
        // and GitHub then only builds code that already compiles (and gives the download links).
        val localSlug = repo.name
        var useLocal = localOnly || phoneCan && (existing == null || LocalBuild.hasCopy(context!!, localSlug))
        if (useLocal && existing == null) {
            LocalBuild.write(context!!, localSlug, files.mapValues { it.value.toByteArray() })
        }
        var localRounds = 0
        var phoneApk: String? = null

        var round = startRound
        while (true) {
          currentRound = round
          val sha: String
          val resumeSha = checkSha
          if (resumeSha != null) {
            // Already on GitHub: just look at (or wait for) that build.
            checkSha = null
            sha = resumeSha
          } else {
            if (depsChanged) {
                val gradle = ProjectTemplate.files(appName, packageName, deps.toList())
                pendingFiles["app/build.gradle.kts"] = gradle.getValue("app/build.gradle.kts").toByteArray()
                // The top-level file changes too when a code generator (KSP, for Room) is added.
                pendingFiles["build.gradle.kts"] = gradle.getValue("build.gradle.kts").toByteArray()
                depsChanged = false
            }
            // Make sure GitHub always has the build instructions (harmless if unchanged).
            if (self) {
                // Arin's repo already has its workflow and signing key: leave them alone.
            } else if (imported) {
                pendingFiles[".github/workflows/${ProjectTemplate.IMPORT_WORKFLOW_FILE}"] = ProjectTemplate.importWorkflow.toByteArray()
            } else {
                ProjectTemplate.files(appName, packageName, deps.toList())[".github/workflows/build.yml"]?.let {
                    pendingFiles[".github/workflows/build.yml"] = it.toByteArray()
                }
            }
            if (useLocal) {
                val ctx = context!!
                LocalBuild.write(ctx, localSlug, pendingFiles.toMap())
                LocalBuild.writeFiles(ctx, localSlug, pendingDisk.toMap())
                step(BuildEvent.Kind.BUILD, "Compiling on this phone in Linux (the first build downloads libraries and can take 10+ minutes)")
                when (val out = LocalBuild.build(ctx, localSlug, appName) { }) {
                    is LocalBuild.Outcome.Ok -> {
                        phoneApk = out.apkPath
                        if (localOnly) {
                            val zip = LocalBuild.saveSourceZip(ctx, localSlug, appName)
                            step(BuildEvent.Kind.DONE, "APK ready (${mb(out.size)}): ${out.apkPath}")
                            saveProject(repo, null)
                            step(BuildEvent.Kind.DONE, "Done")
                            return BuildResult(
                                true,
                                if (existing != null) "Your changes are built on this phone." else "Your app is ready (built on this phone).",
                                phoneApk = out.apkPath,
                                phoneSourceZip = zip,
                                phoneInstallPath = out.installFile?.absolutePath,
                            )
                        }
                        step(BuildEvent.Kind.DONE, "Compiled on this phone (${mb(out.size)}), saved to ${out.apkPath}. Now building on GitHub for the download links")
                    }
                    is LocalBuild.Outcome.Errors -> {
                        if (localRounds < maxFixRounds) {
                            localRounds++
                            val query = mainError(out.log)
                            val web = if (query.isNotBlank()) {
                                step(BuildEvent.Kind.WEB, "Searching the web for the error: $query")
                                runCatching { WebTools.search(query) }.getOrNull()?.take(6_000).orEmpty()
                            } else ""
                            val count = out.log.lines().count { it.startsWith("e: ") || it.contains("error:", true) }.coerceAtLeast(1)
                            step(BuildEvent.Kind.ERROR, "Phone build failed with $count error${if (count == 1) "" else "s"}: ${plainError(out.log)}. Fixing on the phone (round $localRounds of $maxFixRounds)")
                            notes = apply(ask(fixPrompt(out.log, notes, web)))
                            continue
                        }
                        if (localOnly) {
                            saveProject(repo, null)
                            return BuildResult(
                                false,
                                "The build still fails on the phone after $localRounds fix rounds. The errors are below; say \"try again\" to keep fixing.",
                                log = out.log,
                            )
                        }
                        step(BuildEvent.Kind.ERROR, "Still failing on the phone after $localRounds fixes; trying GitHub's build")
                        useLocal = false
                    }
                    is LocalBuild.Outcome.Skipped -> {
                        if (localOnly) {
                            saveProject(repo, null)
                            return BuildResult(false, "The phone couldn't run the build: ${out.reason.take(200)}", log = out.reason)
                        }
                        step(BuildEvent.Kind.STEP, "Couldn't build on this phone (${out.reason.take(120)}); GitHub will build it")
                        useLocal = false
                    }
                }
            }
            val toUpload = pendingFiles.size + pendingDisk.size
            step(BuildEvent.Kind.STEP, "Uploading $toUpload changed file${if (toUpload == 1) "" else "s"} to GitHub")
            val message = when {
                round > 0 -> "Fix build errors (round $round)"
                self -> "Arin: ${req.description.take(60)}"
                existing != null -> "Change: ${req.description.take(60)}"
                req.zipPath != null -> "Uploaded project from Arin"
                else -> "Initial app from Arin"
            }
            val total = pendingFiles.size + pendingDisk.size
            var lastPct = -1
            sha = gh.commit(repo, pendingFiles.toMap(), message, pendingDisk.toMap()) { n, of, sent, size ->
                val pct = if (size > 0) (sent * 100 / size).toInt() else n * 100 / of.coerceAtLeast(1)
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct / 100f, "$n of $of files · ${mb(sent)} of ${mb(size)}")
                }
            }
            onProgress(null, "")
            step(BuildEvent.Kind.DONE, "Upload finished: $total file${if (total == 1) "" else "s"} (${mb(pendingFiles.values.sumOf { (it?.size ?: 0).toLong() } + pendingDisk.values.sumOf { it.length() })})")
            pendingFiles.clear()
            pendingDisk.values.forEach { it.delete() }
            pendingDisk.clear()
            uploadedOnce = true
            currentSha = sha
            saveProject(repo, null)
          }

            step(BuildEvent.Kind.BUILD, if (testing) "Building the APK on GitHub, then testing it on an Android emulator (about 6–12 minutes)"
                else "Building the APK on GitHub (usually 3–5 minutes)")
            val run = waitForRun(repo, sha)
                ?: return BuildResult(
                    false,
                    if (neverUploaded) {
                        "GitHub didn't start a build because this project's first upload never finished. Tap New, then upload the zip again."
                    } else {
                        "GitHub didn't start a build. Open the repo on GitHub → Actions: if Actions is turned off for the repo, " +
                            "turn it on and try again; otherwise sign out and sign in with GitHub again in Settings."
                    },
                    repo.url,
                )

            var crashLog: String? = null
            if (run.conclusion == "success") {
                step(BuildEvent.Kind.STEP, "Build succeeded. Getting the download link")
                // Only hand over a link once the APK file is really there.
                var apk: String? = null
                var size: Long? = null
                for (i in 0 until 20) {
                    apk = if (self) gh.apkForTag(repo, "$SELF_TAG_PREFIX${run.number}") else gh.latestApkUrl(repo)
                    size = apk?.let { ApkInstaller.check(it) }
                    if (apk != null && size != null) break
                    delay(3000)
                }
                if (apk == null || size == null) {
                    return BuildResult(false, "The build worked, but GitHub hasn't published the APK yet. Open the repo's Releases on GitHub in a minute.", repo.url)
                }
                // Arin updating itself must really install over the installed Arin: check version number and signing key first.
                if (self) {
                    val problem = verifySelfUpdate(apk)
                    if (problem != null) {
                        saveProject(repo, apk)
                        return BuildResult(
                            false,
                            "This self-update must not be installed: $problem",
                            repo.url,
                            apk,
                            log = problem,
                            runUrl = run.url,
                        )
                    }
                }
                step(BuildEvent.Kind.DONE, "APK ready: ${mb(size)}")
                saveProject(repo, apk)
                // The emulator test (it ran in the same GitHub job, after the APK was published).
                var testNote = ""
                var shot: String? = null
                if (testing) {
                    val tag = "build-${run.number}"
                    val report = gh.releaseText(repo, tag, "arin-test-report.txt")
                    shot = gh.releaseAssetLink(repo, tag, "arin-test-screen.png")
                    val result = report?.lineSequence()?.firstOrNull { it.startsWith("RESULT:") }?.removePrefix("RESULT:")?.trim()
                    val why = report?.lineSequence()?.firstOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim().orEmpty()
                    when (result) {
                        "PASS" -> {
                            testNote = "Tested on an Android emulator: it opens, and tapping around didn't crash it."
                            step(BuildEvent.Kind.DONE, testNote)
                        }
                        "CRASH", "NOT_RUNNING", "INSTALL_FAILED" -> {
                            val plain = when (result) {
                                "INSTALL_FAILED" -> "The APK wouldn't install on the emulator"
                                "NOT_RUNNING" -> "The app closed by itself right after it opened"
                                else -> "The app crashed on the emulator"
                            } + (if (why.isNotBlank()) ": $why" else "")
                            if (round < maxFixRounds) {
                                crashLog = "RUNTIME PROBLEM (the app builds, but fails when it runs on an Android emulator).\n" +
                                    "ERROR: $plain\n\nTest report and crash log:\n" + report.orEmpty().take(8_000)
                                step(BuildEvent.Kind.ERROR, "$plain. Fixing it")
                            } else {
                                testNote = "⚠️ $plain. The APK is ready, but say \"fix the crash\" to keep fixing it."
                                step(BuildEvent.Kind.ERROR, testNote)
                            }
                        }
                        else -> {
                            testNote = "The emulator test couldn't run this time, so the app wasn't checked."
                            step(BuildEvent.Kind.STEP, testNote)
                        }
                    }
                }
              if (crashLog == null) {
                step(BuildEvent.Kind.DONE, "Done")
                return BuildResult(
                    true,
                    when {
                        self -> "The new version of Arin is ready. Tap Install to update."
                        existing != null -> "Your changes are built."
                        req.zipPath != null -> "Your project compiled to an APK."
                        else -> "Your app is ready."
                    },
                    repo.url,
                    apk,
                    phoneApk = phoneApk,
                    codespaceUrl = codespaceUrl,
                    autoDownloadApk = req.codespace,
                    testNote = testNote,
                    testScreenshot = shot,
                )
              }
            }

            val errors = crashLog ?: kotlin.run {
                step(BuildEvent.Kind.STEP, "Reading the build errors")
                gh.failureSummary(repo, run.id)
            }
            if (crashLog == null && round >= maxFixRounds) {
                return BuildResult(
                    false,
                    "The build still fails after $round fix round${if (round == 1) "" else "s"}. The errors are below; " +
                        "say \"try again\" to keep fixing, or try a stronger model.",
                    repo.url,
                    log = errors,
                    runUrl = run.url,
                )
            }
            round++
            currentRound = round
            // Look the main error up online every time, and give the model what others did to fix it.
            val web = kotlin.run {
                val query = mainError(errors)
                if (query.isNotBlank()) {
                    step(BuildEvent.Kind.WEB, "Searching the web for the error: $query")
                    runCatching { WebTools.search(query) }.getOrNull()?.take(6_000).orEmpty()
                } else ""
            }
            val count = errors.lines().count { it.startsWith("e: ") || it.contains("error:", true) }.coerceAtLeast(1)
            if (crashLog != null) step(BuildEvent.Kind.ERROR, "Fixing the crash (round $round of $maxFixRounds), then building and testing again")
            else step(BuildEvent.Kind.ERROR, "Build failed with $count error${if (count == 1) "" else "s"}: ${plainError(errors)}. Fixing (round $round of $maxFixRounds)")
            notes = apply(ask(fixPrompt(errors, notes, web)))
            saveProject(repo, null)
        }
    }

    private fun mb(bytes: Long) = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "%.1f MB".format(bytes / 1048576.0)

    /**
     * Sets Arin's own versionCode for this self-update: at least the floor (1000) and always one above the installed
     * app, so the number can never run backwards. Everything else in the build file is left alone.
     */
    private fun pinSelfVersionCode() {
        val ctx = context ?: return
        val installed = SelfUpdate.installedVersionCode(ctx)
        val target = maxOf(SelfUpdate.MIN_VERSION_CODE, installed + 1)
        expectedSelfVersion = target
        val path = "app/build.gradle.kts"
        val raw = files[path]
        if (raw == null) {
            step(BuildEvent.Kind.ERROR, "Arin's app/build.gradle.kts wasn't read, so the new version number can't be checked before publishing")
            return
        }
        val versionLine = Regex("""(?m)^([ \t]*versionCode[ \t]*=[ \t]*)(\d+)""")
        if (!versionLine.containsMatchIn(raw)) {
            step(BuildEvent.Kind.ERROR, "No versionCode line found in Arin's app/build.gradle.kts; the update would keep the old number")
            return
        }
        val updated = versionLine.replace(raw) { it.groupValues[1] + target }
        if (updated == raw) return
        files[path] = updated
        pendingFiles[path] = updated.toByteArray()
        step(
            BuildEvent.Kind.STEP,
            "Version number set to $target (installed: ${if (installed > 0) installed else "unknown"}; floor ${SelfUpdate.MIN_VERSION_CODE})",
        )
    }

    /** Downloads the published update and checks its version number and signer; returns the problem, or null when it installs. */
    private suspend fun verifySelfUpdate(apkUrl: String): String? {
        val ctx = context ?: return "the built APK couldn't be checked (no app context)."
        step(BuildEvent.Kind.STEP, "Checking the update installs over the installed Arin (version number and signing key)")
        val local = SelfUpdate.download(ctx, apkUrl) ?: return "the new APK couldn't be downloaded to check it."
        val check = SelfUpdate.verify(ctx, local, expectedSelfVersion)
        runCatching { local.delete() }
        check.log.lines().filter { it.isNotBlank() }.forEach { step(BuildEvent.Kind.STEP, it) }
        step(if (check.ok) BuildEvent.Kind.DONE else BuildEvent.Kind.ERROR, check.message)
        return if (check.ok) null else check.message
    }

    private fun saveProject(repo: GitHubClient.Repo, apk: String?) {
        onProject(
            Project(
                appName = appName,
                packageName = packageName,
                owner = repo.owner,
                repo = repo.name,
                branch = repo.branch,
                repoUrl = repo.url,
                description = req.project?.description ?: req.description.ifBlank { "Uploaded project" },
                requirements = requirements,
                files = files.toMap(),
                deps = deps.toList(),
                lastApkUrl = apk ?: req.project?.lastApkUrl,
                imported = imported,
                selfUpdate = self,
                zipPath = req.zipPath ?: req.project?.zipPath,
                zipName = req.zipName ?: req.project?.zipName,
                uploaded = uploadedOnce || req.project?.uploaded == true,
                lastSha = currentSha,
                pendingPaths = pendingFiles.keys.toList(),
                round = currentRound,
            ),
        )
    }

    /**
     * Asks the model, letting it search the web or read pages before it writes files, and reports
     * each file as it starts streaming in.
     */
    /** Adds attached images, fonts, sounds and other files to the project; returns notes for the prompt. */
    private fun addAttachedAssets(): String {
        val notes = mutableListOf<String>()
        req.attachments.filter { it.isBinary && !it.isZip }.forEach { a ->
            val ext = a.name.substringAfterLast('.', "").lowercase()
            val base = a.name.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
                .let { if (it.isEmpty() || !it.first().isLetter()) "file_$it" else it }.take(40)
            val path = when {
                ext in listOf("png", "jpg", "jpeg", "webp") || (a.kind == com.arin.data.Attachment.IMAGE && a.mime == "image/jpeg") ->
                    "app/src/main/res/drawable/$base.${if (ext == "jpeg" || (a.mime == "image/jpeg" && ext !in listOf("png", "webp"))) "jpg" else ext}"
                ext in listOf("ttf", "otf") -> "app/src/main/res/font/$base.$ext"
                ext in listOf("mp3", "ogg", "wav", "m4a", "aac", "mp4", "webm") -> "app/src/main/res/raw/$base.$ext"
                else -> "app/src/main/assets/${a.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
            }
            val bytes = runCatching { java.io.File(a.path).readBytes() }.getOrNull() ?: return@forEach
            if (bytes.size > MAX_DOWNLOAD) {
                notes += "${a.name} is over 15 MB, so it wasn't added to the app."
                return@forEach
            }
            pendingFiles[path] = bytes
            val ref = when {
                path.contains("/res/drawable/") -> "R.drawable.${path.substringAfterLast('/').substringBeforeLast('.')}"
                path.contains("/res/font/") -> "R.font.${path.substringAfterLast('/').substringBeforeLast('.')}"
                path.contains("/res/raw/") -> "R.raw.${path.substringAfterLast('/').substringBeforeLast('.')}"
                else -> "assets/${path.substringAfterLast('/')}"
            }
            step(BuildEvent.Kind.DOWNLOAD, "Added your file ${a.name} as $ref")
            notes += "The user's file ${a.name} is in the project as $ref; use it where it fits."
        }
        return if (notes.isEmpty()) "" else "\n\nFiles from the user:\n" + notes.joinToString("\n") { "- $it" }
    }

    private suspend fun ask(prompt: String, attachments: List<com.arin.data.Attachment> = emptyList()): String {
        val effort = if (req.effort == Effort.AUTO) Effort.HIGH else req.effort
        val system = (if (imported) ProjectTemplate.importedSystemPrompt else ProjectTemplate.systemPrompt(packageName)) +
            ProjectTemplate.webInstructions
        val cap = if (false) 3_000 else 8_000
        val working = mutableListOf(ChatMessage(Role.USER, prompt, attachments))
        var steps = 0
        while (true) {
            val sb = StringBuilder()
            val thinking = StringBuilder()
            var scanned = 0
            var about: String? = null
            resilient(
                attempt = { model -> streamModel(req.provider, req.apiKey, model, working.toList(), system, req.endpoint()) },
                model = req.model,
                fallback = { alternativeModel(req.provider, req.apiKey, req.model) },
                onRetry = { step(BuildEvent.Kind.STEP, it) },
            ).collect { raw ->
                if (raw.startsWith(THOUGHT)) {
                    // Show what the model is thinking, live, under the current step.
                    thinking.append(raw.substring(1))
                    // Only the newest part is shown, so only look at the end (the whole text grows with every word).
                    val now = System.currentTimeMillis()
                    if (now - lastThinkingNote > 400) {
                        lastThinkingNote = now
                        onProgress(-1f, "Thinking: " + proseOnly(thinking.takeLast(1_200).toString()).takeLast(160))
                    }
                    return@collect
                }
                if (thinking.isNotEmpty()) {
                    step(BuildEvent.Kind.THINKING, "Planned it: " + proseOnly(thinking.take(3_000).toString()).trim().take(160) + "…")
                    thinking.clear()
                    onProgress(null, "")
                }
                val chunk = raw
                sb.append(chunk)
                // Announce files as their headers arrive, like an IDE agent.
                val nl = sb.lastIndexOf("\n")
                if (nl > scanned) {
                    sb.substring(scanned, nl).lines().forEach { line ->
                        aboutLine.find(line.trim())?.let { about = it.groupValues[1].trim() }
                        fileStart.find(line.trim())?.let {
                            // Say in plain English what this part of the app does, like Claude does, not the code.
                            val path = it.groupValues[1].trim('`', ' ')
                            part++
                            val what = about?.takeIf { a -> a.length > 3 } ?: plainEnglish(path)
                            step(BuildEvent.Kind.FILE, "Part $part: $what (${path.substringAfterLast('/')})")
                            about = null
                        }
                    }
                    scanned = nl
                }
            }
            val reply = sb.toString()
            lastReply = reply
            if (steps >= MAX_WEB_STEPS || reply.contains("=== FILE:")) return reply
            val search = WebTools.searchTag.find(reply)?.groupValues?.get(1)?.trim()
            val fetch = WebTools.fetchTag.find(reply)?.groupValues?.get(1)?.trim()
            val result = when {
                search != null -> {
                    step(BuildEvent.Kind.WEB, "Searching: $search")
                    "Web search results for \"$search\":\n\n" + WebTools.search(search).take(cap)
                }
                fetch != null -> {
                    step(BuildEvent.Kind.WEB, "Reading ${fetch.removePrefix("https://").take(60)}")
                    "Content of $fetch:\n\n" + WebTools.fetch(fetch).take(cap)
                }
                else -> return reply
            }
            steps++
            working += ChatMessage(Role.ASSISTANT, reply.trim())
            working += ChatMessage(Role.USER, "[Tool result]\n$result\n\nNow continue: write the files in the required format.")
        }
    }

    /** Sent again when a reply had no files: just the format, as plainly as possible. */
    private fun formatReminder() = "\n\n" + """
        IMPORTANT: your last reply contained no files, so nothing could be built. Don't explain or plan this time.
        Reply with ONLY the complete files, starting right away with the lines below, every file in this exact format:
        === ABOUT: what this part of the app does, in plain English ===
        === FILE: app/src/main/java/${packageName.replace('.', '/')}/MainActivity.kt ===
        (the whole file)
