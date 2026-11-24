package com.circaso.ramp.presentation.controllers

import com.circaso.ramp.presentation.dto.AssertionChallengeResponse
import com.circaso.ramp.presentation.dto.LockRequest
import com.circaso.ramp.presentation.dto.LockResponse
import com.circaso.ramp.presentation.dto.NotificationsRequest
import com.circaso.ramp.presentation.dto.NotificationsResponse
import com.circaso.ramp.presentation.dto.PasskeyOptionsRequest
import com.circaso.ramp.presentation.dto.PasskeyRegistrationRequest
import com.circaso.ramp.presentation.dto.PasskeyResponse
import com.circaso.ramp.presentation.dto.PasskeySignInRequest
import com.circaso.ramp.presentation.dto.PasskeysResponse
import com.circaso.ramp.presentation.dto.RegistrationChallengeResponse
import com.circaso.ramp.presentation.dto.RemovePasskeyRequest
import com.circaso.ramp.presentation.dto.RemovedResponse
import com.circaso.ramp.presentation.dto.SecurityCodeRequest
import com.circaso.ramp.presentation.dto.SignInCodeResponse
import com.circaso.ramp.presentation.dto.SignedInResponse
import com.circaso.ramp.presentation.dto.StepUpRequest
import com.circaso.ramp.presentation.mappers.challengeIdOf
import com.circaso.ramp.presentation.mappers.idOf
import com.circaso.ramp.presentation.mappers.toAssertion
import com.circaso.ramp.presentation.mappers.toBinding
import com.circaso.ramp.presentation.mappers.toChoices
import com.circaso.ramp.presentation.mappers.toJson
import com.circaso.ramp.presentation.mappers.toNotifications
import com.circaso.ramp.presentation.mappers.toParts
import com.circaso.ramp.presentation.mappers.toProof
import com.circaso.ramp.presentation.mappers.toPurpose
import com.circaso.ramp.presentation.mappers.toResponse
import com.circaso.ramp.presentation.services.SecurityService
import com.circaso.ramp.presentation.support.clientAddress
import com.circaso.ramp.presentation.support.merchantId
import com.circaso.ramp.presentation.support.merchantNetwork
import com.circaso.ramp.presentation.support.signInContext
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

private const val TAG = "Merchant portal"
private const val SESSION = "Needs `X-Merchant-Session`."
private const val NO_SESSION = "No session needed."
private const val MAX_TOKEN = 200

/**
 * Passkeys on the merchant portal (2026-11-24): the list, adding one (a fresh email code and the PIN first), removing
 * one (another passkey, or a code and the PIN), the step-up challenge every sensitive request answers once the merchant
 * has a passkey, and the merchant's email choices. Inside the merchant session group.
 */
fun Route.securityController(security: SecurityService) {
    route("/security") {
        get("passkeys") {
            val result = security.passkeys(call.merchantId())
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<NoBody, PasskeysResponse>(
            "RM-P1 · Your passkeys",
            TAG,
            listOf("ramp.session_invalid"),
            notes =
                "$SESSION Oldest first, each with its label, when it was added and last used. `required`: Circaso " +
                    "requires a passkey for withdrawals, address and PIN changes and payment account changes; " +
                    "without one those are refused (`ramp.passkey_required`). `max`: how many you may keep.",
        )

        post("code") {
            val request = call.receive<SecurityCodeRequest>()
            val result =
                request.toPurpose().flatMap { security.code(call.merchantId(), it, call.clientAddress()) }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<SecurityCodeRequest, SignInCodeResponse>(
            "RM-P2 · Email a security code",
            TAG,
            listOf("ramp.code_locked", "ramp.email_unavailable", "request.malformed"),
            notes =
                "$SESSION `{ purpose }`: `ADD_PASSKEY` or `REMOVE_PASSKEY`. Emails a 6-digit code to your account's " +
                    "address, good for 10 minutes and for that one purpose; limits as for sign-in codes.",
        )

        post("passkeys/options") {
            val request = call.receive<PasskeyOptionsRequest>()
            val result =
                request.toParts().flatMap { (label, code, pin) ->
                    security.registrationOptions(call.merchantId(), label, code, pin)
                }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<PasskeyOptionsRequest, RegistrationChallengeResponse>(
            "RM-P3 · Start adding a passkey",
            TAG,
            listOf(
                "ramp.code_invalid",
                "ramp.code_locked",
                "ramp.pin_invalid",
                "ramp.pin_locked",
                "ramp.passkeys_full",
                "ramp.merchant_setup_incomplete",
                "request.malformed",
            ),
            notes =
                "$SESSION `{ label, code, pin }`: a name for it (1 to 40 characters), the code from RM-P2 " +
                    "(`ADD_PASSKEY`) and your PIN. Answers `publicKey` for `navigator.credentials.create` and a " +
                    "`registrationId` (5 minutes) to send back with the result to RM-P4.",
        )

        post("passkeys") {
            val request = call.receive<PasskeyRegistrationRequest>()
            val result =
                challengeIdOf(request.registrationId, "registrationId").flatMap {
                    security.register(call.merchantId(), it, request.credential.toJson())
                }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<PasskeyRegistrationRequest, PasskeyResponse>(
            "RM-P4 · Add the passkey",
            TAG,
            listOf("ramp.passkey_invalid", "request.malformed"),
            notes =
                "$SESSION `{ registrationId, credential }`: the credential is `PublicKeyCredential.toJSON()`. " +
                    "It must be made for `merchants.circaso.com` with user verification. You are emailed.",
        )

        post("passkeys/{passkeyId}/remove") {
            val request = call.receive<RemovePasskeyRequest>()
            val result =
                idOf(call.parameters["passkeyId"], "passkeyId").flatMap { id ->
                    request.toProof().flatMap { security.remove(call.merchantId(), id, it) }
                }
            call.respondDefault(result.toDefaultResponse { RemovedResponse(true) })
        }.docs<RemovePasskeyRequest, RemovedResponse>(
            "RM-P5 · Remove a passkey",
            TAG,
            listOf(
                "ramp.passkey_not_found",
                "ramp.step_up_required",
                "ramp.step_up_invalid",
                "ramp.code_invalid",
                "ramp.code_locked",
                "ramp.pin_invalid",
                "ramp.pin_locked",
                "request.malformed",
            ),
            notes =
                "$SESSION Either `{ passkey }`, a confirmation by **another** of your passkeys (RM-P6 with " +
                    "`REMOVE_PASSKEY` and this `passkeyId`), or `{ code, pin }` (RM-P2 with `REMOVE_PASSKEY`). " +
                    "You are emailed.",
        )

        post("step-up") {
            val request = call.receive<StepUpRequest>()
            val result =
                request.toBinding(call.merchantId(), call.merchantNetwork()).flatMap { security.stepUp(it) }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<StepUpRequest, AssertionChallengeResponse>(
            "RM-P6 · Confirm a request with your passkey",
            TAG,
            listOf("ramp.passkey_not_found", "request.malformed"),
            notes =
                "$SESSION Ask just before sending a sensitive request, with its `action` and the same fields it " +
                    "will carry. Answers `publicKey` for `navigator.credentials.get` and a `challengeId`; send both " +
                    "back in the request as `passkey: { challengeId, credential }`. The challenge covers the action " +
                    "and a hash of those fields, works once and lasts 5 minutes: a changed amount or address, " +
                    "another action, or a second use is `ramp.step_up_invalid`.",
        )
    }

    route("/notifications") {
        get {
            val result = security.notifications(call.merchantId())
            call.respondDefault(result.toDefaultResponse { it.toNotifications() })
        }.docs<NoBody, NotificationsResponse>(
            "RM-N1 · Your email choices",
            TAG,
            listOf("ramp.session_invalid"),
            notes =
                "$SESSION `orderEmails` (an email for each completed order and top-up; on to start with) and " +
                    "`dailySummary` (one email a day about yesterday). Security emails are always sent.",
        )

        put {
            val request = call.receive<NotificationsRequest>()
            val result =
                if (request.orderEmails == null && request.dailySummary == null) {
                    Resource.error(SharedError.Malformed("orderEmails", "send orderEmails, dailySummary or both"))
                } else {
                    security.setNotifications(call.merchantId(), request.toChoices())
                }
            call.respondDefault(result.toDefaultResponse { it.toNotifications() })
        }.docs<NotificationsRequest, NotificationsResponse>(
            "RM-N2 · Change your email choices",
            TAG,
            listOf("ramp.session_invalid", "request.malformed"),
            notes = "$SESSION `{ orderEmails?, dailySummary? }`; one left out stays as it is.",
        )
    }
}

/** MA4 and MA5: signing in with a passkey instead of an email code. Under `/auth`, no session. */
fun Route.passkeySignInRoutes(security: SecurityService) {
    post("passkey/options") {
        val result = security.signInOptions()
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, AssertionChallengeResponse>(
        "MA4 · Sign in with a passkey: options",
        TAG,
        emptyList(),
        notes =
            "$NO_SESSION `publicKey` for `navigator.credentials.get` (no `allowCredentials`: the browser offers your " +
                "passkeys for merchants.circaso.com) and a `challengeId` for MA5. Lasts 5 minutes, works once.",
    )

    post("passkey/verify") {
        val request = call.receive<PasskeySignInRequest>()
        val result =
            challengeIdOf(request.challengeId, "challengeId").flatMap { id ->
                security.signIn(request.toAssertion(id), call.merchantNetwork(), call.signInContext())
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<PasskeySignInRequest, SignedInResponse>(
        "MA5 · Sign in with a passkey",
        TAG,
        listOf("ramp.passkey_sign_in_failed", "request.malformed"),
        notes =
            "$NO_SESSION `{ challengeId, credential }`. Answers as MA2: a session token. You are emailed that you " +
                "signed in.",
    )
}

/** MA6: "This wasn't me". No session: the signed token from the email's link is the only thing it takes. */
fun Route.lockRoutes(security: SecurityService) {
    post("lock") {
        val request = call.receive<LockRequest>()
        val token = request.token.trim()
        val result =
            if (token.isEmpty() || token.length > MAX_TOKEN) {
                Resource.error(SharedError.Malformed("token", "required"))
            } else {
                security.lock(token)
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<LockRequest, LockResponse>(
        "MA6 · Lock withdrawals (\"This wasn't me\")",
        TAG,
        listOf("ramp.lock_link_invalid", "ramp.lock_link_expired", "request.malformed"),
        notes =
            "$NO_SESSION `{ token }` from a security email's link (`/lock#t=…`), which works once and for 72 hours. " +
                "Locks your withdrawals: withdrawals not yet being sent are cancelled and their amount freed, " +
                "addresses still cooling off are frozen, you are taken offline and signed out everywhere, and " +
                "Circaso is alerted. Only Circaso can unlock. A link used before answers `alreadyUsed: true` and " +
                "does nothing more.",
    )
}
