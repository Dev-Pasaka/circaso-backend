package com.circaso.wallet.presentation.controllers

import com.circaso.shared.Resource
import com.circaso.wallet.presentation.services.CoinIconService
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private const val TAG = "Wallet"
private val SVG = ContentType("image", "svg+xml")

/** A week, and never revalidated within it: a symbol's icon only changes with a new vendored set. */
private const val CACHE = "public, max-age=604800, immutable"

/**
 * IC1, coin icons by symbol (decision 2026-11-06). Public: an icon carries nothing about anyone, and a plain image
 * loader (and the CDN in front of the API) can fetch and keep it without a token. It answers the SVG itself, not the
 * envelope; failures still answer the envelope.
 */
fun Route.coinIconController(icons: CoinIconService) {
    route("/assets/icons") {
        get("{symbol}") {
            when (val result = icons.icon(call.parameters["symbol"].orEmpty())) {
                is Resource.Success -> {
                    val icon = result.data
                    call.response.header(HttpHeaders.CacheControl, CACHE)
                    call.response.header(HttpHeaders.ETag, icon.etag)
                    // An SVG on the API's own origin: never sniffed as anything else, and nothing in it may run.
                    call.response.header("X-Content-Type-Options", "nosniff")
                    call.response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                    val seen = call.request.header(HttpHeaders.IfNoneMatch)
                    if (seen != null && seen.split(',').any { it.trim().removePrefix("W/") == icon.etag }) {
                        call.respond(HttpStatusCode.NotModified)
                    } else {
                        call.respondBytes(icon.svg, SVG)
                    }
                }

                is Resource.Error -> {
                    call.respondError(result.error)
                }
            }
        }.docs<NoBody, NoBody>(
            "IC1 · A coin's icon, by symbol",
            TAG,
            listOf("wallet.icon_symbol_invalid", "wallet.icon_not_found"),
            notes =
                "No token needed. `symbol` is the coin's symbol, any case (`USDC`, `weth`), 1–15 letters, digits or " +
                    "dots. Answers `image/svg+xml`: the Web3 Icons mono mark (MIT), one colour in a 24 × 24 box, for " +
                    "the app to paint in its theme. Cached a week (`public, max-age=604800, immutable`) with an " +
                    "`ETag`; send `If-None-Match` to get 304. The app maps wrapped and renamed coins to their " +
                    "original first (`ETH` for WETH, `POL` for MATIC).",
            envelope = false,
        )
    }
}
