package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.models.MascotActivity
import com.circaso.assistant.domain.models.MascotMood
import com.circaso.assistant.domain.models.MascotReaction
import com.circaso.assistant.domain.models.MascotScene

/**
 * What the model proposed for the mascot in one `<mascot …/>` tag, already checked against the fixed lists: a value
 * that isn't on its list is null, as if the model had not written it.
 */
data class MascotTag(
    val scene: MascotScene? = null,
    val mood: MascotMood? = null,
    val activity: MascotActivity? = null,
    val reaction: MascotReaction? = null,
    val sensitive: Boolean = false,
    /** The model says the topic has plainly changed: the scene may switch on this turn alone. */
    val strong: Boolean = false,
) {
    companion object {
        private val ATTRIBUTE = Regex("""([A-Za-z_]+)\s*=\s*["']?([A-Za-z_]+)["']?""")

        /** The attributes of one tag (the text between `<mascot` and `>`); unknown names and values are dropped. */
        fun parse(attributes: String): MascotTag {
            val values =
                ATTRIBUTE
                    .findAll(attributes)
                    .associate { it.groupValues[1].lowercase() to it.groupValues[2].lowercase() }
            return MascotTag(
                scene = MascotScene.of(values["scene"]),
                mood = MascotMood.of(values["mood"]),
                activity = MascotActivity.of(values["activity"]),
                reaction = MascotReaction.of(values["reaction"]),
                sensitive = values["sensitive"] == "true",
                strong = values["strong"] == "true",
            )
        }
    }
}

/**
 * Takes Asha's mascot tag out of the model's stream (decision 2026-11-04). The model writes one
 * `<mascot scene="gym" mood="happy" activity="speaking"/>` at the start of its reply; the user never sees it, in
 * text or in speech, and it is never saved. Ordinary text goes straight through, except a `<` that may be opening a
 * tag, held for the few characters it takes to tell. A tag that never closes is dropped with what follows it, up to
 * [MAX_TAG] characters, rather than shown.
 */
class MascotTagFilter {
    private val held = StringBuilder()

    /** What one piece of the model's text comes to: the text to show and the tags it finished. */
    data class Piece(val text: String, val tags: List<MascotTag>)

    fun feed(delta: String): Piece {
        held.append(delta)
        return drain(isFinal = false)
    }

    /** The step is over: whatever is held is settled. */
    fun finish(): Piece = drain(isFinal = true)

    private fun drain(isFinal: Boolean): Piece {
        val out = StringBuilder()
        val tags = mutableListOf<MascotTag>()
        while (held.isNotEmpty()) {
            val open = held.indexOf('<')
            if (open < 0) {
                out.append(held)
                held.clear()
                break
            }
            out.append(held, 0, open)
            held.delete(0, open)
            // held starts with `<`: is it, or may it become, `<mascot` followed by a space, `/` or `>`?
            val prefix = held.substring(0, minOf(held.length, OPEN.length)).lowercase()
            if (!OPEN.startsWith(prefix) || (held.length > OPEN.length && !isBoundary(held[OPEN.length]))) {
                out.append(held[0])
                held.deleteCharAt(0)
                continue
            }
            if (held.length <= OPEN.length) {
                // too short to tell: wait, or at the end let a part of the word out (a lone `<mascot` is dropped)
                if (isFinal && prefix.length < OPEN.length) out.append(held)
                if (isFinal) held.clear()
                break
            }
            val close = held.indexOf('>')
            if (close < 0) {
                if (isFinal || held.length > MAX_TAG) held.clear()
                break
            }
            tags += MascotTag.parse(held.substring(OPEN.length, close).removeSuffix("/"))
            held.delete(0, close + 1)
            // the tag's own line goes with it
            if (held.startsWith("\r\n")) {
                held.delete(0, 2)
            } else if (held.startsWith("\n")) {
                held.deleteCharAt(0)
            }
        }
        return Piece(out.toString(), tags)
    }

    private fun isBoundary(next: Char): Boolean = next.isWhitespace() || next == '/' || next == '>'

    private companion object {
        const val OPEN = "<mascot"
        const val MAX_TAG = 400
    }
}
