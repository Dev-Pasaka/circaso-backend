package com.circaso.status.domain.support

/** What an older app's `reacted: true` means (Pasaka, 2026-11-03): the red heart, with its emoji presentation. */
const val HEART = "❤️"

/** The most a reaction may take in UTF-8: room for a skin-toned or joined pair, never a sentence. */
const val REACTION_MAX_BYTES = 16

private const val ZERO_WIDTH_JOINER = 0x200D
private const val VARIATION_SELECTOR_16 = 0xFE0F
private const val COMBINING_KEYCAP = 0x20E3
private const val ASCII_END = 0x80

/** Characters that never belong in an emoji (a joiner and the keycap mark are let through before this). */
private val REFUSED_TYPES =
    setOf(
        Character.CONTROL,
        Character.FORMAT,
        Character.SURROGATE,
        Character.PRIVATE_USE,
        Character.UNASSIGNED,
    )

/**
 * True when [text] can stand as one emoji reaction: 1 to [REACTION_MAX_BYTES] bytes of UTF-8, at least one symbol,
 * and no letters, digits, spaces, controls or stray surrogates. ASCII is refused except as the base of a keycap
 * (`1️⃣`, `#️⃣`), the one emoji that starts with it. It is an approximation of "one grapheme", which is the phone's
 * job to get exactly right; the server only keeps text out of a field that shows next to a name.
 */
fun isReaction(text: String): Boolean {
    val bytes = text.toByteArray(Charsets.UTF_8).size
    if (bytes !in 1..REACTION_MAX_BYTES) return false
    val points = text.codePoints().toArray()
    var symbols = 0
    for ((i, cp) in points.withIndex()) {
        if (cp == ZERO_WIDTH_JOINER || cp == COMBINING_KEYCAP) continue
        if (cp < ASCII_END) {
            if (!isKeycapAt(points, i)) return false
            symbols++
            continue
        }
        if (Character.isLetterOrDigit(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return false
        val type = Character.getType(cp).toByte()
        if (type in REFUSED_TYPES) return false
        if (type == Character.OTHER_SYMBOL) symbols++
    }
    return symbols > 0
}

/** `0`-`9`, `#` or `*` followed by the keycap mark, with or without the emoji variation selector between. */
private fun isKeycapAt(points: IntArray, i: Int): Boolean {
    val base = points[i].toChar()
    if (base !in '0'..'9' && base != '#' && base != '*') return false
    val next = points.getOrNull(i + 1)
    return next == COMBINING_KEYCAP || (next == VARIATION_SELECTOR_16 && points.getOrNull(i + 2) == COMBINING_KEYCAP)
}
