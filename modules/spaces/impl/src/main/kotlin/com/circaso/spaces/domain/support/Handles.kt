package com.circaso.spaces.domain.support

/**
 * Space handles (their own namespace, separate from people's): 3 to 30 of `a-z`, `0-9`, `.` and `_`, starting with a
 * letter, kept lowercase. The link is `circaso.app/s/{handle}`.
 */
object Handles {
    private val SHAPE = Regex("^[a-z][a-z0-9._]{2,29}$")

    /** Reserved so nobody can pose as Circaso. */
    private val RESERVED = setOf("circaso", "support", "help", "admin", "official", "spaces", "settings")

    fun normalise(raw: String): String = raw.trim().removePrefix("@").lowercase()

    fun isValid(handle: String): Boolean = SHAPE.matches(handle) && handle !in RESERVED

    /** Candidates to offer when [handle] is taken (SN1b), in the order the frame shows them. */
    fun candidates(handle: String, name: String): List<String> {
        val base = handle.ifBlank { name.lowercase().filter { it.isLetterOrDigit() } }.take(24).ifBlank { "space" }
        val word =
            name
                .lowercase()
                .split(
                    ' ',
                ).firstOrNull { it.isNotBlank() }
                ?.filter { it.isLetterOrDigit() }
                .orEmpty()
        return listOf(
            "$base.ke",
            if (word.isNotEmpty() && !base.startsWith(word)) "$word$base".take(30) else "the$base",
            "${base}hub",
            "${base}_official",
            "$base.co",
            "${base}2",
        ).map { it.take(30) }.filter { isValid(it) }.distinct()
    }
}
