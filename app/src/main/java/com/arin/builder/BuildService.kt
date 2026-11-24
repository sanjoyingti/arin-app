package com.arin.builder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.arin.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/** Runs Build Mode in the background with a notification, so closing the app doesn't stop a build. */
class BuildService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + com.arin.CrashLog.handler { this })

    /** Progress updates arrive for every word the model writes; the notification only needs a few a second. */
    private var lastProgressNote = 0L
    /** One job per running build session, so several builds run side by side. */
    private val jobs = mutableMapOf<String, Job>()
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val id = intent.getStringExtra(EXTRA_SESSION)
            if (id == null) jobs.values.toList().forEach { it.cancel() } else jobs[id]?.cancel()
            return START_NOT_STICKY
        }
        val session = BuildRunner.session(intent?.getStringExtra(EXTRA_SESSION))
        val request = session?.pending
        if (session == null || request == null || jobs[session.id]?.isActive == true) {
            if (jobs.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        session.pending = null
        createChannel()
        // Every startForegroundService() needs a startForeground() soon after, even when already in the foreground.
        startInForeground(progressNotification(if (jobs.isEmpty()) "Starting…" else "Starting another build…"))
        inForeground = true
        // Keep the CPU and network awake while building, even with the screen off.
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Arin:build")
                .apply { acquire(2 * 60 * 60 * 1000L) }
        }
        val appName = request.project?.appName ?: request.appName
        session.running = true
        jobs[session.id] = scope.launch {
            // The agent parses big model replies and project files: keep that work off the main thread,
            // or the screen freezes and Android closes the app ("isn't responding").
            val result = try { kotlinx.coroutines.withContext(Dispatchers.Default) {
                BuildAgent(
                    request,
                    context = this@BuildService,
                    emit = { event ->
                        scope.launch {
                            runCatching {
                                session.timeline += TimelineItem.Event(event.copy(text = event.text.take(6_000)))
                                BuildRunner.save(this@BuildService, session)
                                notify(PROGRESS_ID, progressNotification(prefix(appName) + event.text.take(200)))
                            }.onFailure { com.arin.CrashLog.record(this@BuildService, it, "build step") }
                        }
                    },
                    onProject = { p ->
                        scope.launch {
                            runCatching {
                                session.project = p
                                BuildRunner.save(this@BuildService, session)
                            }.onFailure { com.arin.CrashLog.record(this@BuildService, it, "saving the build") }
                        }
                    },
                    onProgress = { value, text ->
                        val now = System.currentTimeMillis()
                        if (value == null || now - lastProgressNote > 300) {
                            lastProgressNote = now
                            scope.launch {
                                runCatching {
                                    session.progress = value
                                    session.progressText = text.take(300)
                                    if (value != null) notify(PROGRESS_ID, progressNotification(prefix(appName) + text.take(200).ifBlank { "Working…" }, value))
                                }
                            }
                        }
                    },
                ).run()
            } } catch (e: CancellationException) {
                BuildResult(false, "Stopped.")
            } catch (e: Throwable) {
                com.arin.CrashLog.record(this@BuildService, e, "build")
                BuildResult(false, friendly(e), log = e.stackTraceToString().take(2_000))
            }
            session.timeline += TimelineItem.Result(result.success, result.message, result.apkUrl, result.repoUrl)
            if (result.success) Toolchain.markGood()
            runCatching { session.timeline += TimelineItem.Event(BuildEvent(BuildEvent.Kind.DONE, plainSummary(appName, result, session.timeline.toList()))) }
            if (!result.success && result.message != "Stopped.") {
                com.arin.CrashLog.note(this@BuildService, "Build failed", "$appName: ${result.message}", result.log)
            }
            if (!result.success && result.log.isNotBlank()) {
                session.timeline += TimelineItem.Event(BuildEvent(BuildEvent.Kind.ERROR, "Why it failed:\n" + result.log.take(3_000)))
            }
            session.running = false
            session.progress = null
            BuildRunner.saveNow(this@BuildService, session)
            val finished = session.project
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                runCatching { BuildRunner.onFinished?.invoke(result, finished, session.id) }
            }
            notify(DONE_ID + (session.id.hashCode() and 0xffff), doneNotification(appName, result))
            jobs.remove(session.id)
            if (jobs.isEmpty()) {
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                inForeground = false
                stopSelf()
            } else {
                notify(PROGRESS_ID, progressNotification("${jobs.size} build${if (jobs.size == 1) "" else "s"} still running"))
            }
        }
        return START_NOT_STICKY
    }

    /** With several builds running, each notification line says which app it's about. */
    private fun prefix(appName: String) = if (jobs.size > 1) "${appName.ifBlank { "App" }}: " else ""

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        scope.cancel()
        BuildRunner.sessions.values.forEach { it.running = false }
        super.onDestroy()
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_ID, n)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App builds", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished builds", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun notify(id: Int, n: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(text: String, value: Float? = null): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BuildService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (BuildRunner.runningCount > 1) "Building ${BuildRunner.runningCount} apps" else "Building your app")
            .setContentText(text)
            .setOngoing(true)
            .apply { if (value != null) setProgress(100, (value * 100).toInt().coerceIn(0, 100), value < 0f) }
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), if (BuildRunner.runningCount > 1) "Stop all" else "Stop", stop,
            ).build())
            .build()
    }

    private fun doneNotification(appName: String, r: BuildResult): Notification {
        val tap = r.apkUrl?.let {
            PendingIntent.getActivity(this, 2, Intent(Intent.ACTION_VIEW, Uri.parse(it)), PendingIntent.FLAG_IMMUTABLE)
        } ?: openAppIntent()
        return Notification.Builder(this, CHANNEL_DONE)
            .setSmallIcon(if (r.success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (r.success) "${appName.ifBlank { "Your app" }} is ready" else "${appName.ifBlank { "Your app" }} didn't build")
            .setContentText(if (r.success && r.apkUrl != null) "Tap to download the APK" else r.message)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
    }

    private fun friendly(t: Throwable): String = when {
        t is ProviderException && t.code == 401 -> "A key or token was rejected. Check Settings. (${t.message})"
        t is ProviderException && t.code == 403 ->
            "Not allowed: ${t.message}. Your GitHub token needs the public_repo and workflow scopes."
        t is ProviderException && t.code == 429 -> "Rate limit reached. Wait a moment and try again."
        t is IOException -> "Network error. Check your connection and try again."
        else -> t.message ?: "Something went wrong."
    }

    companion object {
        private const val CHANNEL = "builds"
        private const val CHANNEL_DONE = "builds_done"
        private const val PROGRESS_ID = 41
        private const val DONE_ID = 42
        private const val ACTION_STOP = "com.arin.STOP_BUILD"

        const val EXTRA_SESSION = "session"

        /**
         * Starts [request] in [session] (the Build tab's by default). Other sessions keep running: several builds
         * can run at once. Does nothing if this session is already building.
         */
        fun start(context: Context, request: BuildRequest, session: BuildSession = BuildRunner.current) {
            if (session.running) return
            BuildRunner.sessions[session.id] = session
            session.pending = request
            session.timeline += TimelineItem.User(request.description)
            session.running = true
            BuildRunner.save(context, session)
            context.startForegroundService(Intent(context, BuildService::class.java).putExtra(EXTRA_SESSION, session.id))
        }

        /** Stops one build ([sessionId]), or every build when it's null. Progress so far stays saved. */
        fun stop(context: Context, sessionId: String? = null) {
            context.startService(Intent(context, BuildService::class.java).setAction(ACTION_STOP)
                .apply { if (sessionId != null) putExtra(EXTRA_SESSION, sessionId) })
        }
    }

/** A short summary in plain English of what a build did, shown at the end of every build in the Build tab. */
internal fun plainSummary(appName: String, result: BuildResult, timeline: List<TimelineItem>): String {
    val asked = timeline.filterIsInstance<TimelineItem.User>().lastOrNull()?.text?.trim()?.take(160)
    val events = timeline.filterIsInstance<TimelineItem.Event>().map { it.event }
    val parts = events.count { it.kind == BuildEvent.Kind.FILE }
    val fixes = events.count { it.kind == BuildEvent.Kind.ERROR }
    val searched = events.count { it.kind == BuildEvent.Kind.WEB }
    val time = java.text.SimpleDateFormat("EEE, d MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
    return buildString {
        appendLine("📋 Summary")
        if (!asked.isNullOrBlank()) appendLine("• You asked: $asked")
        append("• What Arin did: ")
        append(listOfNotNull(
            if (searched > 0) "looked things up online $searched time${if (searched == 1) "" else "s"}" else null,
            if (parts > 0) "wrote $parts part${if (parts == 1) "" else "s"} of the app" else null,
            if (fixes > 0) "found and worked on $fixes problem${if (fixes == 1) "" else "s"}" else null,
            "built it",
        ).joinToString(", "))
        appendLine(".")
        when {
            result.success -> appendLine("• Result: ✅ $appName built successfully." + if (result.apkUrl != null) " The APK is ready to download and install." else "")
            result.message == "Stopped." -> appendLine("• Result: ⏹ You stopped it. Everything so far is saved; say \"continue\" to carry on.")
            else -> appendLine("• Result: ❌ Not finished yet — ${result.message.take(200)}")
        }
        append("🕒 ").append(if (result.success) "Completed " else "").append(time)
    }
}
}
