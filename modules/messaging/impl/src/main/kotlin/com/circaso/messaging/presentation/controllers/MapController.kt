package com.circaso.messaging.presentation.controllers

import com.circaso.messaging.presentation.mappers.toStaticMapCommand
import com.circaso.messaging.presentation.services.MapService
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private const val TAG = "Chat media"

/**
 * MAP1, the preview picture in a location message (decision 2026-10-30). It answers the PNG itself, not the
 * envelope, so the phone can hand it straight to its image loader; failures still answer the envelope.
 */
fun Route.mapController(maps: MapService) {
    route("/maps") {
        authenticate(Scope.APP) {
            staticRoute(maps)
        }
    }
}

private fun Route.staticRoute(maps: MapService) {
    get("static") {
        when (
            val result =
                call.request.queryParameters
                    .toStaticMapCommand()
                    .flatMap { maps.staticMap(it) }
        ) {
            is Resource.Success -> {
                // A map of a point doesn't change; the phone keeps it a day rather than asking again.
                call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
                call.respondBytes(result.data.bytes, ContentType.parse(result.data.contentType))
            }

            is Resource.Error -> {
                call.respondError(result.error)
            }
        }
    }.docs<NoBody, NoBody>(
        "MAP1 · Map picture for a location message",
        TAG,
        listOf("messaging.map_invalid", "messaging.map_unavailable", "request.malformed"),
        notes =
            "Scope: `app`. Query: `lat`, `lng` (degrees), `zoom` (3–20), `w` and `h` (points, 16–640; the " +
                "picture is drawn at 2×) and the theme's colours as `RRGGBB`: `land`, `road`, `highway`, " +
                "`water`, `label`. Answers `image/png` with no marker: the app draws its own pin. The point is " +
                "passed to the map provider and never stored or logged.",
        envelope = false,
    )
}
