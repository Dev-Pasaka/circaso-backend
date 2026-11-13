package com.circaso.support.domain.support

import java.security.SecureRandom

/**
 * `DL-7K3QX9`, the code a person reads out to support for a diagnostic log (2026-11-13). Six characters from an
 * alphabet without 0, O, 1, I, L or U, so it survives being read over the phone: about 729 million codes.
 */
object DiagnosticCodes {
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val LENGTH = 6
    private const val PREFIX = "DL-"
    private val random = SecureRandom()

    fun next(): String = PREFIX + (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /** A code as typed (any case, spaces, `DL-` optional) in its stored form; null when it can't be one. */
    fun normalize(raw: String): String? {
        val bare = raw.trim().uppercase().removePrefix(PREFIX).removePrefix("DL").trim().removePrefix("-")
        if (bare.length != LENGTH || bare.any { it !in ALPHABET }) return null
        return PREFIX + bare
    }
}

/**
 * The server's own pass over a diagnostic log before it is stored (2026-11-13). The phone scrubs every line already;
 * this catches what an older or broken build might still send: tokens, emails and phone numbers.
 */
object LogScrub {
    private val jwt = Regex("""eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*""")
    private val bearer = Regex("""(?i)\bbearer\s+\S+""")
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val phone = Regex("""\+\d[\d\s-]{6,16}\d""")

    fun scrub(text: String): String {
        var s = jwt.replace(text, "[token]")
        s = bearer.replace(s, "Bearer [token]")
        s = email.replace(s, "[email]")
        return phone.replace(s, "[phone]")
    }
}
