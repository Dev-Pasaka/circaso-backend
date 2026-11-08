package com.circaso.support.domain.support

/**
 * The length rules for what people write on a ticket (support.md → Endpoints), counted after cleaning: control
 * characters other than a line break or a tab are taken out (they render as nothing, or worse, on the staff
 * dashboard), Windows line breaks become plain ones and the ends are trimmed. Checked in the use cases, because Asha's
 * tool and the staff routes reach them without passing the app's mappers.
 */
object TicketTexts {
    private val SUBJECT = 3..120
    private val DESCRIPTION = 10..4_000
    private val BODY = 1..4_000

    /** The subject on one line: line breaks become spaces. Null when out of range. */
    fun subject(raw: String): String? = clean(raw.replace('\n', ' ').replace('\r', ' ')).takeIf { it.length in SUBJECT }

    fun description(raw: String): String? = clean(raw).takeIf { it.length in DESCRIPTION }

    fun body(raw: String): String? = clean(raw).takeIf { it.length in BODY }

    private fun clean(raw: String): String =
        raw
            .replace("\r\n", "\n")
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }
            .trim()
}
