package com.arin.seclab

/**
 * Validation of scan targets and reading of open ports out of scanner output. Pure Kotlin so both can be
 * unit-tested without a phone.
 */
object Targets {

    /** The outcome of checking a typed target. */
    data class Check(val ok: Boolean, val target: String = "", val reason: String = "") {
        val isLocal: Boolean
            get() {
                val host = target.substringBefore('/')
                return target == "localhost" || host.startsWith("10.") || host.startsWith("192.168.") ||
                    Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host) || host.startsWith("127.")
            }
    }

    private val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val hostname = Regex("^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")
    private val allowed = Regex("^[A-Za-z0-9.:/_\\-]+$")

    /** A single address or name (no range). */
    fun single(raw: String): Check {
        val c = check(raw)
        if (c.ok && c.target.contains('/')) return Check(false, c.target, "Use a single address here, not a range")
        return c
    }

    /** Checks [raw] as an IPv4/IPv6 address, a hostname, or one of those with a /prefix range. */
    fun check(raw: String): Check {
        val t = raw.trim()
        if (t.isEmpty()) return Check(false, reason = "Type an IP address, hostname or range")
        if (t.length > 253) return Check(false, reason = "That is longer than any real address")
        if (!allowed.matches(t)) return Check(false, reason = "Only letters, digits, dots, colons, hyphens and / are allowed")
        if (t.startsWith("/") || t.endsWith("/") || t.contains("//")) return Check(false, reason = "Check the / in the range")

        val host = t.substringBefore('/')
        val bits = t.substringAfter('/', "").let { if ('/' in t) it else null }
        if (host.isEmpty()) return Check(false, reason = "The range has no address in front of the /")

        val family = when {
            ipv4.matches(host) -> {
                if (ipv4.find(host)!!.groupValues.drop(1).any { it.toIntOrNull() == null || it.toInt() > 255 }) "bad"
                else "v4"
            }
            host.contains(':') -> "v6"
            hostname.matches(host) && host.any { it.isLetter() } -> "name"
            else -> "bad"
        }
        if (family == "bad") return Check(false, reason = "Not a valid address: $host")

        if (bits != null) {
            if (bits.isEmpty() || bits.any { !it.isDigit() }) return Check(false, reason = "The part after / must be a number")
            val max = if (family == "v6") 128 else 32
            val n = bits.toIntOrNull() ?: return Check(false, reason = "The part after / is too big")
            if (n < 1 || n > max) return Check(false, reason = "A ${if (family == "v6") "IPv6" else "IPv4"} range goes from /1 to /$max")
            if (family == "name") return Check(false, reason = "Ranges need an IP address, not a name")
        }
        return Check(true, t)
    }

    /** A single word made safe to paste into a command. */
    fun safe(raw: String): String = raw.trim().filter { it.isLetterOrDigit() || it in ".:/_-@+%=" }

    /** What a port scan found. */
    data class PortReport(val openPorts: List<Int>, val services: Map<Int, String>) {
        val summary: String
            get() = if (openPorts.isEmpty()) "no open ports found"
            else openPorts.size.toString() + " open port" + (if (openPorts.size == 1) " " else "s ") +
                openPorts.joinToString(", ") { p -> services[p]?.let { "$p/$it" } ?: p.toString() }
    }

    // 22/tcp open ssh OpenSSH 8.2  |  80/tcp  open  http
    private val nmapLine = Regex("""^\s*(\d{1,5})/(tcp|udp)\s+open\s+([A-Za-z0-9_.-]+)?""")
    // Discovered open port 443/tcp on 10.0.0.1  |  masscan style
    private val discovered = Regex("""Discovered open port (\d{1,5})/(tcp|udp)(?: on \S+)?\s*(?:\(([^)]*)\))?""", RegexOption.IGNORE_CASE)
    // 443/tcp   open   https
    private val plain = Regex("""^\s*(\d{1,5})/(tcp|udp)\s+open\b""")

    /** Collects every open port and service name mentioned in scanner [output]. */
    fun openPorts(output: String): PortReport {
        val services = linkedMapOf<Int, String>()
        val found = linkedSetOf<Int>()
        output.lineSequence().forEach { line ->
            val hits = listOfNotNull(nmapLine.find(line) ?: discovered.find(line) ?: plain.find(line))
            hits.forEach { m ->
                val port = m.groupValues[1].toIntOrNull() ?: return@forEach
                if (port !in 1..65535) return@forEach
                found += port
                val service = m.groupValues.getOrNull(3)?.trim().orEmpty().ifBlank { m.groupValues.getOrNull(2).orEmpty() }
                if (service.isNotBlank() && service != "tcp" && service != "udp") services[port] = service.take(24)
            }
        }
        return PortReport(found.sorted(), services)
    }

    /** The ports to hand to the follow-up service scan. */
    fun portList(report: PortReport): String = report.openPorts.joinToString(",")
}
