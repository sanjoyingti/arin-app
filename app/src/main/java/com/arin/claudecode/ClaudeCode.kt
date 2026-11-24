package com.arin.claudecode

import android.content.Context
import com.arin.terminal.Shell
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Runs Anthropic's Claude Code CLI inside Arin's built-in Linux, signed in with the person's own
 * Claude Pro/Max subscription (no API key, no API billing). Prompts run non-interactively with
 * `claude -p` and the answer comes back into the app.
 *
 * Login uses `claude setup-token`, which prints an OAuth URL and, once the person signs in, a
 * one-year token. PRoot shares the phone's network, so the browser's localhost redirect usually
 * reaches the CLI and the token appears on its own; a paste-the-code box is there as a fallback.
 */
object ClaudeCode {
    private const val TOKEN_FILE = "/root/.cc_token"
    private const val OUT = "/root/.cc_login_out"
    private const val FIFO = "/root/.cc_login_fifo"

    private fun esc(s: String) = s.replace("'", "'\\''")

    suspend fun installed(context: Context): Boolean =
        Shell.run(context, "command -v claude >/dev/null 2>&1 && echo yes || echo no", 60_000).stdout.contains("yes")

    suspend fun connected(context: Context): Boolean =
        Shell.run(context, "[ -s $TOKEN_FILE ] && echo yes || echo no", 30_000).stdout.contains("yes")

    /**
     * Install the CLI. Returns an error message, or null on success.
     *
     * Not with `npm install -g`: the npm package now pulls in a ~380 MB native binary, and npm runs out of
     * memory unpacking it under PRoot on a phone ("Aborted"). Instead fetch that same platform package
     * straight from the npm registry with curl and stream just the binary out of it with tar. If that fails,
     * fall back to Anthropic's official native installer.
     */
    suspend fun install(context: Context, log: (String) -> Unit): String? {
        log("Installing Claude Code (about 100 MB download, a few minutes)…")
        val script = """
            set -o pipefail
            case "${'$'}(uname -m)" in
              aarch64|arm64) P=linux-arm64 ;;
              x86_64|amd64) P=linux-x64 ;;
              *) echo "This phone's CPU (${'$'}(uname -m)) has no Claude Code build."; echo ARIN_FAIL; exit 0 ;;
            esac
            V=${'$'}(curl -fsSL --retry 3 https://registry.npmjs.org/@anthropic-ai/claude-code/latest | grep -o '"version":"[^"]*"' | head -1 | cut -d'"' -f4)
            ok=0
            if [ -n "${'$'}V" ]; then
              echo "Downloading Claude Code ${'$'}V (${'$'}P)…"
              rm -rf /tmp/cc && mkdir -p /tmp/cc
              if curl -fL --retry 3 --retry-delay 3 -sS "https://registry.npmjs.org/@anthropic-ai/claude-code-${'$'}P/-/claude-code-${'$'}P-${'$'}V.tgz" \
                   | tar -xz -C /tmp/cc package/claude; then
                mkdir -p /usr/local/lib/arin-claude && install -m 755 /tmp/cc/package/claude /usr/local/lib/arin-claude/claude && ok=1
              fi
              rm -rf /tmp/cc
            fi
            if [ "${'$'}ok" != 1 ]; then
              echo "Registry download failed — trying Anthropic's installer…"
              curl -fsSL https://claude.ai/install.sh | bash 2>&1 | tail -n 5
              [ -x /root/.local/bin/claude ] && mkdir -p /usr/local/lib/arin-claude && ln -sf /root/.local/bin/claude /usr/local/lib/arin-claude/claude
            fi
            # Remove a broken npm attempt so it can't shadow the working binary.
            rm -f /usr/local/lib/node_modules/.bin/claude 2>/dev/null
            apt-get install -y ripgrep python3 >/dev/null 2>&1 || true
            if /usr/local/lib/arin-claude/claude --version 2>&1 | head -n 2; then echo ARIN_OK; else echo ARIN_FAIL; fi
        """.trimIndent()
        val r = Shell.run(context, script, timeoutMs = 25 * 60_000L)
        r.stdout.lines().filter { it.isNotBlank() && !it.contains("ARIN_OK") && !it.contains("ARIN_FAIL") }
            .takeLast(12).forEach(log)
        return if (r.stdout.contains("ARIN_OK")) { log("Claude Code installed ✓"); null }
        else "Install failed. " + (r.stdout.ifBlank { r.stderr }).trim().takeLast(300)
    }

    /** Start the browser login in the background. Returns the current output (should hold the OAuth URL). */
    suspend fun startLogin(context: Context): String {
        // `claude setup-token` only shows its sign-in link inside a terminal, so give it one with `script`.
        // ('setup-[t]oken' so pkill doesn't match, and kill, this very command.)
        Shell.run(
            context,
            "command -v script >/dev/null || apt-get install -y bsdutils >/dev/null 2>&1; " +
                "pkill -f 'claude setup-[t]oken' 2>/dev/null; rm -f $OUT $FIFO; mkfifo $FIFO; " +
                "( sleep 1800 > $FIFO & ) ; " +
                "setsid bash -c 'COLUMNS=500 LINES=40 TERM=xterm-256color DISABLE_AUTOUPDATER=1 " +
                "script -qfc \"stty cols 500 rows 40 2>/dev/null; claude setup-token\" /dev/null < $FIFO > $OUT 2>&1' " +
                ">/dev/null 2>&1 & echo started",
            60_000,
        )
        delay(3000)
        return loginOutput(context)
    }

    /** The login's screen output as plain text (terminal colour and cursor codes removed). */
    suspend fun loginOutput(context: Context): String =
        plain(Shell.run(context, "cat $OUT 2>/dev/null", 30_000).stdout)

    private fun plain(raw: String): String = raw
        .replace(Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)"), " ")   // OSC (links, titles)
        .replace(Regex("\u001B\\[[0-9;?>]*[ -/]*[@-~]"), " ")                       // CSI (colours, cursor moves)
        .replace(Regex("\u001B[()][0-9A-Za-z]"), "")
        .replace("\r", "")

    /** The OAuth URL printed by the login, if any. */
    fun urlIn(output: String): String? =
        Regex("https://claude\\.ai/oauth/authorize\\S*").find(output)?.value
            ?: Regex("https://[\\w.-]*(claude|anthropic)[\\w.-]*/[\\w./?=&%+~-]*oauth[\\w./?=&%+~-]*", RegexOption.IGNORE_CASE).find(output)?.value

    /** Feed the pasted code into the waiting login process. */
    suspend fun pasteCode(context: Context, code: String) {
        Shell.run(context, "printf '%s\\r' '${esc(code.trim())}' > $FIFO 2>/dev/null; echo ok", 30_000)
    }

    /** Look for the one-year token in the login output and save it. Returns true if captured. */
    suspend fun captureToken(context: Context): Boolean {
        val token = tokenIn(loginOutput(context)) ?: return false
        return saveToken(context, token)
    }

    private fun tokenIn(output: String): String? =
        Regex("sk-ant-[A-Za-z0-9_-]{20,}").find(output)?.value

    /** Save a token the person pasted (from `claude setup-token` run anywhere). */
    suspend fun saveToken(context: Context, token: String): Boolean {
        val t = tokenIn(token) ?: token.trim()
        if (t.isBlank()) return false
        return Shell.run(context, "printf '%s' '${esc(t)}' > $TOKEN_FILE && echo ok", 30_000).stdout.contains("ok")
    }

    suspend fun logout(context: Context) {
        Shell.run(context, "rm -f $TOKEN_FILE; pkill -f 'claude setup-[t]oken' 2>/dev/null; echo ok", 60_000)
    }

    /**
     * The brain Claude Code uses. [kind]: "anthropic" = the API speaks Anthropic's format (used directly);
     * "openai" = an OpenAI-compatible API, reached through Arin's local bridge; "pro" = the person's Claude
     * Pro/Max login (Google account) via the saved token.
     */
    data class Backend(val kind: String, val base: String, val key: String, val model: String)

    /** Claude Code's own model names for the Pro login. */
    val PRO_MODELS = listOf("sonnet", "opus", "haiku")

    // ---------- connecting a custom API: find its Anthropic endpoint and list its models ----------

    data class Connection(val kind: String, val base: String, val models: List<String>)

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val notChat = listOf("embed", "whisper", "tts", "transcribe", "moderation", "rerank", "dall-e", "image", "flux", "sdxl", "guard")

    private suspend fun get(url: String, headers: Map<String, String>): Pair<Int, String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
                http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
            }.getOrElse { 0 to (it.message ?: "network error") }
        }

    private suspend fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
            }.getOrElse { 0 to (it.message ?: "network error") }
        }

    private fun ids(body: String): List<String> = runCatching {
        val root = org.json.JSONTokener(body).nextValue()
        val arr = when (root) {
            is org.json.JSONObject -> root.optJSONArray("data") ?: root.optJSONArray("models")
            is org.json.JSONArray -> root
            else -> null
        } ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i)
            (o?.optString("id")?.takeIf { it.isNotBlank() } ?: o?.optString("name")?.takeIf { it.isNotBlank() } ?: arr.optString(i))
                .takeIf { it.isNotBlank() && !it.startsWith("{") }
        }.filter { id -> notChat.none { id.lowercase().contains(it) } }.distinct()
    }.getOrDefault(emptyList())

    /**
     * From just a URL and key: find where the API speaks Anthropic's format (what Claude Code needs) and list
     * its models. Throws with a readable message if it can't.
     */
    /** The API wouldn't list its models (some keys aren't allowed to): ask the person for the model id. */
    class NeedsModel(message: String) : Exception(message)

    /** The API said the key is wrong (not just "can't list models"). */
    class KeyRejected(message: String) : Exception(message)

    /** A short, readable piece of what the server said. */
    private fun serverSays(body: String): String {
        val msg = runCatching {
            val o = org.json.JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("message").takeIf { it.isNotBlank() }
                ?: o.optString("detail").takeIf { it.isNotBlank() }
        }.getOrNull() ?: body
        return msg.replace(Regex("\\s+"), " ").trim().take(160)
    }

    /**
     * From a URL and key (and a model id, if the API won't list its models): find how Claude Code can talk to
     * it and which models it has. Throws [NeedsModel] when the list is refused, else a readable error.
     */
    suspend fun connect(base: String, key: String, typedModel: String = ""): Connection {
        val k = key.trim().replace(Regex("\\s"), "")   // pasted keys sometimes carry spaces or line breaks
        val clean = base.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
        val root = clean.removeSuffix("/chat/completions").removeSuffix("/v1/messages").removeSuffix("/models").removeSuffix("/v1")
            .removeSuffix("/anthropic").removeSuffix("/v1").removeSuffix("/openai")
        val anthropicCandidates = (if (clean.endsWith("/anthropic")) listOf(clean) else listOf("$root/anthropic", root, clean)).distinct()
        val openAiCandidates = listOf(clean, "$root/v1", root).distinct()
        // Services differ in where they want the key; try the common ones.
        val keyHeaders = listOf(
            mapOf("Authorization" to "Bearer $k"),
            mapOf("x-api-key" to k),
            mapOf("api-key" to k),
        )
        val anth = mapOf("x-api-key" to k, "Authorization" to "Bearer $k", "anthropic-version" to "2023-06-01")
        var rejectedSays = ""
        var networkSays = ""

        // 1. Models.
        var models = emptyList<String>()
        var openAiBase: String? = null
        loop@ for (cand in openAiCandidates) for (h in keyHeaders) {
            val (code, body) = get("$cand/models", h)
            when {
                code == 0 -> networkSays = body
                code == 401 || code == 403 -> rejectedSays = serverSays(body)
                code in 200..299 -> { models = ids(body); if (models.isNotEmpty()) { openAiBase = cand; break@loop } }
            }
        }
        if (models.isEmpty()) for (cand in anthropicCandidates) {
            val (code, body) = get("$cand/v1/models", anth)
            if (code == 401 || code == 403) rejectedSays = serverSays(body)
            if (code in 200..299) { models = ids(body); if (models.isNotEmpty()) break }
        }
        val typed = typedModel.trim()
        if (models.isEmpty() && typed.isEmpty()) {
            if (networkSays.isNotBlank() && rejectedSays.isBlank()) throw IllegalStateException("Couldn't reach the API: $networkSays")
            // The server says the key itself is bad: no model id will help, so say that plainly.
            if (rejectedSays.isNotBlank() && Regex("key|token|auth|credential|revoked|expired|unauthori", RegexOption.IGNORE_CASE).containsMatchIn(rejectedSays)) {
                throw KeyRejected("Your API rejected this key: $rejectedSays")
            }
            throw NeedsModel(
                "This API didn't list its models" + (if (rejectedSays.isNotBlank()) " (it said: $rejectedSays)" else "") +
                    ". Type the model id you use with it (the same one as in the chat tab) and tap Connect again.",
            )
        }
        val probeModel = typed.ifEmpty { models.first() }

        // 2. Prefer an Anthropic-format endpoint (Claude Code's own format) when the API has one.
        val probe = """{"model":"$probeModel","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
        for (cand in anthropicCandidates) {
            val (code, body) = post("$cand/v1/messages", anth, probe)
            val anthropicShaped = body.replace(" ", "").let { it.contains("\"type\":\"message\"") || it.contains("\"type\":\"error\"") }
            if ((code in 200..299 || code == 400 || code == 422 || code == 429) && anthropicShaped) {
                return Connection("anthropic", cand, models.ifEmpty { listOf(typed) })
            }
        }

        // 3. OpenAI-compatible: check a real (1-token) chat works with this key and model, then use the bridge.
        val chatProbe = """{"model":"$probeModel","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
        var chatSays = ""
        for (cand in listOfNotNull(openAiBase) + openAiCandidates) {
            val (code, body) = post("$cand/chat/completions", mapOf("Authorization" to "Bearer $k"), chatProbe)
            if (code in 200..299 || code == 429) return Connection("openai", cand, models.ifEmpty { listOf(typed) })
            if (code == 401 || code == 403) throw KeyRejected("Your API rejected this key: ${serverSays(body)}")
            if (code != 404 && code != 405 && code != 0) chatSays = "HTTP $code: ${serverSays(body)}"
        }
        throw IllegalStateException(
            if (chatSays.isNotBlank()) "The API refused a test message — $chatSays"
            else "Couldn't find a chat endpoint at this URL. Try the base URL ending in /v1.",
        )
    }

    // ---------- the `claude` command in Arin's Linux uses the same brain ----------

    private const val REAL_BIN = "/usr/local/lib/arin-claude/claude"
    private const val BRIDGE_ENV = "/root/.arin/cc-bridge.env"

    /** `claude` in the terminal: starts Arin's bridge when the brain is an OpenAI-style API, then runs Claude Code. */
    private val WRAPPER = """
        #!/bin/bash
        # ARIN_WRAPPER — written by Arin. Your brain (API and model) is set in Arin: Settings → Claude Code.
        if [ -f $BRIDGE_ENV ] && ! curl -s -m 2 http://127.0.0.1:8787/health >/dev/null 2>&1; then
          ( set -a; . $BRIDGE_ENV; set +a; setsid python3 /root/.arin/cc-bridge.py >/dev/null 2>&1 & )
          for i in 1 2 3 4 5 6 7 8 9 10; do curl -s -m 1 http://127.0.0.1:8787/health >/dev/null 2>&1 && break; sleep 0.3; done
        fi
        exec $REAL_BIN "${'$'}@"
    """.trimIndent() + "\n"

    /** Every setting Arin manages in Claude Code's settings.json (others the person set are kept). */
    private val OWNED_ENV = listOf(
        "ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "ANTHROPIC_MODEL", "ANTHROPIC_SMALL_FAST_MODEL",
        "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL",
        "CLAUDE_CODE_SUBAGENT_MODEL", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC",
        "DISABLE_AUTOUPDATER", "DISABLE_TELEMETRY", "DISABLE_ERROR_REPORTING", "ENABLE_TOOL_SEARCH",
    )

    /**
     * Make the `claude` command in Arin's Linux use the brain chosen in Settings, exactly like the Claude Code tab:
     * writes ~/.claude/settings.json (which Claude Code reads on every start), the bridge config for OpenAI-style
     * APIs, and the `claude` launcher. [b] null = signed out (Arin's settings are removed).
     */
    suspend fun applyLinuxConfig(context: Context, b: Backend?) {
        val root = com.arin.terminal.Linux.rootfs(context)
        if (!java.io.File(root, "bin").exists()) return

        // 1. The launcher in front of the real program (moves an older install aside once).
        Shell.run(
            context,
            "mkdir -p /usr/local/lib/arin-claude /root/.arin /root/.claude; " +
                "if [ -e /usr/local/bin/claude ] && ! head -c 300 /usr/local/bin/claude | grep -q ARIN_WRAPPER; then " +
                "mv -f /usr/local/bin/claude $REAL_BIN; fi; echo ok",
            60_000,
        )
        java.io.File(root, "root/.arin").mkdirs()
        context.assets.open("cc-bridge.py").use { input -> java.io.File(root, "root/.arin/cc-bridge.py").outputStream().use { input.copyTo(it) } }
        if (java.io.File(root, REAL_BIN.removePrefix("/")).exists() || java.io.File(root, "root/.local/bin/claude").exists()) {
            java.io.File(root, "usr/local/bin/claude").apply { writeText(WRAPPER); setExecutable(true, false) }
        }

        // 2. Claude Code's own settings: the env block it applies on every start.
        val settingsFile = java.io.File(root, "root/.claude/settings.json").apply { parentFile?.mkdirs() }
        val settings = runCatching { org.json.JSONObject(settingsFile.readText()) }.getOrElse { org.json.JSONObject() }
        val env = settings.optJSONObject("env") ?: org.json.JSONObject()
        OWNED_ENV.forEach { env.remove(it) }
        val quiet = mapOf("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1", "DISABLE_AUTOUPDATER" to "1", "DISABLE_TELEMETRY" to "1",
            "DISABLE_ERROR_REPORTING" to "1", "ENABLE_TOOL_SEARCH" to "false")
        fun models(m: String) = listOf("ANTHROPIC_MODEL", "ANTHROPIC_SMALL_FAST_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL",
            "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL", "CLAUDE_CODE_SUBAGENT_MODEL").associateWith { m }
        val bridgeEnv = java.io.File(root, BRIDGE_ENV.removePrefix("/"))
        val bridgeBefore = runCatching { bridgeEnv.readText() }.getOrDefault("")
        when (b?.kind) {
            "pro" -> {
                val token = runCatching { java.io.File(root, TOKEN_FILE.removePrefix("/")).readText().trim() }.getOrDefault("")
                if (token.isNotEmpty()) env.put("CLAUDE_CODE_OAUTH_TOKEN", token)
                if (b.model.isNotBlank()) env.put("ANTHROPIC_MODEL", b.model)
                env.put("DISABLE_AUTOUPDATER", "1")
                bridgeEnv.delete()
            }
            "openai" -> {
                (mapOf("ANTHROPIC_BASE_URL" to "http://127.0.0.1:$BRIDGE_PORT", "ANTHROPIC_AUTH_TOKEN" to "arin-bridge") + models(b.model) + quiet)
                    .forEach { (k, v) -> env.put(k, v) }
                bridgeEnv.writeText(
                    "ARIN_UPSTREAM='${esc(b.base)}'\nARIN_KEY='${esc(b.key)}'\nARIN_PORT=$BRIDGE_PORT\n",
                )
                bridgeEnv.setReadable(false, false); bridgeEnv.setReadable(true, true)
            }
            "anthropic" -> {
                (mapOf("ANTHROPIC_BASE_URL" to b.base, "ANTHROPIC_AUTH_TOKEN" to b.key) + models(b.model) + quiet)
                    .forEach { (k, v) -> env.put(k, v) }
                bridgeEnv.delete()
            }
            else -> bridgeEnv.delete()
        }
        settings.put("env", env)
        settingsFile.writeText(settings.toString(2))

        // 3. Skip the first-run sign-in/theme screens: Arin has already set up the brain.
        val state = java.io.File(root, "root/.claude.json")
        val st = runCatching { org.json.JSONObject(state.readText()) }.getOrElse { org.json.JSONObject() }
        if (b != null) st.put("hasCompletedOnboarding", true)
        state.writeText(st.toString(2))

        // 4. Restart the bridge only if its API or key changed (so a running reply isn't cut off).
        val bridgeAfter = runCatching { bridgeEnv.readText() }.getOrDefault("")
        if (bridgeAfter != bridgeBefore) Shell.run(context, "pkill -f 'cc-[b]ridge.py' 2>/dev/null; echo ok", 30_000)
    }

    // ---------- the local bridge (Anthropic format -> OpenAI format) ----------

    private const val BRIDGE_PORT = 8787
    private const val BRIDGE = "/root/.arin/cc-bridge.py"

    /** (Re)start the bridge for this API. Its key goes in through the environment, never to disk. */
    private suspend fun startBridge(context: Context, b: Backend, thinkLess: Boolean = false): Boolean {
        val dir = java.io.File(com.arin.terminal.Linux.rootfs(context), "root/.arin").apply { mkdirs() }
        context.assets.open("cc-bridge.py").use { input -> java.io.File(dir, "cc-bridge.py").outputStream().use { input.copyTo(it) } }
        val r = Shell.run(
            context,
            "command -v python3 >/dev/null || apt-get install -y python3 >/dev/null 2>&1; " +
                "pkill -f 'cc-[b]ridge.py' 2>/dev/null; sleep 0.3; " +
                "ARIN_UPSTREAM='${esc(b.base)}' ARIN_KEY='${esc(b.key)}' ARIN_PORT=$BRIDGE_PORT ARIN_THINK_LESS=${if (thinkLess) 1 else 0} " +
                    "setsid python3 $BRIDGE >/dev/null 2>&1 & " +
                "for i in 1 2 3 4 5 6 7 8 9 10; do curl -s http://127.0.0.1:$BRIDGE_PORT/health >/dev/null && { echo up; break; }; sleep 0.5; done",
            90_000,
        )
        return r.stdout.contains("up")
    }

    // ---------- running Claude Code like a chat ----------

    private const val RUN_OUT = "/root/.cc_run.jsonl"
    private const val RUN_PROMPT = "/root/.cc_prompt.txt"
    private const val MCP_CONFIG = "/root/.arin/cc-mcp.json"
    private const val GIT_HELPER = "/root/.arin/git-credential-arin"

    /**
     * Start one turn in the background. The prompt goes in on stdin (no "no stdin data" wait), output is
     * stream-json so steps show live, and [continueChat] resumes the same Claude Code conversation.
     */
    /**
     * Extra powers for a turn: [mcpConfig] = Claude Code MCP config JSON (Arin's tools, REA, the person's servers),
     * [mcpServers] = their names (all their tools are allowed), [githubToken] = lets git push / gh work.
     */
    data class Extras(
        val mcpConfig: String = "",
        val mcpServers: List<String> = emptyList(),
        val githubToken: String = "",
        /** Settings → Think less: brief reasoning, fewer output tokens. */
        val thinkLess: Boolean = false,
    )

    /**
     * Tools Claude Code would describe in every request that aren't useful on a phone (sub-agents, schedules,
     * worktrees…). Leaving them out cut each request from about 23,000 to 7,600 tokens in testing.
     * WebSearch only works on Anthropic's own servers, so it's dropped for custom APIs (Arin's web_search covers it).
     */
    private val HEAVY_TOOLS = listOf(
        "Agent", "Task", "CronCreate", "CronDelete", "CronList", "EnterWorktree", "ExitWorktree", "ListAgents", "Monitor",
        "NotebookEdit", "ReportFindings", "ScheduleWakeup", "SendMessage", "Skill", "TaskStop", "Workflow",
    )

    suspend fun startTurn(context: Context, prompt: String, b: Backend, continueChat: Boolean, extras: Extras = Extras()): Boolean {
        val m = esc(b.model)
        // ENABLE_TOOL_SEARCH=false: offer every tool (Arin's, REA's) directly; non-Claude models don't know to search for them.
        val quiet = "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 DISABLE_AUTOUPDATER=1 DISABLE_TELEMETRY=1 DISABLE_ERROR_REPORTING=1 ENABLE_TOOL_SEARCH=false"
        val models = "ANTHROPIC_MODEL='$m' ANTHROPIC_SMALL_FAST_MODEL='$m' ANTHROPIC_DEFAULT_OPUS_MODEL='$m' " +
            "ANTHROPIC_DEFAULT_SONNET_MODEL='$m' ANTHROPIC_DEFAULT_HAIKU_MODEL='$m' CLAUDE_CODE_SUBAGENT_MODEL='$m'"
        val env = when (b.kind) {
            // Claude Pro/Max login: Anthropic's own servers with the saved one-year token.
            "pro" -> "unset ANTHROPIC_API_KEY ANTHROPIC_BASE_URL ANTHROPIC_AUTH_TOKEN ANTHROPIC_MODEL; " +
                "export CLAUDE_CODE_OAUTH_TOKEN=$(cat $TOKEN_FILE 2>/dev/null) DISABLE_AUTOUPDATER=1; "
            // OpenAI-compatible API: through the local bridge.
            "openai" -> {
                if (!startBridge(context, b, extras.thinkLess)) return false
                "unset CLAUDE_CODE_OAUTH_TOKEN ANTHROPIC_API_KEY; " +
                    "export ANTHROPIC_BASE_URL='http://127.0.0.1:$BRIDGE_PORT' ANTHROPIC_AUTH_TOKEN='arin-bridge' $models $quiet; "
            }
            // Anthropic-compatible API (e.g. DeepSeek's /anthropic): directly.
            else -> "unset CLAUDE_CODE_OAUTH_TOKEN ANTHROPIC_API_KEY; " +
                "export ANTHROPIC_BASE_URL='${esc(b.base)}' ANTHROPIC_AUTH_TOKEN='${esc(b.key)}' $models $quiet; "
        }
        val b64 = android.util.Base64.encodeToString(prompt.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val root = com.arin.terminal.Linux.rootfs(context)
        java.io.File(root, "root/.arin").mkdirs()
        // MCP servers: Arin's own features, REA when on, and the person's servers.
        val mcpFlag = if (extras.mcpConfig.isNotBlank()) {
            java.io.File(root, MCP_CONFIG.removePrefix("/")).apply { writeText(extras.mcpConfig); setReadable(false, false); setReadable(true, true) }
            "--mcp-config $MCP_CONFIG "
        } else ""
        // GitHub: the person's sign-in from Settings, so `git push` and `gh` work. Given only to this process.
        val gh = if (extras.githubToken.isNotBlank()) {
            java.io.File(root, GIT_HELPER.removePrefix("/")).apply {
                writeText("#!/bin/sh\n[ \"$1\" = get ] || exit 0\necho username=x-access-token\necho \"password=${'$'}GH_TOKEN\"\n")
                setExecutable(true, false)
            }
            "export GH_TOKEN='${esc(extras.githubToken)}' GITHUB_TOKEN='${esc(extras.githubToken)}'; "
        } else ""
        val gitSetup = if (extras.githubToken.isNotBlank())
            "command -v git >/dev/null || apt-get install -y git >/dev/null 2>&1; " +
                "git config --global credential.https://github.com.helper $GIT_HELPER; " +
                "git config --global user.name >/dev/null || git config --global user.name 'Arin'; " +
                "git config --global user.email >/dev/null || git config --global user.email 'arin@users.noreply.github.com'; "
        else ""
        val pro = b.kind == "pro"
        val tools = (listOf("Bash", "Read", "Edit", "Write", "MultiEdit", "Glob", "Grep", "WebFetch", "TodoWrite") +
            (if (pro) listOf("WebSearch") else emptyList()) + extras.mcpServers.map { "mcp__$it" }).joinToString(" ")
        val dropped = (HEAVY_TOOLS + if (pro) emptyList() else listOf("WebSearch")).joinToString(" ")
        val flags = (if (continueChat) "--continue " else "") +
            "--model '$m' --output-format stream-json --verbose --include-partial-messages " + mcpFlag +
            "--disallowedTools $dropped --allowedTools $tools"
        val thinking = if (extras.thinkLess) "export MAX_THINKING_TOKENS=0; " else ""
        val r = Shell.run(
            context,
            "pkill -f 'claude -[p]' 2>/dev/null; echo '$b64' | base64 -d > $RUN_PROMPT; rm -f $RUN_OUT; " +
                "mkdir -p /root/files; cd /root/files; " + gitSetup +
                "setsid bash -c \"$env $gh $thinking claude -p $flags < $RUN_PROMPT > $RUN_OUT 2>&1; echo '{\\\"type\\\":\\\"arin_end\\\"}' >> $RUN_OUT\" >/dev/null 2>&1 & echo started",
            60_000,
        )
        return r.stdout.contains("started")
    }

    /** Stop the running turn. Writes the end marker itself, since the wrapper that would is stopped too. */
    suspend fun stopTurn(context: Context) {
        Shell.run(context, "pkill -f 'claude -[p]' 2>/dev/null; sleep 0.5; echo '{\"type\":\"arin_end\"}' >> $RUN_OUT; echo ok", 30_000)
    }

    /** What the running turn has produced so far: like a chat reply (text, thinking, the steps it ran). */
    data class Turn(
        val text: String,
        val thinking: String,
        val runs: List<com.arin.data.CommandRun>,
        val status: String?,
        val done: Boolean,
        val error: Boolean,
    )

    suspend fun readTurn(context: Context): Turn {
        val raw = Shell.run(context, "cat $RUN_OUT 2>/dev/null", 30_000).stdout
        return parseTurn(raw)
    }

    fun parseTurn(raw: String): Turn {
        val text = StringBuilder()
        val thinking = StringBuilder()
        var partialText = StringBuilder()
        var partialThinking = StringBuilder()
        val calls = LinkedHashMap<String, Pair<String, String>>()   // tool id -> (name, description)
        val results = HashMap<String, Pair<String, Boolean>>()       // tool id -> (output, isError)
        var status: String? = null
        var done = false
        var error = false
        var result: String? = null
        val plain = StringBuilder()
        fun addText(sb: StringBuilder, t: String) { if (t.isBlank()) return; if (sb.isNotEmpty()) sb.append("\n\n"); sb.append(t.trim()) }
        raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val o = runCatching { org.json.JSONObject(line) }.getOrNull()
            if (o == null) {
                if (!line.startsWith("[claude-code:")) plain.append(line).append('\n')
                return@forEach
            }
            when (o.optString("type")) {
                // Live deltas while a message is being written.
                "stream_event" -> {
                    val ev = o.optJSONObject("event") ?: return@forEach
                    when (ev.optString("type")) {
                        "message_start" -> { partialText = StringBuilder(); partialThinking = StringBuilder() }
                        "content_block_delta" -> {
                            val d = ev.optJSONObject("delta") ?: return@forEach
                            when (d.optString("type")) {
                                "text_delta" -> { partialText.append(d.optString("text")); status = "Writing…" }
                                "thinking_delta" -> { partialThinking.append(d.optString("thinking")); status = "Thinking…" }
                            }
                        }
                    }
                }
                // A finished message: its text, thinking and tool calls.
                "assistant" -> {
                    partialText = StringBuilder(); partialThinking = StringBuilder()
                    val content = o.optJSONObject("message")?.optJSONArray("content") ?: return@forEach
                    for (i in 0 until content.length()) {
                        val c = content.optJSONObject(i) ?: continue
                        when (c.optString("type")) {
                            "text" -> addText(text, c.optString("text"))
                            "thinking" -> addText(thinking, c.optString("thinking"))
                            "tool_use" -> {
                                val d = describeTool(c.optString("name"), c.optJSONObject("input"))
                                calls[c.optString("id")] = c.optString("name") to d
                                status = d
                            }
                        }
                    }
                }
                // Tool results come back as a user message.
                "user" -> {
                    val content = o.optJSONObject("message")?.optJSONArray("content") ?: return@forEach
                    for (i in 0 until content.length()) {
                        val c = content.optJSONObject(i) ?: continue
                        if (c.optString("type") != "tool_result") continue
                        val out = when (val v = c.opt("content")) {
                            is org.json.JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it)?.optString("text") }.joinToString("\n")
                            null -> ""
                            else -> v.toString()
                        }
                        results[c.optString("tool_use_id")] = out to c.optBoolean("is_error")
                        status = "Working…"
                    }
                }
                "result" -> {
                    done = true
                    error = o.optBoolean("is_error")
                    result = o.optString("result").takeIf { it.isNotBlank() }
                }
                "arin_end" -> done = true
            }
        }
        val runs = calls.map { (id, nd) ->
            val (name, desc) = nd
            val res = results[id]
            com.arin.data.CommandRun(
                command = desc,
                output = res?.first?.take(4000) ?: "",
                exitCode = res?.let { if (it.second) 1 else 0 },
                kind = if (name == "Bash") "terminal" else "tool",
            )
        }
        val allText = StringBuilder(text).also { addText(it, partialText.toString()) }.toString()
        val allThinking = StringBuilder(thinking).also { addText(it, partialThinking.toString()) }.toString()
        val finalText = when {
            allText.isNotEmpty() -> allText
            result != null -> result!!
            else -> plain.toString().trim()
        }
        return Turn(
            finalText, allThinking, runs, if (done) null else status, done,
            error || (done && allText.isEmpty() && result == null && plain.isNotBlank()),
        )
    }

    private fun describeTool(name: String, input: org.json.JSONObject?): String {
        fun arg(k: String) = input?.optString(k).orEmpty()
        return when (name) {
            "Bash" -> "$ " + arg("command").lines().first().take(80)
            "Read" -> "Read " + arg("file_path").substringAfterLast('/')
            "Edit", "MultiEdit" -> "Edit " + arg("file_path").substringAfterLast('/')
            "Write" -> "Write " + arg("file_path").substringAfterLast('/')
            "Glob", "Grep" -> "$name " + arg("pattern").take(60)
            "WebFetch" -> "Fetch " + arg("url").take(60)
            "WebSearch" -> "Search " + arg("query").take(60)
            "TodoWrite" -> "Updated the plan"
            else -> if (name.startsWith("mcp__")) {
                val server = name.removePrefix("mcp__").substringBefore("__")
                val t = name.substringAfterLast("__").replace('_', ' ')
                val first = input?.keys()?.asSequence()?.firstOrNull()?.let { input.optString(it) }.orEmpty().take(60)
                (if (server == "arin") "" else "$server: ") + t + if (first.isNotBlank()) " — $first" else ""
            } else name
        }
    }
}
