package com.circaso.ramp.presentation.controllers

import com.circaso.ramp.presentation.dto.KycResponse
import com.circaso.ramp.presentation.dto.OrderResponse
import com.circaso.ramp.presentation.dto.OrdersResponse
import com.circaso.ramp.presentation.dto.QuoteRequest
import com.circaso.ramp.presentation.dto.QuoteResponse
import com.circaso.ramp.presentation.dto.RampStatusResponse
import com.circaso.ramp.presentation.dto.StartOnRampRequest
import com.circaso.ramp.presentation.dto.VerificationDto
import com.circaso.ramp.presentation.mappers.cursorOf
import com.circaso.ramp.presentation.mappers.orderIdOf
import com.circaso.ramp.presentation.mappers.toCommand
import com.circaso.ramp.presentation.mappers.toDto
import com.circaso.ramp.presentation.mappers.toResponse
import com.circaso.ramp.presentation.services.RampService
import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.authenticateOnNetwork
import com.circaso.web.docs
import com.circaso.web.principal
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.walletNetwork
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Ramp"
private const val SCOPE = "Scope: `app`. Network: `X-Wallet-Network`."
private const val MALFORMED = "request.malformed"
private const val UNAVAILABLE = "ramp.unavailable"
private const val KYC = "ramp.kyc_required"
private const val RESTRICTED = "ramp.account_restricted"
private const val BLOCKED = "ramp.blocked"
private const val NOT_FOUND = "ramp.order_not_found"
private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

/**
 * The ramp for the app: buying USDC with M-Pesa from a partner merchant (decision 2026-11-22). R0 verifying your
 * identity once, R1 whether you can trade, R2 a price, R3 the order and its M-Pesa prompt, R4 and R5 your orders, R6
 * cancelling before you pay. Every
 * route is about money on one network, so all of them sit in the wallet-network group. Selling waits on a payout
 * provider.
 */
fun Route.rampController(ramp: RampService) {
    route("/ramp") {
        authenticateOnNetwork(Scope.APP) {
            get {
                val result = ramp.status(call.principal().userId, call.walletNetwork())
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, RampStatusResponse>(
                "R1 · Whether you can buy now",
                TAG,
                listOf(UNAVAILABLE),
                notes =
                    "$SCOPE `canTrade` is every gate at once: identity verified (or not required), not barred, the " +
                        "account active, a merchant online and no order of yours still open. No merchant is ever " +
                        "listed. `verification` is the identity check in full: `status` (`NONE`, `PENDING`, " +
                        "`IN_REVIEW`, `VERIFIED`, `DECLINED`, `EXPIRED`), `required` (whether this network asks " +
                        "for it), `canStart` (whether R0 will hand out a check now), `reason` (a code to word), " +
                        "`retryAt` (when today's tries come back) and `submitted` (the person finished their " +
                        "part and the provider hasn't decided: say \"we're checking\", never \"finish " +
                        "verifying\"; `canStart` is false). `kyc` is the short form older apps read. " +
                        "`blocked` is a bar on the account or its identity: say so and point to support. " +
                        "`ramp.unavailable` means buying isn't open to you on this network: hide the feature.",
            )

            route("/kyc") {
                post {
                    val result = ramp.startKyc(call.principal().userId, call.walletNetwork())
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<NoBody, KycResponse>(
                    "R0 · Verify your identity (start or continue)",
                    TAG,
                    listOf(
                        UNAVAILABLE,
                        RESTRICTED,
                        BLOCKED,
                        "ramp.kyc_attempts_exceeded",
                        "ramp.kyc_unavailable",
                        "ramp.kyc_not_configured",
                    ),
                    notes =
                        "$SCOPE Once, before your first purchase. While `session` is there a check is open: start " +
                            "the identity provider's native SDK with `session.sessionToken` (or open `session.url`, " +
                            "its hosted page, as the fallback); both work until `session.expiresAt`. Calling again " +
                            "returns the same open check, never a second one. A declined or expired check may be " +
                            "started again, a few times a day (`ramp.kyc_attempts_exceeded`, 429, with " +
                            "`details.retryAfterSeconds`). With no `session` there is nothing to do now: `status`, " +
                            "`submitted` and `reason` say why (already `VERIFIED`, `IN_REVIEW`, `PENDING` with " +
                            "`submitted` true: finished and waiting for the provider's answer, or declined for " +
                            "a reason trying again can't fix). The outcome arrives as a `ramp/kyc.updated` " +
                            "frame, and a push when it is final. `ramp.kyc_unavailable` (502): the provider is " +
                            "out of reach, try again in a moment. `ramp.kyc_not_configured` (503): checks aren't set up on this network yet.",
                )

                get {
                    val result = ramp.kyc(call.principal().userId, call.walletNetwork())
                    call.respondDefault(result.toDefaultResponse { it.toDto() })
                }.docs<NoBody, VerificationDto>(
                    "R0 · Your identity verification",
                    TAG,
                    listOf(UNAVAILABLE),
                    notes =
                        "$SCOPE The same fields as R1's `verification`. Call it when the provider's SDK closes: " +
                            "while a check is unfinished this asks the provider itself (at most every few seconds), " +
                            "so the outcome shows at once even before the frame arrives. `PENDING` with `submitted` " +
                            "true means the check was finished and the provider hasn't decided yet: keep reading " +
                            "(or wait for the frame) and never reopen the SDK. It never returns a link.",
                )
            }

            post("quotes") {
                val userId = call.principal().userId
                val request = call.receive<QuoteRequest>()
                val result = request.toCommand(userId, call.walletNetwork()).flatMap { ramp.quote(it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<QuoteRequest, QuoteResponse>(
                "R2 · Price an amount",
                TAG,
                listOf(
                    UNAVAILABLE,
                    KYC,
                    BLOCKED,
                    RESTRICTED,
                    "ramp.chain_unsupported",
                    "ramp.amount_invalid",
                    "ramp.limit_exceeded",
                    "ramp.offramp_unavailable",
                    "ramp.pricing_unavailable",
                    MALFORMED,
                ),
                notes =
                    "$SCOPE Give `fiat` (what you pay, whole shillings) or `crypto` (what you want to receive). The " +
                        "fee is inside what you pay and the quote shows it. The price holds until `expiresAt` and " +
                        "reserves nothing. Only `ON_RAMP` is offered.",
            )

            post("onramp/orders") {
                val userId = call.principal().userId
                val request = call.receive<StartOnRampRequest>()
                val result = request.toCommand(userId, call.walletNetwork()).flatMap { ramp.buy(it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<StartOnRampRequest, OrderResponse>(
                "R3 · Buy USDC with M-Pesa",
                TAG,
                listOf(
                    UNAVAILABLE,
                    KYC,
                    BLOCKED,
                    RESTRICTED,
                    "ramp.quote_not_found",
                    "ramp.quote_expired",
                    "ramp.order_in_progress",
                    "ramp.phone_not_yours",
                    "ramp.wallet_required",
                    "ramp.too_many_prompts",
                    "ramp.limit_exceeded",
                    "ramp.no_merchant_available",
                    "ramp.merchant_unavailable",
                    "ramp.collections_unavailable",
                    "ramp.custody_unavailable",
                    MALFORMED,
                ),
                notes =
                    "$SCOPE Sends an M-Pesa prompt to `phone`, which must be your account's own verified number. " +
                        "Once M-Pesa confirms the payment, the USDC is sent to your wallet on the quote's chain " +
                        "(within a few seconds) and `ramp/order.updated` frames follow the order. Repeating the " +
                        "request with the same quote returns the same order and sends no second prompt. " +
                        "`ramp.merchant_unavailable` (409): the payment couldn't be started with any seller right " +
                        "now and nothing was taken; ask for a new quote and try again in a moment. " +
                        "`ramp.collections_unavailable` (502) is M-Pesa itself out of reach.",
            )

            route("/orders") {
                get {
                    val userId = call.principal().userId
                    val asked = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
                    val limit = asked.coerceIn(1, MAX_LIMIT)
                    val after = cursorOf(call.request.queryParameters["cursor"])
                    val result = ramp.orders(userId, call.walletNetwork(), limit, after)
                    call.respondDefault(result.toDefaultResponse { it.toResponse(limit) })
                }.docs<NoBody, OrdersResponse>(
                    "R5 · Your orders, newest first",
                    TAG,
                    notes = "$SCOPE `?limit=` (default 50, at most 200) and `?cursor=<the previous page's next>`.",
                )

                route("/{orderId}") {
                    get {
                        val userId = call.principal().userId
                        val result = orderIdOf(call.parameters["orderId"]).flatMap { ramp.order(userId, it) }
                        call.respondDefault(result.toDefaultResponse { it.toResponse() })
                    }.docs<NoBody, OrderResponse>(
                        "R4 · One order",
                        TAG,
                        listOf(NOT_FOUND, MALFORMED),
                        notes = "$SCOPE Only the user who made the order sees it.",
                    )

                    post("cancel") {
                        val userId = call.principal().userId
                        val result = orderIdOf(call.parameters["orderId"]).flatMap { ramp.cancel(userId, it) }
                        call.respondDefault(result.toDefaultResponse { it.toResponse() })
                    }.docs<NoBody, OrderResponse>(
                        "R6 · Cancel before you pay",
                        TAG,
                        listOf(NOT_FOUND, "ramp.order_not_cancellable", MALFORMED),
                        notes =
                            "$SCOPE While the M-Pesa prompt is still open on your phone the order can't be " +
                                "cancelled: dismiss the prompt and it ends by itself.",
                    )
                }
            }
        }
    }
}
