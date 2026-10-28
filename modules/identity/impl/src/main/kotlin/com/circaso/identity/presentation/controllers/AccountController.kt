package com.circaso.identity.presentation.controllers

import com.circaso.identity.presentation.dto.LinkProviderRequest
import com.circaso.identity.presentation.dto.SecurityLogResponse
import com.circaso.identity.presentation.dto.SecurityOverviewResponse
import com.circaso.identity.presentation.mappers.parseProvider
import com.circaso.identity.presentation.mappers.toCommand
import com.circaso.identity.presentation.mappers.toPrincipal
import com.circaso.identity.presentation.mappers.toResponse
import com.circaso.identity.presentation.services.AccountService
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.principal
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Account and security"

/** Account & security: the overview, linked sign-in accounts, the security log and deletion. I11 to I14. */
fun Route.accountController(account: AccountService) {
    route("/account") {
        authenticate(Scope.APP) {
            get("security") {
                call.respondDefault(
                    account.overview(call.principal().toPrincipal()).toDefaultResponse { it.toResponse() },
                )
            }.docs<NoBody, SecurityOverviewResponse>(
                "I11 · Phone, linked sign-in accounts, this phone and verification status",
                TAG,
                notes = "Scope: `app`.",
            )

            get("security-log") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val result =
                    account.securityLog(
                        call.principal().toPrincipal(),
                        call.request.queryParameters["cursor"],
                        limit,
                    )
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, SecurityLogResponse>(
                "I13 · Security activity, newest first",
                TAG,
                notes = "Scope: `app`. Query: `cursor`, `limit` (default 50, at most 200).",
            )

            delete {
                call.respondDefault(account.delete(call.principal().toPrincipal()).toDefaultResponse { it })
            }.docs<NoBody, NoBody>(
                "I14 · Schedule account deletion once every guard agrees",
                TAG,
                listOf("wallet.funds_remaining", "wallet.custody_unavailable"),
                notes =
                    "Scope: `app`. Each module that holds something of yours is asked first; the wallet refuses " +
                        "while it holds 0.01 USDC or more on any chain, with its own code, and when Circle can't " +
                        "answer (`wallet.custody_unavailable`, try again).",
            )

            route("/providers") {
                post {
                    val result =
                        call
                            .receive<LinkProviderRequest>()
                            .toCommand(
                                call.principal(),
                            ).flatMap { account.link(it) }
                    call.respondDefault(result.toDefaultResponse { it })
                }.docs<LinkProviderRequest, NoBody>(
                    "I12 · Link a Google or Apple login",
                    TAG,
                    listOf("identity.provider_in_use", "identity.nonce_invalid"),
                    notes = "Scope: `app`.",
                )

                delete("{provider}") {
                    val provider = parseProvider(call.parameters["provider"])
                    val result: Resource<Unit> =
                        if (provider == null) {
                            Resource.error(SharedError.Malformed("provider", "is not valid"))
                        } else {
                            account.unlink(call.principal().toPrincipal(), provider)
                        }
                    call.respondDefault(result.toDefaultResponse { it })
                }.docs<NoBody, NoBody>(
                    "I12 · Unlink a login, only if the other one stays",
                    TAG,
                    listOf("identity.provider_last"),
                    notes = "Scope: `app`.",
                )
            }
        }
    }
}
