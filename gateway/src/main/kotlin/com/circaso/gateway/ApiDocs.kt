package com.circaso.gateway

import com.circaso.shared.Scope
import com.circaso.web.authenticate
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import java.security.MessageDigest

/** The guide that opens the reference, bundled in the gateway's resources (`docs/guide.md`). */
private object DocsResources {
    val guide: String by lazy {
        DocsResources::class.java.getResourceAsStream("/docs/guide.md")?.use { String(it.readBytes()) }
            ?: error("Missing resource docs/guide.md")
    }
}

/** A strong ETag for a body, so a browser that has it asks "changed?" and gets a tiny 304. */
private fun etagOf(bytes: ByteArray): String =
    "\"" +
        MessageDigest
            .getInstance(
                "SHA-256",
            ).digest(bytes)
            .take(12)
            .joinToString("") { "%02x".format(it) } +
        "\""

private suspend fun ApplicationCall.respondRevalidated(
    etag: String,
    type: ContentType,
    body: () -> ByteArray,
) {
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, "no-cache")
    if (request.headers[HttpHeaders.IfNoneMatch] ==
        etag
    ) {
        respond(HttpStatusCode.NotModified)
    } else {
        respondBytes(body(), type)
    }
}

/**
 * The generated OpenAPI spec, at `/docs/openapi.json`. Ktor builds it from the routing tree at request time, so it can
 * not drift from the code; each route's text comes from its `docs(...)` call, and [ApiDocsCatalog] adds what no single
 * route can say: the guide, the sections in the order a person uses the API (Infrastructure first, then
 * authentication, profile, people, Asha, account), security and servers. The reader itself is not here: it is its own
 * small service (`docs-ui/`, static files behind nginx) and the proxy sends `/docs` to it and this one path to the app.
 * The spec is sent gzipped and validated by ETag, so a repeat visit costs a 304. It is off in production, and behind
 * the `ops` scope outside local runs. The route is hidden from the spec itself.
 */
@OptIn(ExperimentalKtorApi::class)
fun Route.apiDocsRoutes(application: Application, config: ApiDocsConfig) {
    if (!config.enabled) return
    val base = OpenApiDoc(info = OpenApiInfo(title = "Circaso API", version = "v1"))
    val source = OpenApiDocSource.Routing(ContentType.Application.Json)

    val serve: Route.() -> Unit = {
        route("/docs") {
            get("openapi.json") {
                val json =
                    ApiDocsCatalog
                        .complete(
                            source.read(application, base).content,
                            DocsResources.guide,
                        ).toByteArray()
                call.respondRevalidated(etagOf(json), ContentType.Application.Json) { json }
            }.hide()
        }
    }
    if (config.requireOps) authenticate(Scope.OPS, build = serve) else serve()
}
