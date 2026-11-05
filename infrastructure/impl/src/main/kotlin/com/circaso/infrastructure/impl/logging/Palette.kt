package com.circaso.infrastructure.impl.logging

/**
 * ANSI colors for the console format. The pretty format is the one a developer reads, so it is colored by default,
 * including under Gradle or an IDE run window, where stdout is a pipe and `System.console()` is null; otherwise only a
 * terminal gets colors. The JSON format never has any, so no escape codes reach Loki.
 * `LOG_COLOR=true|false` overrides the detection, and `NO_COLOR` switches colors off. Each setting is read from the
 * environment or, failing that, from a system property (which is where `.env` puts it).
 */
class Palette(private val enabled: Boolean) {
    fun gray(text: String) = paint("90", text)

    fun cyan(text: String) = paint("36", text)

    fun green(text: String) = paint("32", text)

    fun yellow(text: String) = paint("33", text)

    fun red(text: String) = paint("31", text)

    fun boldRed(text: String) = paint("1;31", text)

    fun blue(text: String) = paint("34", text)

    fun magenta(text: String) = paint("35", text)

    fun boldWhite(text: String) = paint("1;37", text)

    /** The bright shades are kept for who sent a request (address, device, app), so it stands out from the rest. */
    fun brightCyan(text: String) = paint("96", text)

    fun brightMagenta(text: String) = paint("95", text)

    fun brightBlue(text: String) = paint("94", text)

    private fun paint(code: String, text: String) = if (enabled) "\u001B[${code}m$text\u001B[0m" else text

    companion object {
        val detected: Palette by lazy {
            val forced = setting("LOG_COLOR")?.lowercase()
            Palette(
                when (forced) {
                    "true" -> {
                        true
                    }

                    "false" -> {
                        false
                    }

                    else -> {
                        setting("NO_COLOR") == null &&
                            (setting("LOG_FORMAT")?.uppercase() == "PRETTY" || System.console() != null)
                    }
                },
            )
        }

        private fun setting(name: String): String? =
            (System.getenv(name) ?: System.getProperty(name))?.takeIf {
                it.isNotEmpty()
            }
    }
}
