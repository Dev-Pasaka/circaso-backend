package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.models.Conversation

/**
 * A conversation's title, made from its first question by a rule rather than a model (decision 2026-10-31): free,
 * instant, never fails, and the question goes nowhere new. Greetings are dropped ("Hi Asha, how much…"), then the
 * first six words are kept, without markdown marks or the closing punctuation.
 */
object ConversationTitles {
    /** What an untitled conversation (a new one with no question yet) is called. */
    const val UNTITLED = "New chat"

    /** Conversation 1 (V2) whose first message isn't a question. */
    const val EARLIER = "Earlier chat"

    const val MAX_WORDS = 6
    const val MAX_CHARS = 60

    /** The longest title a user may give. */
    const val MAX_USER_CHARS = 80

    private val GREETINGS =
        setOf("hi", "hello", "hey", "hiya", "habari", "jambo", "mambo", "niaje", "sasa", "yo", "asha", "dear")
    private val MARKS = Regex("[*_`#>~]")
    private val SPACES = Regex("\\s+")
    private const val CLOSING = ".,;:!?-–—…"

    fun from(question: String): String {
        val words =
            question
                .replace(MARKS, " ")
                .trim()
                .split(SPACES)
                .filter { it.isNotEmpty() }
        val asked =
            words
                .filter { word -> word.any { it.isLetterOrDigit() } }
                .dropWhile { it.lowercase().trimEnd(*CLOSING.toCharArray()) in GREETINGS }
        val kept = asked.ifEmpty { words }.take(MAX_WORDS)
        val title =
            kept
                .joinToString(" ")
                .trimEnd(*CLOSING.toCharArray())
                .trimEnd()
                .let { if (it.length > MAX_CHARS) it.take(MAX_CHARS).trimEnd() + "…" else it }
                .replaceFirstChar { it.uppercaseChar() }
        return title.ifBlank { question.trim().take(MAX_CHARS) }.ifBlank { UNTITLED }
    }

    /** What the app shows: the title, or [UNTITLED] while there is none. */
    fun shownFor(conversation: Conversation): String = conversation.title ?: UNTITLED
}
