package com.arin.claudecode

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Arin's own features as an MCP server for Claude Code (web search, pages, pictures/videos, memory, Movie Maker,
 * building APKs). Streamable HTTP with plain JSON replies, on 127.0.0.1 only, and every request must carry the
 * secret token, so other apps on the phone can't use it. Arin's Linux shares the phone's network, so Claude Code
 * reaches it at http://127.0.0.1:[PORT]/mcp.
 */
object ArinMcp {
    const val PORT = 8790
    private const val PROTOCOL = "2025-03-26"

    /** Runs one tool: returns (text for the model, isError). */
    @Volatile private var handler: (suspend (String, JSONObject) -> Pair<String, Boolean>)? = null

    @Volatile private var server: ServerSocket? = null
    @Volatile private var token: String = ""

    private fun tool(name: String, description: String, props: Map<String, String>, required: List<String> = props.keys.toList()) =
        JSONObject().put("name", name).put("description", description).put(
            "inputSchema",
            JSONObject().put("type", "object").put(
                "properties",
                JSONObject().apply { props.forEach { (k, d) -> put(k, JSONObject().put("type", "string").put("description", d)) } },
            ).put("required", JSONArray(required)),
        )

    private val TOOLS = JSONArray(
        listOf(
            tool("web_search", "Search the web (works with any brain). Returns results with page text.", mapOf("query" to "What to search for")),
            tool("read_page", "Read a web page, document or PDF at a URL and return its text.", mapOf("url" to "https:// address")),
            tool("find_images", "Find pictures of something; they are shown to the person under your reply.", mapOf("query" to "What to show")),
            tool("find_videos", "Find videos of something; they are shown to the person under your reply.", mapOf("query" to "What to show")),
            tool("remember", "Save a lasting fact about the person to Arin's memory.", mapOf("fact" to "The fact, in one sentence")),
            tool("recall", "List what Arin remembers about the person.", emptyMap(), emptyList()),
            tool(
                "make_movie",
                "Make a movie in Arin's Movie Maker from a script or idea (it plans, renders and edits it; progress shows there).",
                mapOf("script" to "The full script or the film idea"),
            ),
            tool("movie_status", "How the Movie Maker film is going.", emptyMap(), emptyList()),
            tool(
                "build_app",
                "Build an Android app (APK) with Arin's Build mode on GitHub; it opens in the Build tab with progress and download link.",
                mapOf("name" to "App name", "description" to "Everything the app must do"),
            ),
        ),
    )

    /** Start (once) with the person's [secret]; later calls only update the handler. */
    @Synchronized
    fun start(secret: String, h: suspend (String, JSONObject) -> Pair<String, Boolean>) {
        token = secret
        handler = h
        if (server?.isClosed == false) return
        val ss = runCatching { ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1")) }.getOrNull() ?: return
        server = ss
        thread(name = "arin-mcp", isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: continue
                thread(isDaemon = true) { runCatching { serve(s) }; runCatching { s.close() } }
            }
        }
    }

    private fun serve(s: Socket) {
        s.soTimeout = 15 * 60_000
        val input = BufferedInputStream(s.getInputStream())
        val head = ByteArrayOutputStream()
        // Headers end at \r\n\r\n.
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 64 * 1024) return
        }
        val lines = head.toString(Charsets.UTF_8.name()).split("\r\n")
        val (method, path) = lines.first().split(" ").let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length).also { var off = 0; while (off < length) { val n = input.read(it, off, length - off); if (n < 0) break; off += n } }
        val out = s.getOutputStream()
        fun reply(code: Int, status: String, json: String?) {
            val bytes = json?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val h = "HTTP/1.1 $code $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            out.write(h.toByteArray()); out.write(bytes); out.flush()
        }
        if (headers["authorization"] != "Bearer $token" || token.isBlank()) return reply(401, "Unauthorized", """{"error":"unauthorized"}""")
        if (!path.startsWith("/mcp")) return reply(404, "Not Found", null)
        if (method == "GET") return reply(405, "Method Not Allowed", null)   // no server-to-client stream
        if (method == "DELETE") return reply(200, "OK", null)
        val req = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return reply(400, "Bad Request", null)
        if (!req.has("id")) return reply(202, "Accepted", null)   // a notification
        val id = req.get("id")
        val result: JSONObject = when (req.optString("method")) {
            "initialize" -> JSONObject()
                .put("protocolVersion", req.optJSONObject("params")?.optString("protocolVersion")?.ifBlank { null } ?: PROTOCOL)
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "arin").put("version", "1.0"))
                .put("instructions", "Arin's app features: web search, reading pages, pictures/videos shown to the person, memory, Movie Maker and building Android apps.")
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", TOOLS)
            "tools/call" -> {
                val p = req.optJSONObject("params") ?: JSONObject()
                val (text, isError) = runCatching {
                    runBlocking { handler?.invoke(p.optString("name"), p.optJSONObject("arguments") ?: JSONObject()) ?: ("Arin isn't ready." to true) }
                }.getOrElse { ("Error: ${it.message}" to true) }
                JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))).put("isError", isError)
            }
            else -> return reply(
                200, "OK",
                JSONObject().put("jsonrpc", "2.0").put("id", id)
                    .put("error", JSONObject().put("code", -32601).put("message", "Method not found")).toString(),
            )
        }
        reply(200, "OK", JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())
    }
}
