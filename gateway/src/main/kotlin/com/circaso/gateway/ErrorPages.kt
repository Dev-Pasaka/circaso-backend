package com.circaso.gateway

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import java.time.Instant

private const val MAX_FIELD = 300

/** What the browser may load on an error page: its own inline style, nothing else (the mark is inline SVG). */
private const val CONTENT_SECURITY_POLICY =
    "default-src 'none'; style-src 'unsafe-inline'; " +
        "base-uri 'none'; form-action 'none'"

/** The designed pages for errors the proxy makes, bundled in the gateway's resources (`errors/`). */
private object ErrorPageResources {
    /** The statuses that have a page. 502, 503 and 504 are not here: the app cannot serve them when it is down. */
    val statuses = setOf(403, 404, 413, 429)

    private fun text(name: String): String =
        ErrorPageResources::class.java.getResourceAsStream("/errors/$name")?.use { String(it.readBytes()) }
            ?: error("Missing resource errors/$name")

    val style: String by lazy { text("_style.html") }
    val logo: String by lazy { text("_logo.html") }
    private val templates = HashMap<Int, String>()

    fun template(status: Int): String? =
        if (status !in statuses) {
            null
        } else {
            synchronized(templates) { templates.getOrPut(status) { text("$status.html") } }
        }
}

private val placeholder = Regex("\\{\\{([A-Z]+)}}")

/** Fills `{{NAME}}` in one pass, so a value that itself contains `{{...}}` is printed, never expanded. */
internal fun fillTemplate(template: String, values: Map<String, String>): String =
    placeholder.replace(template) { values[it.groupValues[1]] ?: "" }

/** Safe to put in HTML: cut to a sensible length, control characters dropped, markup characters escaped. */
internal fun htmlText(value: String?): String =
    value
        .orEmpty()
        .take(MAX_FIELD)
        .filter { !it.isISOControl() }
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

/**
 * What the 403 page shows the caller: their own address, country, browser and request, which is what the access log
 * holds about them. The address comes from `CF-Connecting-IP` and the original method and path from `X-Original-*`,
 * all set by nginx, which replaces anything the caller sent.
 */
private fun ApplicationCall.requestRecord(): Map<String, String> {
    val method = request.headers["X-Original-Method"] ?: request.local.method.value
    val path = request.headers["X-Original-URI"] ?: request.local.uri
    return mapOf(
        "ADDRESS" to htmlText(clientIp()),
        "COUNTRY" to htmlText(request.headers["CF-IPCountry"] ?: "unknown"),
        "BROWSER" to htmlText(request.headers[HttpHeaders.UserAgent] ?: "none sent"),
        "REQUEST" to htmlText("$method $path"),
        "TIME" to htmlText(Instant.now().toString()),
        "REFERENCE" to htmlText(request.headers["CF-Ray"] ?: "none"),
    )
}

/**
 * `GET /_errors/{status}`: the page nginx shows for a 403, 404, 413 or 429 that it produced itself (a refused request, a
 * rate limit). nginx fetches it internally and sends the page with the original status; the path is not reachable from
 * outside. It answers with the status in the URL so a browser opened on it sees what the caller would. Hidden from the
 * OpenAPI spec.
 */
@OptIn(ExperimentalKtorApi::class)
fun Route.errorPageRoutes() {
    route("/_errors") {
        get("{status}") {
            val status = call.parameters["status"]?.toIntOrNull()
            val template = status?.let(ErrorPageResources::template)
            if (status == null || template == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val values =
                call.requestRecord() +
                    mapOf(
                        "STYLE" to ErrorPageResources.style,
                        "LOGO" to ErrorPageResources.logo,
                    )
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
            call.respondText(
                text = fillTemplate(template, values),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.fromValue(status),
            )
        }.hide()
    }
}
