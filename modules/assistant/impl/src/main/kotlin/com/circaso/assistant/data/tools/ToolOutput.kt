package com.circaso.assistant.data.tools

import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Every tool answers JSON of at most [MAX_CHARS] (decision 2026-10-31): a long answer would crowd the model's context
 * and cost tokens without making the reply better. A list too long is halved until it fits, and the answer says it
 * was cut; one that can't be cut and still doesn't fit is replaced by a message saying so.
 */
internal object ToolOutput {
    const val MAX_CHARS = 12_000
    private const val CUT = "Only part of the answer fits here. Ask for less (a shorter range, a lower limit)."
    private const val TOO_LARGE = "The answer was too large to read. Ask for less (a shorter range, a lower limit)."

    fun <T> encode(
        data: T,
        serializer: KSerializer<T>,
        shrink: (T) -> T?,
    ): Resource<String> {
        var current = data
        var isCut = false
        while (true) {
            val json =
                if (isCut) {
                    CircasoJson.encodeToString(CutAnswer.serializer(serializer), CutAnswer(CUT, current))
                } else {
                    CircasoJson.encodeToString(serializer, current)
                }
            if (json.length <= MAX_CHARS) return Resource.success(json)
            current = shrink(current) ?: return message(TOO_LARGE)
            isCut = true
        }
    }

    /** A plain message for the model, as JSON: `{"message": "…"}`. */
    fun message(text: String): Resource<String> =
        Resource.success(CircasoJson.encodeToString(ToolMessage.serializer(), ToolMessage(text)))
}

/** Half of a list (the first half), or null when it can't get shorter. */
internal fun <T> List<T>.half(): List<T>? = if (size <= 1) null else take(size / 2)

/** User-given text in a status line, short and in quotes. */
internal fun quoted(text: String): String = "“" + text.trim().take(MAX_QUOTED_CHARS) + "”"

private const val MAX_QUOTED_CHARS = 40

/** `searchContacts` and `searchWeb` (their schemas are in `AshaPrompt`). */
@Serializable
internal data class QueryArgs(val query: String = "")

@Serializable
private data class ToolMessage(val message: String)

@Serializable
private data class CutAnswer<T>(val note: String, val data: T)
