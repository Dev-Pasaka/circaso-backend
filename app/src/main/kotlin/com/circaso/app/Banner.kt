package com.circaso.app

import com.circaso.infrastructure.api.Environment
import com.circaso.infrastructure.impl.logging.Palette

/**
 * The startup banner: the CIRCASO.COM logo and where the server is listening. Printed to the console only in the
 * pretty log format (a person is reading); the JSON format gets the one "Application started" log line instead.
 */
object Banner {
    private val glyphs: Map<Char, List<String>> =
        mapOf(
            'C' to listOf("  ____ ", " / ___|", "| |    ", "| |___ ", " \\____|"),
            'I' to listOf(" ___ ", "|_ _|", " | | ", " | | ", "|___|"),
            'R' to listOf(" ____  ", "|  _ \\ ", "| |_) |", "|  _ < ", "|_| \\_\\"),
            'A' to listOf("    _    ", "   / \\   ", "  / _ \\  ", " / ___ \\ ", "/_/   \\_\\"),
            'S' to listOf(" ____  ", "/ ___| ", "\\___ \\ ", " ___) |", "|____/ "),
            'O' to listOf("  ___  ", " / _ \\ ", "| | | |", "| |_| |", " \\___/ "),
            '.' to listOf("   ", "   ", "   ", " _ ", "(_)"),
            'M' to listOf(" __  __ ", "|  \\/  |", "| |\\/| |", "| |  | |", "|_|  |_|"),
        )

    /** The logo, five lines, with no color. */
    fun logo(text: String = "CIRCASO.COM"): List<String> =
        (0 until 5).map { row -> text.map { glyphs.getValue(it)[row] }.joinToString("") }

    /** The URL a person can open: a server bound to every address is reached at `localhost`. */
    fun url(host: String, port: Int): String =
        "http://${if (host == "0.0.0.0" || host == "::") "localhost" else host}:$port"

    fun render(app: AppConfig, palette: Palette): String {
        val url = url(app.host, app.port)
        val docs = app.environment != Environment.PRODUCTION
        return buildString {
            appendLine()
            logo().forEach { appendLine("  " + palette.cyan(it)) }
            appendLine()
            appendLine("  " + palette.green("Application started") + " on " + palette.boldWhite(url))
            appendLine(
                "  " + palette.gray("environment ") + app.environment.name.lowercase() + palette.gray("  version ") +
                    app.version,
            )
            if (docs) appendLine("  " + palette.gray("api docs    ") + "$url/docs")
            appendLine("  " + palette.gray("health      ") + "$url/health/ready")
            appendLine()
        }
    }

    /** Prints the banner if the log format is the pretty one. */
    fun print(
        app: AppConfig,
        format: String?,
        palette: Palette = Palette.detected,
    ) {
        if (format?.uppercase() == "PRETTY") print(render(app, palette))
    }
}
