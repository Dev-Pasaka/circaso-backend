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

/** Text and files the reader is built from, bundled in the gateway's resources (`docs/`). */
private object DocsResources {
    fun bytes(name: String): ByteArray =
        DocsResources::class.java.getResourceAsStream("/docs/$name")?.use { it.readBytes() }
            ?: error("Missing resource docs/$name (build it with `cd docs-ui && bun run build`)")

    val guide: String by lazy { String(bytes("guide.md")) }
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

/** A file of the reader (`docs-ui/` builds them), kept in memory with its ETag. */
private class DocsAsset(name: String, val type: ContentType) {
    val body: ByteArray by lazy { DocsResources.bytes(name) }
    val etag: String by lazy { etagOf(body) }
}

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
 * The generated OpenAPI spec and a reader for it. Ktor builds the spec from the routing tree at request time, so it
 * can not drift from the code; each route's text comes from its `docs(...)` call, and [ApiDocsCatalog] adds what no
 * single route can say: the guide, the sections in the order a person uses the API (Infrastructure first, then
 * authentication, profile, people, Asha, account), security and servers. The reader is Circaso's own (`docs-ui/`,
 * TypeScript, about 25 KB with no dependencies): static files that draw everything from `/docs/openapi.json`, so the
 * page changes only when the spec does. The spec and the files are sent gzipped and validated by ETag, so a repeat
 * visit costs a few 304s. Both are off in production, and behind the `ops` scope outside local runs. The docs routes
 * are hidden from the spec itself.
 */
@OptIn(ExperimentalKtorApi::class)
fun Route.apiDocsRoutes(application: Application, config: ApiDocsConfig) {
    if (!config.enabled) return
    val base = OpenApiDoc(info = OpenApiInfo(title = "Circaso API", version = "v1"))
    val source = OpenApiDocSource.Routing(ContentType.Application.Json)
    val page = DocsAsset("index.html", ContentType.Text.Html)
    val script = DocsAsset("app.js", ContentType.Application.JavaScript)
    val styles = DocsAsset("app.css", ContentType.Text.CSS)

    val serve: Route.() -> Unit = {
        route("/docs") {
            get { call.respondRevalidated(page.etag, page.type) { page.body } }.hide()
            get("app.js") { call.respondRevalidated(script.etag, script.type) { script.body } }.hide()
            get("app.css") { call.respondRevalidated(styles.etag, styles.type) { styles.body } }.hide()
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
