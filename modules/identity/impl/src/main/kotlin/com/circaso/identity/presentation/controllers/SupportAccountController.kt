package com.circaso.identity.presentation.controllers

import com.circaso.identity.presentation.dto.CreateSupportAccountRequest
import com.circaso.identity.presentation.dto.SupportAccountDto
import com.circaso.identity.presentation.dto.SupportAccountsResponse
import com.circaso.identity.presentation.mappers.toCommand
import com.circaso.identity.presentation.mappers.toDto
import com.circaso.identity.presentation.mappers.toResponse
import com.circaso.identity.presentation.services.SupportAccountService
import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.principal
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Support accounts"

/**
 * Circaso's own support accounts, behind the `ops` scope (decision 2026-10-31). OPS1 and OPS2. The app never calls
 * these; the support dashboard will.
 *
 * TODO(decision: ops sign-in for the support dashboard): no route issues an `ops` token to a person yet, so these
 * answer `auth.scope_insufficient` to every token that exists today.
 */
fun Route.supportAccountController(support: SupportAccountService) {
    route("/ops/support-accounts") {
        authenticate(Scope.OPS) {
            post {
                val result =
                    call
                        .receive<CreateSupportAccountRequest>()
                        .toCommand(call.principal())
                        .flatMap { support.create(it) }
                call.respondDefault(result.toDefaultResponse { it.toDto() })
            }.docs<CreateSupportAccountRequest, SupportAccountDto>(
                "OPS1 · Create a Circaso support account",
                TAG,
                listOf("users.handle_invalid", "users.handle_taken"),
                notes =
                    "Scope: `ops`. Idempotency-Key required. The account is `ACTIVE` at once and has no phone, " +
                        "device or Google/Apple login: it can never sign in on the app, never holds a wallet, and " +
                        "its profile shows `support: true`. The handle may be a reserved name such as `circaso`, " +
                        "which no person can take. Users makes the profile a moment later, from " +
                        "`identity.SupportAccountCreated`.",
            )

            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val result = support.list(call.request.queryParameters["cursor"], limit)
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, SupportAccountsResponse>(
                "OPS2 · Every support account, oldest first",
                TAG,
                notes = "Scope: `ops`. Query: `cursor`, `limit` (default 50, at most 200).",
            )
        }
    }
}
