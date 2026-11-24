package com.arin.seclab

/** Recognises the common hash formats so the right cracker mode can be picked. */
object HashId {

    data class Match(val name: String, val mode: String, val hint: String)

    private val hex = Regex("^[A-Fa-f0-9]+$")

    fun identify(text: String): List<Match> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val out = mutableListOf<Match>()
        when {
            t.startsWith("$2a$") || t.startsWith("$2b$") || t.startsWith("$2y$") ->
                out += Match("bcrypt", "{0}", "hashcat -m 3200, john --format=bcrypt")
            t.startsWith("\$argon2") -> out += Match("Argon2", "argon2", "hashcat -m 3500 / john argon2")
            t.startsWith("$6$") -> out += Match("SHA-512 crypt", "{6}", "hashcat -m 1800, john sha512crypt")
            t.startsWith("$5$") -> out += Match("SHA-256 crypt", "{5}", "hashcat -m 7400")
            t.startsWith("$1$") -> out += Match("MD5 crypt (unix)", "{1}", "hashcat -m 500, john md5crypt")
            t.startsWith("{SSHA}") || t.startsWith("{SHA}") -> out += Match("LDAP salted SHA", "ldap", "john --format=netldap")
            t.startsWith("sha1$") -> out += Match("SHA1 crypt", "{SHA}", "hashcat -m 100")
            hex.matches(t) -> when (t.length) {
                32 -> out += Match("MD5 or NTLM", "{0} / 1000", "32 hex characters")
                40 -> out += Match("SHA-1", "100", "40 hex characters")
                56 -> out += Match("SHA-224", "1300", "56 hex characters")
                64 -> out += Match("SHA-256", "1400", "64 hex characters")
                96 -> out += Match("SHA-384", "10800", "96 hex characters")
                128 -> out += Match("SHA-512", "1700", "128 hex characters")
                else -> if (t.length >= 16) out += Match("Hex hash of unknown length", "-", "${t.length} hex characters")
            }
            t.count { it == '$' } >= 3 -> out += Match("Modular crypt format", "-", "prefix separated hash")
        }
        if (t.length in 13..16 && t.none { it == '$' } && t.all { it.isLetterOrDigit() || it in "./" }) {
            out += Match("Possible DES crypt / old UNIX hash", "1500", "13 short characters")
        }
        return out
    }
}
