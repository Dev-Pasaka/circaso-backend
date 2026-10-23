package com.circaso.identity.presentation.controllers

import com.circaso.identity.presentation.dto.CarrierRequest
import com.circaso.identity.presentation.dto.PhoneVerifiedResponse
import com.circaso.identity.presentation.dto.ReleaseRequest
import com.circaso.identity.presentation.dto.ReleaseResponse
import com.circaso.identity.presentation.dto.StartPhoneRequest
import com.circaso.identity.presentation.dto.StartPhoneResponse
import com.circaso.identity.presentation.dto.VerifyCodeRequest
import com.circaso.identity.presentation.mappers.toCommand
import com.circaso.identity.presentation.mappers.toResponse
import com.circaso.identity.presentation.services.PhoneService
import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.web.principal
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.post

private const val TAG = "Phone verification"

/** Phone verification, by SMS code or carrier token, and number release. I3, I4, I5 and I16. */
fun Route.phoneController(phones: PhoneService) {
    authenticate(Scope.ONBOARDING) {
        route("/auth/phone") {
            post("start") {
                val result = call.receive<StartPhoneRequest>().toCommand(call.principal()).flatMap { phones.start(it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<StartPhoneRequest, StartPhoneResponse>(
                "I3 · Send a 6-digit SMS code", TAG,
                listOf("identity.phone_mismatch", "identity.code_locked", "identity.sms_unavailable", "request.rate_limited"),
                notes = "Scope: `onboarding`. 3 codes per number per 10 minutes, 10 per phone per day.",
            )

            post("verify") {
                val result = call.receive<VerifyCodeRequest>().toCommand(call.principal()).flatMap { phones.verify(it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<VerifyCodeRequest, PhoneVerifiedResponse>(
                "I4 · Check the SMS code; claims the number on sign-up", TAG,
                listOf("identity.code_invalid", "identity.code_expired", "identity.code_locked", "identity.phone_taken", "identity.phone_mismatch"),
                notes = "Scope: `onboarding`. Five wrong codes lock the account for 10 minutes.",
            )

            post("carrier") {
                val result = call.receive<CarrierRequest>().toCommand(call.principal()).flatMap { phones.carrier(it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<CarrierRequest, PhoneVerifiedResponse>(
                "I5 · Same as I4 from a carrier token, with no SMS", TAG,
                listOf("identity.carrier_unsupported", "identity.phone_taken", "identity.phone_mismatch"),
                notes = "Scope: `onboarding`.",
            )

            post("release") {
                val result = phones.release(call.receive<ReleaseRequest>().toCommand(call.principal()))
                call.respondDefault(result.toDefaultResponse { ReleaseResponse(it) })
            }.docs<ReleaseRequest, ReleaseResponse>(
                "I16 · Free a number held by a lost, unverified account after a 7-day notice", TAG,
                listOf("identity.release_not_allowed", "identity.code_expired"),
                notes = "Scope: `onboarding`. Needs a verification that proved the SIM (I3 and I4).",
            )
        }
    }
}
