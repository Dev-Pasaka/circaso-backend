package com.circaso.identity.presentation.controllers

import com.circaso.identity.presentation.dto.WebDeletionRequest
import com.circaso.identity.presentation.dto.WebDeletionResponse
import com.circaso.identity.presentation.mappers.toCommand
import com.circaso.identity.presentation.mappers.toResponse
import com.circaso.identity.presentation.services.WebDeletionService
import com.circaso.shared.flatMap
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Account and security"

/** I17: the website's account deletion page (Google Play requires one), signed in with Google or Apple. */
fun Route.webDeletionController(deletions: WebDeletionService) {
    // No token: the Google or Apple ID token in the body is the proof.
    route("/web") {
        post("account-deletion") {
            val result = call.receive<WebDeletionRequest>().toCommand().flatMap { deletions.request(it) }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<WebDeletionRequest, WebDeletionResponse>(
            "I17 · Schedule account deletion from the website, proved by a Google or Apple sign-in",
            TAG,
            listOf(
                "identity.nonce_invalid",
                "identity.provider_token_invalid",
                "request.malformed",
                "request.rate_limited",
            ),
            notes =
                "No token needed; send an `Idempotency-Key`. The page gets a nonce from I1 (`POST " +
                    "/v1/auth/nonce`), passes it to Google or Apple sign-in, then sends the ID token here; it is " +
                    "checked as at I2 and the nonce is used up. The account is the one that owns that login. " +
                    "`data.status`: `scheduled` (with `deleteAfter`, 30 days on), `already_scheduled`, `no_account` " +
                    "(no account has this login), or `refused` with the refusal's `code`: `wallet.funds_remaining`, " +
                    "`wallet.custody_unavailable`, `identity.account_suspended`, `identity.account_blocked` or " +
                    "`identity.recovery_pending`. Every session of the account ends. `reason` is one of " +
                    "`not_useful`, `privacy`, `too_many_apps`, `switching`, `problems`, `other`; `details` is " +
                    "optional, at most 500 characters; both are stored without the account. 20 requests per address " +
                    "per hour.",
        )
    }
}
