package com.circaso.app

import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.hide
import io.ktor.utils.io.ExperimentalKtorApi

/** Where someone who lands on the API's own address is sent: this server has no website of its own. */
private const val WEBSITE = "https://circaso.com"

/**
 * `GET /`: nothing is served at the root, so a visitor is sent on to the website. A temporary redirect (302), so the
 * browser asks again rather than remembering it. Hidden from the OpenAPI spec.
 */
@OptIn(ExperimentalKtorApi::class)
fun Route.homeRoute() {
    get { call.respondRedirect(WEBSITE) }.hide()
}
