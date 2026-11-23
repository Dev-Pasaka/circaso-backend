package com.circaso.integrations.impl.payhero

import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * The **layout** of a JSON body with every value thrown away: field names and the kind of each value
 * (`{amount:number,response:{Status:string},tags:[string]}`). PayHero/FinoQ documents neither its payout status and
 * balance answers nor which callback a payout sends, so the first real ones are how we learn them, and a body can't
 * be logged (it carries phone numbers and amounts).
 *
 * This is a scanner, not a parser of payloads: it never keeps a value, only steps over it. Field names are kept,
 * sorted, with any long run of digits masked in case a body is keyed by an id or a number. Anything that isn't JSON is
 * `not-json`; it never throws and its output is bounded.
 */
internal object JsonShape {
    fun of(body: String): String {
        val text = body.trim()
        if (text.isEmpty()) return "empty"
        if (text.first() != '{' && text.first() != '[') return NOT_JSON
        return runCatching { Scanner(text).document() }.getOrDefault(NOT_JSON).take(MAX_LENGTH)
    }

    private const val NOT_JSON = "not-json"
    private const val MAX_LENGTH = 700
    private const val MAX_DEPTH = 6
    private const val MAX_NESTING = 32
    private const val MAX_FIELDS = 60
    private const val MAX_NAME = 40
    private const val MAX_VARIANTS = 3
    private val DIGIT_RUN = Regex("""\d{4,}""")
    private val UNSAFE = Regex("""[^A-Za-z0-9_.\-#]""")

    private class Scanner(private val text: String) {
        private var at = 0

        fun document(): String {
            val shape = value(0)
            skipSpace()
            check(at == text.length)
            return shape
        }

        private fun value(depth: Int): String {
            check(depth <= MAX_NESTING)
            skipSpace()
            return when (val next = text[at]) {
                '{' -> fields(depth)
                '[' -> items(depth)
                '"' -> "string".also { string() }
                't' -> "bool".also { word("true") }
                'f' -> "bool".also { word("false") }
                'n' -> "null".also { word("null") }
                else -> "number".also { number(next) }
            }
        }

        private fun fields(depth: Int): String {
            at++
            val found = sortedMapOf<String, String>()
            skipSpace()
            while (text[at] != '}') {
                skipSpace()
                val name = name(string())
                skipSpace()
                expect(':')
                val shape = value(depth + 1)
                if (found.size < MAX_FIELDS) found[name] = shape
                skipSpace()
                if (text[at] == ',') at++
                skipSpace()
            }
            at++
            if (depth >= MAX_DEPTH) return "{…}"
            return found.entries.joinToString(",", "{", "}") { "${it.key}:${it.value}" }
        }

        private fun items(depth: Int): String {
            at++
            val variants = linkedSetOf<String>()
            skipSpace()
            while (text[at] != ']') {
                val shape = value(depth + 1)
                if (variants.size < MAX_VARIANTS) variants += shape
                skipSpace()
                if (text[at] == ',') at++
                skipSpace()
            }
            at++
            if (depth >= MAX_DEPTH) return "[…]"
            return variants.joinToString("|", "[", "]")
        }

        /** Steps over a string and hands back what was between the quotes (only ever used for a field's name). */
        private fun string(): String {
            expect('"')
            val start = at
            while (text[at] != '"') at += if (text[at] == '\\') 2 else 1
            return text.substring(start, at++)
        }

        private fun number(first: Char) {
            check(first == '-' || first.isDigit())
            while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
        }

        private fun word(expected: String) {
            check(text.startsWith(expected, at))
            at += expected.length
        }

        private fun expect(char: Char) {
            check(text[at] == char)
            at++
        }

        private fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun name(raw: String): String = raw.take(MAX_NAME).replace(DIGIT_RUN, "#").replace(UNSAFE, "?")
    }
}

/**
 * Writes one line the first time this server sees a layout (per kind of answer and HTTP status), so the log shows
 * every shape PayHero/FinoQ actually sends without repeating itself. Bounded: past [MAX_SEEN] layouts it goes quiet.
 */
internal class ShapeLog(private val log: Logger) {
    private val seen = ConcurrentHashMap.newKeySet<String>()

    fun note(
        what: String,
        status: Int?,
        body: String,
    ) {
        val shape = JsonShape.of(body)
        if (seen.size >= MAX_SEEN || !seen.add("$what|$status|$shape")) return
        log.info("payhero layout first seen: what={} status={} shape={}", what, status ?: "-", shape)
    }

    private companion object {
        const val MAX_SEEN = 500
    }
}
