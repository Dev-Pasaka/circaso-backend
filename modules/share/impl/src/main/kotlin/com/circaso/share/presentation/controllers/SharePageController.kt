package com.circaso.share.presentation.controllers

import com.circaso.messaging.api.GroupLinkToken
import com.circaso.share.domain.models.GroupPageLookup
import com.circaso.share.domain.models.PageLookup
import com.circaso.share.domain.support.ShareConfig
import com.circaso.share.presentation.dto.AppLinksComponentDto
import com.circaso.share.presentation.dto.AppLinksDetailDto
import com.circaso.share.presentation.dto.AppLinksDto
import com.circaso.share.presentation.dto.AppSiteAssociationDto
import com.circaso.share.presentation.dto.AssetLinkDto
import com.circaso.share.presentation.dto.AssetLinkTargetDto
import com.circaso.share.presentation.mappers.Platform
import com.circaso.share.presentation.mappers.SharePages
import com.circaso.share.presentation.services.SharePageService
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import com.circaso.web.EnvelopeSent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.builtins.ListSerializer

/**
 * The public side of share links, at the root of the share host rather than under `/v1` (decision 2026-11-06):
 * `GET /s/{code}`, the page for browsers and link previews (a share link's, or a group join link's with its published
 * photo at `/s/{token}/photo`, decision 2026-11-14), and the two files that make a link open the app when it
 * is installed, `/.well-known/assetlinks.json` (Android App Links) and `/.well-known/apple-app-site-association` (iOS
 * Universal Links). No token, no envelope: they answer with the page or the file. The page counts views per caller
 * address itself, is `noindex`, never cached, and loads nothing but its own style and the logo. Hidden from the
 * OpenAPI spec.
 */
@OptIn(ExperimentalKtorApi::class)
fun Route.sharePageController(pages: SharePageService, config: ShareConfig) {
    route("/s") {
        get("{code}") {
            val platform = Platform.of(call.request.header(HttpHeaders.UserAgent))
            // A group join link (decision 2026-11-14): a 22-character token where a share code has 12.
            val groupToken = GroupLinkToken.parse(call.parameters["code"])
            if (groupToken != null) {
                call.respondGroupPage(pages, config, groupToken, platform)
                return@get
            }
            val lookup = pages.view(call.parameters["code"], call.callerAddress())
            val found = (lookup as? Resource.Success)?.data
            when (found) {
                is PageLookup.Live -> {
                    call.respondPage(SharePages.live(found.link, config, platform), HttpStatusCode.OK)
                }

                is PageLookup.Gone -> {
                    call.respondPage(SharePages.gone(config, platform, found.isRevoked), HttpStatusCode.Gone)
                }

                is PageLookup.Throttled -> {
                    call.response.header(HttpHeaders.RetryAfter, found.retryAfterSeconds.toString())
                    call.respondPage(SharePages.throttled(config, platform), HttpStatusCode.TooManyRequests)
                }

                is PageLookup.Missing, null -> {
                    call.respondPage(SharePages.missing(config, platform), HttpStatusCode.NotFound)
                }
            }
        }.hide()

        // The photo an admin's phone published for a group join link: for the page, its preview and the app.
        get("{code}/photo") {
            val token = GroupLinkToken.parse(call.parameters["code"])
            val photo = token?.let { (pages.groupPhoto(it, call.callerAddress()) as? Resource.Success)?.data }
            call.attributes.put(EnvelopeSent, true)
            call.response.header("X-Robots-Tag", "noindex, nofollow")
            call.response.header("X-Content-Type-Options", "nosniff")
            if (photo == null) {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText("", ContentType.Text.Plain, HttpStatusCode.NotFound)
            } else {
                // Short: a reset or turned-off link's photo is gone from caches within minutes. The page names a
                // new `?v=` whenever the photo changes.
                call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
                call.respondBytes(photo.bytes, ContentType.parse(photo.contentType), HttpStatusCode.OK)
            }
        }.hide()
    }

    route("/.well-known") {
        get("assetlinks.json") {
            if (config.androidCertFingerprints.isEmpty()) {
                call.respondFile(NOT_SET, HttpStatusCode.NotFound)
                return@get
            }
            val statement =
                AssetLinkDto(
                    relation = listOf("delegate_permission/common.handle_all_urls"),
                    target = AssetLinkTargetDto("android_app", config.androidPackage, config.androidCertFingerprints),
                )
            call.respondFile(CircasoJson.encodeToString(ListSerializer(AssetLinkDto.serializer()), listOf(statement)))
        }.hide()

        get("apple-app-site-association") {
            if (config.iosAppIds.isEmpty()) {
                call.respondFile(NOT_SET, HttpStatusCode.NotFound)
                return@get
            }
            val association =
                AppSiteAssociationDto(
                    AppLinksDto(
                        details =
                            listOf(
                                AppLinksDetailDto(
                                    appIDs = config.iosAppIds,
                                    components = listOf(AppLinksComponentDto("/s/*", "Share links")),
                                    paths = listOf("/s/*"),
                                ),
                            ),
                    ),
                )
            call.respondFile(CircasoJson.encodeToString(AppSiteAssociationDto.serializer(), association))
        }.hide()
    }
}

private const val NOT_SET = "{}"

/** The caller's address as nginx passes it (it replaces anything the caller sent), else the direct peer. */
private fun ApplicationCall.callerAddress(): String =
    request.header("CF-Connecting-IP")?.trim()?.takeIf { it.isNotEmpty() } ?: request.local.remoteHost

private suspend fun ApplicationCall.respondGroupPage(
    pages: SharePageService,
    config: ShareConfig,
    token: GroupLinkToken,
    platform: Platform,
) {
    val csp = SharePages.GROUP_CONTENT_SECURITY_POLICY
    when (val found = (pages.viewGroup(token, callerAddress()) as? Resource.Success)?.data) {
        is GroupPageLookup.Live -> {
            respondPage(SharePages.group(found.token, found.page, config, platform), HttpStatusCode.OK, csp)
        }

        is GroupPageLookup.Throttled -> {
            response.header(HttpHeaders.RetryAfter, found.retryAfterSeconds.toString())
            respondPage(SharePages.throttled(config, platform), HttpStatusCode.TooManyRequests)
        }

        is GroupPageLookup.Gone, null -> {
            respondPage(SharePages.groupGone(config, platform), HttpStatusCode.Gone)
        }
    }
}

private suspend fun ApplicationCall.respondPage(
    html: String,
    status: HttpStatusCode,
    contentSecurityPolicy: String = SharePages.CONTENT_SECURITY_POLICY,
) {
    // The page is the answer: without the flag the 404 handler would swap it for the JSON envelope.
    attributes.put(EnvelopeSent, true)
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("X-Robots-Tag", "noindex, nofollow")
    response.header("Content-Security-Policy", contentSecurityPolicy)
    response.header("Referrer-Policy", "no-referrer")
    response.header("X-Content-Type-Options", "nosniff")
    respondText(html, ContentType.Text.Html.withParameter("charset", "utf-8"), status)
}

/** The association files: JSON with no redirect, short cache, so a changed fingerprint is picked up within an hour. */
private suspend fun ApplicationCall.respondFile(json: String, status: HttpStatusCode = HttpStatusCode.OK) {
    attributes.put(EnvelopeSent, true)
    response.header(HttpHeaders.CacheControl, "public, max-age=3600")
    respondText(json, ContentType.Application.Json, status)
}
