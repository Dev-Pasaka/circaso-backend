package com.circaso.ramp.presentation.controllers

import com.circaso.ramp.presentation.dto.CallbackResponse
import com.circaso.ramp.presentation.dto.ChangePinRequest
import com.circaso.ramp.presentation.dto.EarningsResponse
import com.circaso.ramp.presentation.dto.GoOnlineRequest
import com.circaso.ramp.presentation.dto.KycResponse
import com.circaso.ramp.presentation.dto.MerchantOrdersResponse
import com.circaso.ramp.presentation.dto.MerchantResponse
import com.circaso.ramp.presentation.dto.OnlineResponse
import com.circaso.ramp.presentation.dto.PaymentsRequest
import com.circaso.ramp.presentation.dto.PaymentsSavedResponse
import com.circaso.ramp.presentation.dto.PinChangedResponse
import com.circaso.ramp.presentation.dto.ProfileRequest
import com.circaso.ramp.presentation.dto.SignInCodeRequest
import com.circaso.ramp.presentation.dto.SignInCodeResponse
import com.circaso.ramp.presentation.dto.SignedInResponse
import com.circaso.ramp.presentation.dto.StatementResponse
import com.circaso.ramp.presentation.dto.VerifyCodeRequest
import com.circaso.ramp.presentation.mappers.cursorOf
import com.circaso.ramp.presentation.mappers.periodOf
import com.circaso.ramp.presentation.mappers.toCommand
import com.circaso.ramp.presentation.mappers.toEmail
import com.circaso.ramp.presentation.mappers.toMerchantResponse
import com.circaso.ramp.presentation.mappers.toPins
import com.circaso.ramp.presentation.mappers.toResponse
import com.circaso.ramp.presentation.mappers.toSavedResponse
import com.circaso.ramp.presentation.mappers.toSignIn
import com.circaso.ramp.presentation.mappers.toWindow
import com.circaso.ramp.presentation.services.MerchantAuthService
import com.circaso.ramp.presentation.services.MerchantService
import com.circaso.ramp.presentation.support.MERCHANT_SESSION_HEADER
import com.circaso.ramp.presentation.support.clientAddress
import com.circaso.ramp.presentation.support.merchantId
import com.circaso.ramp.presentation.support.merchantNetwork
import com.circaso.ramp.presentation.support.merchantSession
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

private const val TAG = "Merchant portal"
private const val SESSION = "Needs `X-Merchant-Session`."
private const val MONEY = "$SESSION Network: `X-Wallet-Network` (the testnet without it)."
private const val MALFORMED = "request.malformed"
private const val INVALID = "ramp.session_invalid"
private const val NOT_FOUND = "ramp.merchant_not_found"
private const val PIN_INVALID = "ramp.pin_invalid"
private const val PIN_LOCKED = "ramp.pin_locked"
private const val VAULT = "ramp.vault_unavailable"
private const val SETUP = "ramp.merchant_setup_incomplete"
private const val DEFAULT_LIMIT = 20
private const val MAX_LIMIT = 200

private fun ApplicationCall.pageLimit(): Int =
    (request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

/**
 * The merchant portal's API (decision 2026-11-22), for `merchants.circaso.com`: a partner merchant's own account,
 * signed in to with their email and a one-time code. Its session is the ramp's own (`X-Merchant-Session`), never an
 * app token. Staff approve a merchant on the ops gateway; the float wallet is made by the ops server, so it has no
 * route here.
 */
fun Route.merchantController(auth: MerchantAuthService, merchants: MerchantService) {
    route("/ramp/merchant") {
        route("/auth") { signInRoutes(auth) }
        merchantSession(auth) {
            accountRoutes(merchants)
            paymentRoutes(merchants)
            tradingRoutes(merchants)
            moneyRoutes(merchants)
        }
    }
}

private fun Route.signInRoutes(auth: MerchantAuthService) {
    post("code") {
        val request = call.receive<SignInCodeRequest>()
        val result = request.toEmail().flatMap { auth.requestCode(it, call.clientAddress()) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<SignInCodeRequest, SignInCodeResponse>(
        "MA1 · Email a sign-in code",
        TAG,
        listOf("ramp.code_locked", "ramp.email_unavailable", MALFORMED),
        notes =
            "No session needed. Emails a 6-digit code that lasts 10 minutes. The answer is the same whether or not " +
                "the email has an account. `retryAfterSeconds` is how long before another code may be asked for; " +
                "asking sooner, more than 3 times in 10 minutes, or more than 10 times an hour from one address " +
                "answers `ramp.code_locked` with `details.retryAfterSeconds`.",
    )

    post("verify") {
        val request = call.receive<VerifyCodeRequest>()
        val result = request.toSignIn().flatMap { (email, code) -> auth.verify(email, code, call.merchantNetwork()) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<VerifyCodeRequest, SignedInResponse>(
        "MA2 · Sign in with the code",
        TAG,
        listOf("ramp.code_invalid", "ramp.code_locked", MALFORMED),
        notes =
            "No session needed. A code allows 5 tries. The first verified sign-in creates the merchant account " +
                "(`NEW`). Send `sessionToken` as `X-Merchant-Session` from then on; it lasts 12 hours, and using " +
                "it in its last hour renews it.",
    )

    post("signout") {
        val result = auth.signOut(call.request.header(MERCHANT_SESSION_HEADER))
        call.respondDefault(result.toDefaultResponse { NoBody() })
    }.docs<NoBody, NoBody>(
        "MA3 · Sign out",
        TAG,
        notes = "Ends the session in `X-Merchant-Session` and takes the merchant offline. Always answers success.",
    )
}

private fun Route.accountRoutes(merchants: MerchantService) {
    get {
        val result = merchants.merchant(call.merchantId(), call.merchantNetwork())
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, MerchantResponse>(
        "RM2 · Your merchant account",
        TAG,
        listOf(INVALID, NOT_FOUND),
        notes =
            "$MONEY State, what is set up, the float wallet to fund and what it holds (`reserved` is open orders " +
                "plus Circaso's share not yet swept), and `missing`: what still stands between you and going " +
                "online (`profile`, `kyc`, `approval`, `wallet`, `payments`, `float`, `provider_balance`). `kyc` " +
                "is your identity verification on this network (`status`, `required`, `canStart`, `reason`, " +
                "`retryAt`): start or continue it with RM3. " +
                "`paymentsIssue` (`{ code, since }`, null when there is none) says your payment provider refused a " +
                "prompt for a reason only you can fix: `SERVICE_BALANCE` is an empty prepaid service wallet " +
                "(\"Top up your PayHero service wallet\"). It takes you offline and puts `provider_balance` in " +
                "`missing`; `canGoOnline` stays true, because going online again (RM6) checks the balance and " +
                "clears it.",
    )

    put("profile") {
        val request = call.receive<ProfileRequest>()
        val result =
            request.toCommand().flatMap { merchants.apply(call.merchantId(), call.merchantNetwork(), it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<ProfileRequest, MerchantResponse>(
        "RM1 · Your name and contact phone",
        TAG,
        listOf(INVALID, NOT_FOUND, MALFORMED),
        notes =
            "$MONEY The first time (from `NEW`) this applies as a partner (`APPLIED`); later it updates the two " +
                "fields and leaves the state alone.",
    )

    post("kyc") {
        val result = merchants.startKyc(call.merchantId(), call.merchantNetwork())
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, KycResponse>(
        "RM3 · Verify your identity (start or continue)",
        TAG,
        listOf(
            INVALID,
            NOT_FOUND,
            SETUP,
            "ramp.merchant_not_approved",
            "ramp.kyc_attempts_exceeded",
            "ramp.kyc_unavailable",
            "ramp.kyc_not_configured",
        ),
        notes =
            "$MONEY ID document, liveness selfie, face match and screening, needed before Circaso can approve you " +
                "as a partner. Needs your profile first (`$SETUP` otherwise). While `session` is there a check is " +
                "open: send the browser to `session.url`, the identity provider's hosted page; when the merchant " +
                "finishes it returns to the portal at `/kyc/done?verificationSessionId=…&status=…`. Never trust " +
                "that `status`: read RM2, whose `kyc` is the truth (it asks the provider while the check is " +
                "unfinished). Calling again returns the same open check. With no `session` there is nothing to do " +
                "now: `status` and `reason` say why. A declined or expired check may be started again a few times " +
                "a day (`ramp.kyc_attempts_exceeded`, 429).",
    )

    put("pin") {
        val request = call.receive<ChangePinRequest>()
        val result =
            request.toPins().flatMap { (current, new) -> merchants.changePin(call.merchantId(), current, new) }
        call.respondDefault(result.toDefaultResponse { PinChangedResponse() })
    }.docs<ChangePinRequest, PinChangedResponse>(
        "RM9 · Change your PIN",
        TAG,
        listOf(INVALID, PIN_INVALID, PIN_LOCKED, SETUP, VAULT, MALFORMED),
        notes = "$SESSION The current PIN is required; five wrong PINs lock PIN entry for 30 minutes.",
    )
}

private fun Route.paymentRoutes(merchants: MerchantService) {
    route("/payments") {
        put {
            val request = call.receive<PaymentsRequest>()
            val result =
                request.toCommand().flatMap {
                    merchants.storePayments(call.merchantId(), call.merchantNetwork(), it)
                }
            call.respondDefault(result.toDefaultResponse { it.toSavedResponse() })
        }.docs<PaymentsRequest, PaymentsSavedResponse>(
            "RM5 · Connect your PayHero account",
            TAG,
            listOf(
                INVALID,
                "ramp.credentials_rejected",
                "ramp.collections_unavailable",
                PIN_INVALID,
                PIN_LOCKED,
                SETUP,
                "ramp.merchant_not_approved",
                VAULT,
                MALFORMED,
            ),
            notes =
                "$MONEY Allowed once you have applied (`APPLIED` or `APPROVED`). The credentials are checked with " +
                    "PayHero, then sealed under your 6-digit PIN: this call sets the PIN the first time, and needs " +
                    "the same PIN afterwards. Circaso can't read them without your PIN. Paste `callbackUrl` into " +
                    "your PayHero account.",
        )

        post("callback") {
            val result = merchants.rotateCallback(call.merchantId(), call.merchantNetwork())
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<NoBody, CallbackResponse>(
            "RM14 · A new callback address",
            TAG,
            listOf(INVALID, SETUP),
            notes = "$MONEY The old address stops working at once: paste the new one into PayHero.",
        )
    }
}

private fun Route.tradingRoutes(merchants: MerchantService) {
    route("/session") {
        post {
            val request = call.receive<GoOnlineRequest>()
            val result =
                request.toWindow().flatMap { (pin, minutes) ->
                    merchants.goOnline(call.merchantId(), call.merchantNetwork(), pin, minutes)
                }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<GoOnlineRequest, OnlineResponse>(
            "RM6 · Go online",
            TAG,
            listOf(INVALID, "ramp.merchant_not_approved", SETUP, PIN_INVALID, PIN_LOCKED, VAULT, MALFORMED),
            notes =
                "$MONEY Your PIN and a trading window in `minutes` (15 to 720). Needs your identity verified where " +
                    "the network requires it (`$SETUP` with the field `kyc` otherwise), approval, a float wallet " +
                    "with enough USDC for the smallest order, and a connected payment account. Your provider's " +
                    "prepaid " +
                    "service wallet (what its fee per prompt is taken from) is checked first: below the network's " +
                    "floor the answer is `$SETUP` with the field `provider_balance`; top it up and try again. A " +
                    "balance the provider can't report never blocks. Going online clears `paymentsIssue`.",
        )

        delete {
            val result = merchants.goOffline(call.merchantId())
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<NoBody, OnlineResponse>(
            "RM6 · Go offline",
            TAG,
            listOf(INVALID),
            notes = "$SESSION New orders stop; open ones finish.",
        )
    }

    get("orders") {
        val limit = call.pageLimit()
        val after = cursorOf(call.request.queryParameters["cursor"])
        val result = merchants.orders(call.merchantId(), call.merchantNetwork(), limit, after)
        call.respondDefault(result.toDefaultResponse { it.toMerchantResponse(limit) })
    }.docs<NoBody, MerchantOrdersResponse>(
        "RM7 · Your orders, newest first",
        TAG,
        listOf(INVALID),
        notes = "$MONEY `?limit=` (default 20, at most 200) and `?cursor=<the previous page's next>`.",
    )
}

private fun Route.moneyRoutes(merchants: MerchantService) {
    get("earnings") {
        val query = call.request.queryParameters
        val result =
            periodOf(query["from"], query["to"]).flatMap {
                merchants.earnings(call.merchantId(), call.merchantNetwork(), it)
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, EarningsResponse>(
        "RM8 · What you made in a period",
        TAG,
        listOf(INVALID, MALFORMED),
        notes =
            "$MONEY `?from=&to=` are calendar days in Nairobi (`2026-11-01`), inclusive; without them, this " +
                "month. Orders completed, shillings collected, USDC sold, the fees, your share and Circaso's, how " +
                "much of Circaso's has been swept from your float and how much it still holds, and the daily " +
                "sweeps whose day falls in the period.",
    )

    get("statement") {
        val query = call.request.queryParameters
        val limit = call.pageLimit()
        val result =
            periodOf(query["from"], query["to"]).flatMap {
                val after = cursorOf(query["cursor"])
                merchants.statement(call.merchantId(), call.merchantNetwork(), it, limit, after)
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse(limit) })
    }.docs<NoBody, StatementResponse>(
        "RM15 · Your statement for a period",
        TAG,
        listOf(INVALID, MALFORMED),
        notes =
            "$MONEY One line per completed exchange, newest first, `?limit=` (default 20, at most 200) and " +
                "`?cursor=`. `totals` cover the whole period and equal the sum of its lines. `settlementId` is " +
                "null while Circaso's share of that order is still held in your float; `txHash` is the release " +
                "of the USDC to the buyer.",
    )
}
