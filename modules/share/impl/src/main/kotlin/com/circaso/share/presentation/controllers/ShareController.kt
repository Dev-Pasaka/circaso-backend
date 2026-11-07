package com.circaso.share.presentation.controllers

import com.circaso.share.domain.support.ShareConfig
import com.circaso.share.presentation.dto.CreateShareLinkRequest
import com.circaso.share.presentation.dto.RevokedResponse
import com.circaso.share.presentation.dto.ShareLinkDto
import com.circaso.share.presentation.dto.ShareLinksResponse
import com.circaso.share.presentation.dto.SharedLinkResponse
import com.circaso.share.presentation.mappers.parseCode
import com.circaso.share.presentation.mappers.parseCursor
import com.circaso.share.presentation.mappers.parseLimit
import com.circaso.share.presentation.mappers.toCommand
import com.circaso.share.presentation.mappers.toDto
import com.circaso.share.presentation.mappers.toResponse
import com.circaso.share.presentation.services.ShareService
import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Share links"
private const val SCOPE = "Scope: `app`."
private const val IDEMPOTENT = "Send an `Idempotency-Key`."
private const val LINK_NOT_FOUND = "share.link_not_found"
private const val MALFORMED = "request.malformed"

/**
 * Share links (decision 2026-11-06): every share in the app sends a short link, `<SHARE_BASE_URL>/s/{code}`, to a
 * public page showing what the sharer chose. SH1 make one, SH2 list yours, SH3 turn one off, SH4 open one in the app.
 * The page itself, and the files that let a link open the app, are [sharePageController]'s.
 */
fun Route.shareController(shares: ShareService, config: ShareConfig) {
    route("/share/links") {
        authenticate(Scope.APP) {
            collectionRoutes(shares, config)
            route("/{code}") {
                linkRoutes(shares, config)
            }
        }
    }
}

private fun Route.collectionRoutes(shares: ShareService, config: ShareConfig) {
    post {
        val caller = call.userId()
        val result = call.receive<CreateShareLinkRequest>().toCommand(caller, config).flatMap { shares.create(it) }
        call.respondDefault(result.toDefaultResponse { it.toDto(config) })
    }.docs<CreateShareLinkRequest, ShareLinkDto>(
        "SH1 · Make a share link",
        TAG,
        listOf(
            "share.request_invalid",
            "share.source_not_found",
            "share.not_shareable",
            "share.unavailable",
            MALFORMED,
        ),
        notes =
            "$SCOPE $IDEMPOTENT At most 30 an hour. `kind`: `receipt` (with `transferId`, a payment you " +
                "sent, received or signed, once it was submitted), `statement` (with `chatId`, a group you are " +
                "in that has a target), `invite` (a fresh 7-day invite to chat with you; the link ends with it), " +
                "`pay_me` (needs your @handle) or `address` (with `blockchain`, your own wallet address there). " +
                "The page is built from the server's own records, never from what the phone sends. `showNames` " +
                "(default true) puts names, and a statement's notes, on the page; off, it shows amounts only. " +
                "`currency` (ISO 4217) adds the value in that currency at today's rate. `expiresInDays` (1 to " +
                "365) ends the link; without it, it opens until you turn it off. Nothing from any chat is ever " +
                "on a page. Share `url` with a short line of text (`title`, `summary`).",
    )

    get {
        val caller = call.userId()
        val after = parseCursor(call.request.queryParameters["cursor"])
        val result =
            parseLimit(call.request.queryParameters["limit"], config).flatMap { limit ->
                shares.list(caller, after, limit)
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse(config) })
    }.docs<NoBody, ShareLinksResponse>(
        "SH2 · Your share links",
        TAG,
        listOf(MALFORMED),
        notes =
            "$SCOPE `?cursor=&limit=${config.pageDefault}` (at most ${config.pageMax}). The links that still open, " +
                "newest first; ended and turned-off ones aren't listed. `nextCursor` continues, null on the last page.",
    )
}

private fun Route.linkRoutes(shares: ShareService, config: ShareConfig) {
    get {
        val caller = call.userId()
        val result = parseCode(call.parameters["code"]).flatMap { shares.open(caller, it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse(config) })
    }.docs<NoBody, SharedLinkResponse>(
        "SH4 · Open a share link in the app",
        TAG,
        listOf(LINK_NOT_FOUND),
        notes =
            "$SCOPE For a link opened on a phone with Circaso (App Links, Universal Links or `circaso://s/{code}`): " +
                "what the page shows, for the app to show natively, `mine` when you made it, and `open`, where your " +
                "app may go from it: `transfer` (your own payment), `chama` (a group you are in), `invite` (connect " +
                "with U11 `via: invite` and `inviteToken`), `pay` or `pay_address`; null when nowhere. Unknown, " +
                "turned-off and ended links all answer `share.link_not_found`.",
    )

    delete {
        val caller = call.userId()
        val result = parseCode(call.parameters["code"]).flatMap { shares.revoke(caller, it) }
        call.respondDefault(result.toDefaultResponse { RevokedResponse(revoked = true) })
    }.docs<NoBody, RevokedResponse>(
        "SH3 · Turn off a share link",
        TAG,
        listOf(LINK_NOT_FOUND),
        notes =
            "$SCOPE $IDEMPOTENT Yours only. What the page showed is deleted at once and the page says the link was " +
                "turned off; someone else's link, or one already off, answers `share.link_not_found`.",
    )
}
