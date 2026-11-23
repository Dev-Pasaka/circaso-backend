package com.circaso.integrations.impl.didit

import java.math.BigDecimal

/**
 * The canonical form Didit signs for `X-Signature-V2` (docs.didit.me → Webhooks → Signature verification): the
 * delivery's JSON written again with **keys sorted, no spaces, Unicode left as it is, and whole-valued floats as
 * integers**, which is Python's `json.dumps(shorten_floats(body), sort_keys=True, separators=(",", ":"),
 * ensure_ascii=False)`. The HMAC over this text survives a proxy that re-encodes the body, which the raw-bytes
 * signature (`X-Signature`) does not.
 *
 * This is not payload reading: no field is taken from the body here (the typed [WebhookEnvelope] does that). Any
 * JSON must come out in the one form both sides sign, so the text is walked value by value and written back, without
 * a tree of untyped elements.
 *
 * What is reproduced, exactly as Python writes it:
 * - object keys in code-point order, a repeated key keeping its last value;
 * - strings with only `"`, `\`, and the control characters below U+0020 escaped (`\n`, `\r`, `\t`, `\b`, `\f`, the
 *   rest as `\u00xx`); every other character, `/` and non-ASCII included, as itself;
 * - integers as written; a float whose value is whole as that integer (`100.0` → `100`, `1e+16` →
 *   `10000000000000000`, `-0.0` → `0`); any other float as it was written, which from Didit is already Python's
 *   shortest form.
 */
internal object DiditCanonicalJson {
    /** [json] in Didit's canonical form, or null when it isn't one well-formed JSON value. */
    fun of(json: String): String? =
        try {
            val reader = Reader(json)
            val out = StringBuilder(json.length)
            reader.skipWhitespace()
            reader.value(out, depth = 0)
            reader.skipWhitespace()
            if (reader.atEnd()) out.toString() else null
        } catch (_: Malformed) {
            null
        }

    private class Malformed : RuntimeException(null, null, false, false)

    private class Reader(private val text: String) {
        private var at = 0

        fun atEnd(): Boolean = at >= text.length

        fun skipWhitespace() {
            while (at < text.length && text[at] in WHITESPACE) at++
        }

        fun value(out: StringBuilder, depth: Int) {
            if (depth > MAX_DEPTH || atEnd()) throw Malformed()
            when (text[at]) {
                '{' -> objectValue(out, depth)
                '[' -> arrayValue(out, depth)
                '"' -> writeString(out, string())
                't' -> literal(out, "true")
                'f' -> literal(out, "false")
                'n' -> literal(out, "null")
                else -> number(out)
            }
        }

        private fun objectValue(out: StringBuilder, depth: Int) {
            at++
            val members = LinkedHashMap<String, String>()
            skipWhitespace()
            if (peek() == '}') {
                at++
            } else {
                while (true) {
                    skipWhitespace()
                    if (peek() != '"') throw Malformed()
                    val key = string()
                    skipWhitespace()
                    expect(':')
                    skipWhitespace()
                    val member = StringBuilder()
                    value(member, depth + 1)
                    members[key] = member.toString()
                    skipWhitespace()
                    if (peek() == ',') at++ else break
                }
                expect('}')
            }
            out.append('{')
            members.keys.sortedWith(CODE_POINT_ORDER).forEachIndexed { index, key ->
                if (index > 0) out.append(',')
                writeString(out, key)
                out.append(':').append(members.getValue(key))
            }
            out.append('}')
        }

        private fun arrayValue(out: StringBuilder, depth: Int) {
            at++
            out.append('[')
            skipWhitespace()
            if (peek() == ']') {
                at++
            } else {
                while (true) {
                    skipWhitespace()
                    value(out, depth + 1)
                    skipWhitespace()
                    if (peek() == ',') {
                        at++
                        out.append(',')
                    } else {
                        break
                    }
                }
                expect(']')
            }
            out.append(']')
        }

        /** The string that starts at the quote under the cursor, with its escapes resolved. */
        private fun string(): String {
            at++
            val read = StringBuilder()
            while (true) {
                if (atEnd()) throw Malformed()
                val char = text[at++]
                when {
                    char == '"' -> return read.toString()
                    char == '\\' -> read.append(escaped())
                    char < ' ' -> throw Malformed()
                    else -> read.append(char)
                }
            }
        }

        private fun escaped(): Char {
            if (atEnd()) throw Malformed()
            return when (val char = text[at++]) {
                '"', '\\', '/' -> char
                'b' -> '\b'
                'f' -> FORM_FEED
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> unicode()
                else -> throw Malformed()
            }
        }

        private fun unicode(): Char {
            if (at + HEX_DIGITS > text.length) throw Malformed()
            val code = text.substring(at, at + HEX_DIGITS).toIntOrNull(HEX) ?: throw Malformed()
            at += HEX_DIGITS
            return code.toChar()
        }

        private fun literal(out: StringBuilder, word: String) {
            if (!text.startsWith(word, at)) throw Malformed()
            at += word.length
            out.append(word)
        }

        private fun number(out: StringBuilder) {
            val start = at
            while (at < text.length && text[at] in NUMBER_CHARS) at++
            val written = text.substring(start, at)
            if (!NUMBER.matches(written)) throw Malformed()
            out.append(if (written.any { it in FLOAT_MARKS }) float(written) else written)
        }

        /** A float as Python writes it after `shorten_floats`: its integer when it is whole, else as it came. */
        private fun float(written: String): String {
            val value = written.toDoubleOrNull() ?: throw Malformed()
            val isWhole = !value.isNaN() && !value.isInfinite() && value == Math.rint(value)
            return if (isWhole) BigDecimal(value).toBigInteger().toString() else written
        }

        private fun peek(): Char = if (atEnd()) throw Malformed() else text[at]

        private fun expect(char: Char) {
            if (peek() != char) throw Malformed()
            at++
        }
    }

    /** Python's `json.dumps(..., ensure_ascii=False)` escapes: the quote, the backslash and the C0 controls only. */
    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (char in value) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char == '\b' -> out.append("\\b")
                char == FORM_FEED -> out.append("\\f")
                char < ' ' -> out.append("\\u").append(char.code.toString(HEX).padStart(HEX_DIGITS, '0'))
                else -> out.append(char)
            }
        }
        out.append('"')
    }

    /** Python orders `str` keys by code point; Kotlin's own order is by UTF-16 unit, which differs above U+FFFF. */
    private val CODE_POINT_ORDER =
        Comparator<String> { left, right ->
            val a = left.codePoints().iterator()
            val b = right.codePoints().iterator()
            var order = 0
            while (order == 0 && a.hasNext() && b.hasNext()) order = a.nextInt().compareTo(b.nextInt())
            if (order != 0) order else a.hasNext().compareTo(b.hasNext())
        }

    private const val MAX_DEPTH = 64
    private const val HEX = 16
    private const val HEX_DIGITS = 4
    private const val FORM_FEED = '\u000C'
    private const val WHITESPACE = " \t\n\r"
    private const val NUMBER_CHARS = "+-.0123456789eE"
    private const val FLOAT_MARKS = ".eE"
    private val NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")
}
