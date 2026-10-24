package com.circaso.infrastructure.impl.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import net.logstash.logback.marker.ObjectAppendingMarker

/**
 * Builds the body of a console line from the same structured arguments the JSON format carries, so colors
 * come from fields rather than from parsing text (Logging → What a line carries, Colors).
 */
class LineFormatter(private val palette: Palette) {
    private val consumed =
        setOf("kind", "http.method", "http.route", "url.path", "http.status", "durationMs", "error.code")

    fun level(event: ILoggingEvent): String {
        val padded = event.level.toString().padEnd(5)
        return levelColor(event.level, padded)
    }

    fun requestId(event: ILoggingEvent): String =
        palette.gray((event.mdcPropertyMap["requestId"] ?: "--------").take(8).padEnd(8, '-'))

    fun module(event: ILoggingEvent): String = palette.boldWhite(moduleOf(event.loggerName))

    fun line(event: ILoggingEvent): String {
        val fields = fieldsOf(event)
        val kind = fields["kind"]?.toString()
        val parts = mutableListOf<String>()

        kind?.let { parts += tag(it) }
        fields["http.method"]?.let { parts += method(it.toString()) }
        (fields["http.route"] ?: fields["url.path"])?.let { parts += it.toString() }
        if (kind == "request" || kind == "response") parts += clientInfo(event)
        fields["http.status"]?.let { parts += status(it.toString()) }
        fields["durationMs"]?.let { parts += duration(it.toString()) }

        val message = Redaction.mask(event.formattedMessage)
        if (!(kind == "request" && message == "in") && !(kind == "response" && message == "out")) parts += message

        val metadata =
            fields
                .filterKeys { it !in consumed }
                .map { (key, value) -> palette.gray("$key=${Redaction.mask(shorten(key, value.toString()))}") }
        parts += metadata
        fields["error.code"]?.let { parts += levelColor(event.level, it.toString()) }

        return parts.filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun fieldsOf(event: ILoggingEvent): Map<String, Any?> =
        event.argumentArray
            .orEmpty()
            .filterIsInstance<ObjectAppendingMarker>()
            // a `kv(name, value)` marker renders as `name=value`
            .associate { it.fieldName to it.toString().substringAfter('=') }

    private fun tag(kind: String): String =
        when (kind) {
            "request" -> palette.gray("→ in")
            "response" -> palette.gray("← out")
            "db" -> palette.magenta("⇄ scylla")
            "redis" -> palette.red("⇄ redis")
            "http" -> palette.cyan("⇄ http")
            "stream.publish" -> palette.blue("⇢ stream")
            "stream.consume" -> palette.blue("⇠ stream")
            "ws" -> palette.green("⇄ ws")
            "job" -> palette.yellow("⏲ job")
            "webhook" -> palette.magenta("⇄ webhook")
            else -> palette.gray(kind)
        }

    private fun method(value: String): String =
        when (value) {
            "GET" -> palette.blue(value)
            "POST" -> palette.green(value)
            "PUT", "PATCH" -> palette.yellow(value)
            "DELETE" -> palette.red(value)
            else -> palette.gray(value)
        }

    private fun status(value: String): String =
        when (value.firstOrNull()) {
            '2' -> palette.green(value)
            '3' -> palette.cyan(value)
            '4' -> palette.yellow(value)
            '5' -> palette.boldRed(value)
            else -> value
        }

    private fun duration(value: String): String {
        val millis = value.toLongOrNull() ?: return value
        val text = "${millis}ms"
        return when {
            millis > 1000 -> palette.red(text)
            millis >= 300 -> palette.yellow(text)
            else -> palette.gray(text)
        }
    }

    private fun clientInfo(event: ILoggingEvent): String {
        val mdc = event.mdcPropertyMap
        val where = listOfNotNull(mdc["client.ip"], mdc["client.country"]).joinToString(" ")
        return listOf(
            where,
            mdc["client.version"].orEmpty(),
        ).filter { it.isNotBlank() }.joinToString(" ") { palette.gray(it) }
    }

    /** Ids are shortened only on the console; JSON keeps them whole so they can be searched. */
    private fun shorten(key: String, value: String): String =
        if ((key == "userId" || key == "deviceId") &&
            value.length > 12
        ) {
            "${value.take(4)}…${value.takeLast(3)}"
        } else {
            value
        }

    private fun levelColor(level: Level, text: String): String =
        when (level.toInt()) {
            Level.ERROR_INT -> palette.boldRed(text)
            Level.WARN_INT -> palette.yellow(text)
            Level.INFO_INT -> palette.green(text)
            Level.DEBUG_INT -> palette.cyan(text)
            else -> palette.gray(text)
        }

    companion object {
        /** `com.circaso.identity.domain.X` → `identity`; anything else keeps its last segment. */
        fun moduleOf(loggerName: String): String {
            val segments = loggerName.split('.')
            return if (segments.size > 2 && segments[0] == "com" &&
                segments[1] == "circaso"
            ) {
                segments[2]
            } else {
                segments.last()
            }
        }
    }
}
