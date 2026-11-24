package com.arin.seclab

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/** Small CyberChef-style converter: encode, decode and detect. Nothing leaves the phone. */
object Codec {

    enum class Kind(val label: String) { Base64("Base64"), Base32("Base32"), Hex("Hex"), Url("URL"), Rot13("ROT13") }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(kind: Kind, text: String): String = when (kind) {
        Kind.Base64 -> Base64.getEncoder().encodeToString(text.toByteArray())
        Kind.Base32 -> base32(text.toByteArray())
        Kind.Hex -> text.toByteArray().joinToString("") { b -> "%02x".format(b) }
        Kind.Url -> URLEncoder.encode(text, "UTF-8")
        Kind.Rot13 -> rot13(text)
    }

    /** Decoded text, or null when [text] isn't valid for [kind]. */
    fun decode(kind: Kind, text: String): String? = runCatching {
        when (kind) {
            Kind.Base64 -> String(Base64.getMimeDecoder().decode(text.trim()))
            Kind.Base32 -> String(unbase32(text.trim()))
            Kind.Hex -> hexBytes(text.trim())?.let { String(it) }
            Kind.Url -> URLDecoder.decode(text.trim(), "UTF-8")
            Kind.Rot13 -> rot13(text)
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() && it.all { c -> c == '\n' || c == '\t' || !c.isISOControl() } }

    /** Guesses the encodings [text] could be, most likely first. */
    fun detect(text: String): List<Kind> {
        val t = text.trim()
        if (t.length < 4) return emptyList()
        val out = mutableListOf<Kind>()
        val hexish = t.length % 2 == 0 && t.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (hexish && decode(Kind.Hex, t) != null) out += Kind.Hex
        val b64chars = t.all { it.isLetterOrDigit() || it in "+/=_- \n\r" }
        if (b64chars && t.length % 4 == 0 && t.any { it.isUpperCase() } && decode(Kind.Base64, t) != null) out += Kind.Base64
        val b32chars = t.all { it.isLetterOrDigit() || it == '=' }
        if (b32chars && t.any { it.isUpperCase() } && t.length % 8 == 0 && decode(Kind.Base32, t) != null) out += Kind.Base32
        if (t.any { it == '%' } && decode(Kind.Url, t) != null) out += Kind.Url
        if (t.any { it.isLetter() } && rot13(t) != t) out += Kind.Rot13
        return out
    }

    /** Detects, decodes and says what it was; null when nothing matched. */
    fun detectAndDecode(text: String): Pair<Kind, String>? {
        for (k in detect(text)) decode(k, text)?.let { return k to it }
        return null
    }

    private fun rot13(s: String) = s.map { c ->
        when {
            c in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
            c in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
            else -> c
        }
    }.joinToString("")

    private fun hexBytes(s: String): ByteArray? {
        if (s.length % 2 != 0 || s.isEmpty()) return null
        return ByteArray(s.length / 2) { i ->
            val hi = hex(s[i * 2]); val lo = hex(s[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            ((hi shl 4) or lo).toByte()
        }
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun base32(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                sb.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) sb.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        while (sb.length % 8 != 0) sb.append('=')
        return sb.toString()
    }

    private fun unbase32(s: String): ByteArray {
        val clean = s.uppercase().filter { it != '=' && it != '\n' && it != '\r' }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) throw IllegalArgumentException("not base32")
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
