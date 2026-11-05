package com.circaso.gateway

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall

/**
 * Who a request came from, for its log line: the address and country Cloudflare saw, the app version the client
 * declares, the device read from its User-Agent, and the User-Agent itself (cleaned and cut short: it is the
 * caller's text). Ops reads these to tell a phone from a script and one person's devices apart.
 */
internal data class ClientFields(
    val ip: String,
    val country: String?,
    val version: String?,
    val device: String?,
    val userAgent: String?,
)

internal fun ApplicationCall.clientFields(): ClientFields {
    val ua = request.headers[HttpHeaders.UserAgent]?.let(::cleanHeader)
    return ClientFields(
        ip = clientIp(),
        // Cloudflare sends XX for an unknown country and T1 for Tor.
        country = request.headers["CF-IPCountry"]?.let(::cleanHeader)?.takeIf { it.length == 2 && it != "XX" },
        version = request.headers["X-Client-Version"]?.let(::cleanHeader),
        device = ua?.let(::deviceOf),
        userAgent = ua,
    )
}

private const val MAX_HEADER = 200

/** Printable ASCII only (no newlines to forge a log line, no escape codes to colour one), at most 200 characters. */
internal fun cleanHeader(value: String): String? =
    value
        .filter { it in ' '..'~' }
        .trim()
        .take(MAX_HEADER)
        .takeIf { it.isNotEmpty() }

private val circaso = Regex("""^Circaso/\S+ \(([^)]*)\)""")
private val androidVersion = Regex("""Android (\d+(?:\.\d+)?)""")
private val iosVersion = Regex("""(?:iPhone|CPU) OS (\d+(?:_\d+)*)""")
private val macVersion = Regex("""Mac OS X (\d+(?:[_.]\d+)*)""")
private val androidModel = Regex("""Android [^;)]*; (?:[a-z]{2}[-_][A-Za-z]{2}; )?([^;)]+?)(?: Build/[^;)]*)?\)""")
private val browsers =
    listOf(
        "Edg/" to "Edge",
        "OPR/" to "Opera",
        "SamsungBrowser/" to "Samsung Internet",
        "Firefox/" to "Firefox",
        "CriOS/" to "Chrome",
        "Chrome/" to "Chrome",
        "Version/" to "Safari",
    )
private val tools =
    listOf(
        "okhttp",
        "Dart",
        "curl",
        "PostmanRuntime",
        "python-requests",
        "Go-http-client",
        "ktor-client",
        "CFNetwork",
        "Wget",
    )

/**
 * A short name for the device a User-Agent describes: `Android 15 · Xiaomi 23129RAA4G` for our app (it says so
 * itself), `Chrome · Android 14 · Pixel 8` for a browser, `curl` for a tool. Null when nothing is recognisable.
 */
internal fun deviceOf(ua: String): String? {
    circaso.find(ua)?.let { m ->
        return m.groupValues[1]
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
    }
    tools.firstOrNull { ua.startsWith(it, ignoreCase = true) }?.let { tool -> return tool }
    val os =
        when {
            "Android" in ua -> {
                "Android" + (
                    androidVersion
                        .find(ua)
                        ?.groupValues
                        ?.get(1)
                        ?.let { " $it" } ?: ""
                )
            }

            "iPhone" in ua || "iPad" in ua -> {
                (if ("iPad" in ua) "iPadOS" else "iOS") +
                    (
                        iosVersion
                            .find(ua)
                            ?.groupValues
                            ?.get(1)
                            ?.let { " " + it.replace('_', '.') } ?: ""
                    )
            }

            "Mac OS X" in ua -> {
                "macOS" +
                    (
                        macVersion
                            .find(ua)
                            ?.groupValues
                            ?.get(1)
                            ?.let { " " + it.replace('_', '.') } ?: ""
                    )
            }

            "Windows" in ua -> {
                "Windows"
            }

            "CrOS" in ua -> {
                "ChromeOS"
            }

            "Linux" in ua -> {
                "Linux"
            }

            else -> {
                null
            }
        }
    // Chrome on Android 10+ reports the model as "K"; it says nothing, so it is left out.
    val model =
        if ("Android" in
            ua
        ) {
            androidModel
                .find(ua)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "K" }
        } else {
            null
        }
    val browser = if ("Mozilla/" in ua) browsers.firstOrNull { (token, _) -> token in ua }?.second else null
    return listOfNotNull(browser, os, model).joinToString(" · ").takeIf { it.isNotEmpty() }
}
