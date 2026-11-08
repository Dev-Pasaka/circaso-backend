package com.circaso.opsgateway

import com.circaso.infrastructure.api.Environment
import com.circaso.shared.StaffRole
import com.circaso.web.staffOnly
import io.ktor.http.ContentType
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.hide
import io.ktor.utils.io.ExperimentalKtorApi

/**
 * The ops API's own OpenAPI spec at `/v1/ops/openapi.json`, built from this process's routes, so it lists the staff
 * routes and nothing of the app's. Signed-in staff only, and never in production, like the app's reference.
 */
@OptIn(ExperimentalKtorApi::class)
internal fun Route.opsSpecRoute(application: Application, config: OpsGatewayConfig) {
    if (config.environment == Environment.PRODUCTION) return
    val base = OpenApiDoc(info = OpenApiInfo(title = "Circaso ops API", version = "v1"))
    val source = OpenApiDocSource.Routing(ContentType.Application.Json)
    staffOnly(StaffRole.entries.toSet()) {
        get("ops/openapi.json") {
            call.respondText(source.read(application, base).content, ContentType.Application.Json)
        }.hide()
    }
}
