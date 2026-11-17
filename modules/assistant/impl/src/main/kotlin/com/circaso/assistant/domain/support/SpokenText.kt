package com.circaso.assistant.domain.support

/**
 * Text on its way to a voice (2026-11-17). A voice reads what it is given: a numbered step came out as "one dot", a
 * `#500` as "pound five hundred", a link as its address. The voice prompt asks the model for plain speech, and this
 * is the guard for when it writes marks anyway, and for a chat reply (markdown by design) that is read aloud.
 *
 * [shown] takes the marks out (what the phone captions); [said] then spells what a voice gets wrong.
 */
object SpokenText {
    /** A whole chat reply as it is read aloud: no blocks, tables as phrases, each line without its marks. */
    fun ofReply(markdown: String): String =
        markdown
            .replace(FENCED, " ")
            .lineSequence()
            .filterNot { TABLE_RULE.matches(it) }
            .map { said(shown(it.replace('|', ','))) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** One sentence or line without markdown; empty when nothing sayable is left (a list number on its own). */
    fun shown(sentence: String): String =
        sentence
            .replace(LINK, "$1")
            .replace(URL, "")
            .replace(HEADING, "")
            .replace(QUOTE, "")
            .replace(LIST_MARK, "")
            .replace(EMPHASIS, "$1")
            .replace("*", "")
            .replace("`", "")
            .replace("~~", "")
            .replace(SPACES, " ")
            .trim()
            .trim(',')
            .trim()
            .let { if (LONE_NUMBER.matches(it)) "" else it }

    /** [shown] text as a voice should get it: `#500` is "hash 500", any other `#` is not said. */
    fun said(shown: String): String =
        shown
            .replace(HASH_AMOUNT, "hash $1")
            .replace("#", "")
            .replace(SPACES, " ")
            .trim()

    private val FENCED = Regex("(?s)```.*?(```|$)")
    private val TABLE_RULE = Regex("^\\s*\\|?[\\s:|-]*-{2,}[\\s:|-]*$")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val URL = Regex("<?https?://\\S+>?")
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+")
    private val QUOTE = Regex("^\\s{0,3}>\\s?")
    private val LIST_MARK = Regex("^\\s*(?:[-*•–]|\\d{1,2}[.)])\\s+")
    private val EMPHASIS = Regex("(?<![\\w])_{1,2}([^_]+)_{1,2}(?![\\w])")
    private val LONE_NUMBER = Regex("\\d{1,2}[.):]?")
    private val HASH_AMOUNT = Regex("#(\\d)")
    private val SPACES = Regex("[ \\t]+")
}
