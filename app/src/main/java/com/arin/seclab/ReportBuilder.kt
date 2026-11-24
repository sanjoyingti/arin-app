package com.arin.seclab

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds a session report out of the saved scans and APK findings. */
object ReportBuilder {

    private val stamp = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH)

    fun markdown(title: String, target: String, scans: List<ScanRecord>, findings: List<String>, tools: List<String>): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("- Generated: ${stamp.format(Date())}")
        if (target.isNotBlank()) appendLine("- Main target: `$target`")
        appendLine("- Runs made: ${scans.size}")
        appendLine()
        appendLine("> All scans were made from the phone against targets the tester is allowed to test.")
        appendLine()
        if (findings.isNotEmpty()) {
            appendLine("## APK findings")
            findings.forEach { appendLine("- $it") }
            appendLine()
        }
        if (tools.isNotEmpty()) {
            appendLine("## Tools used")
            tools.forEach { appendLine("- $it") }
            appendLine()
        }
        appendLine("## Runs")
        if (scans.isEmpty()) appendLine("_Nothing captured yet._")
        scans.forEach { s ->
            appendLine()
            appendLine("### ${s.kind} — `${s.target}`")
            appendLine("- When: ${stamp.format(Date(s.createdAt))}")
            appendLine("- Result: ${s.summary}")
            appendLine()
            appendLine("```")
            appendLine(s.output.take(20_000).trimEnd())
            appendLine("```")
        }
    }

    /** Very small Markdown to HTML, code blocks included. */
    fun html(markdown: String): String {
        val sb = StringBuilder()
        var inCode = false
        for (raw in markdown.lines()) {
            when {
                raw.startsWith("```") -> {
                    sb.append(if (inCode) "</pre>" else "<pre>")
                    inCode = !inCode
                }
                inCode -> sb.append(escape(raw)).append('\n')
                raw.startsWith("### ") -> sb.append("<h3>").append(inline(raw.removePrefix("### "))).append("</h3>\n")
                raw.startsWith("## ") -> sb.append("<h2>").append(inline(raw.removePrefix("## "))).append("</h2>\n")
                raw.startsWith("# ") -> sb.append("<h1>").append(inline(raw.removePrefix("# "))).append("</h1>\n")
                raw.startsWith("> ") -> sb.append("<blockquote>").append(inline(raw.removePrefix("> "))).append("</blockquote>\n")
                raw.startsWith("- ") -> sb.append("<li>").append(inline(raw.removePrefix("- "))).append("</li>\n")
                raw.isBlank() -> sb.append("<br>\n")
                else -> sb.append("<p>").append(inline(raw)).append("</p>\n")
            }
        }
        if (inCode) sb.append("</pre>")
        return """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            |<style>
            |body{background:#05080a;color:#c9ffd8;font:14px/1.5 monospace;padding:16px}
            |h1,h2,h3{color:#39ff14} a{color:#00e5ff}
            |pre{background:#0c1310;border:1px solid #1d2b24;padding:10px;overflow:auto;white-space:pre-wrap;word-break:break-word}
            |blockquote{border-left:3px solid #00e5ff;margin:0;padding-left:10px;color:#8fd3b6}
            |li{margin-left:18px}
            |</style></head><body>
            |""".trimMargin() + sb + "</body></html>"
    }

    private fun inline(s: String): String =
        escape(s).replace(Regex("`([^`]+)`"), "<code>$1</code>").replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
