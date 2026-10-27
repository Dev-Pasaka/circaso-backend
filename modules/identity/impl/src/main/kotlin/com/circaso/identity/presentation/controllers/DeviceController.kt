package com.circaso.identity.presentation.controllers

import com.circaso.identity.presentation.dto.PushTokenRequest
import com.circaso.identity.presentation.dto.RegisterDeviceRequest
import com.circaso.identity.presentation.dto.RegisterDeviceResponse
import com.circaso.identity.presentation.mappers.toCommand
import com.circaso.identity.presentation.mappers.toPrincipal
import com.circaso.identity.presentation.mappers.toResponse
import com.circaso.identity.presentation.services.DeviceService
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
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

private const val REGISTER = "Register device"
private const val TAG = "Account and security"

/** This phone: register its key, store its push token, forget it. I6, I9 and I10. */
fun Route.deviceController(devices: DeviceService) {
    route("/devices") {
        authenticate(Scope.ONBOARDING) {
            post {
                val result =
                    call
                        .receive<RegisterDeviceRequest>()
                        .toCommand(call.principal(), call.request.header("X-Client-Version"))
                        .flatMap { devices.register(it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<RegisterDeviceRequest, RegisterDeviceResponse>(
                "I6 · Register this phone's key; activates the account on sign-up and issues the app session",
                REGISTER,
                listOf("identity.device_attestation_failed", "request.malformed", "request.in_progress"),
                notes =
                    "Scope: `onboarding`. Send `X-Client-Version` (`android/1.0.0`): it carries the platform and " +
                        "app version. `request.in_progress` (409) means a second registration raced the first: keep " +
                        "the other response; don't retry this one.",
            )
        }
        authenticate(Scope.APP) {
            route("/current") {
                put("push-token") {
                    val token = call.receive<PushTokenRequest>().pushToken
                    val result: Resource<Unit> =
                        if (token.isBlank()) {
                            Resource.error(SharedError.Malformed("pushToken", "is required"))
                        } else {
                            devices.updatePushToken(call.principal().toPrincipal(), token)
                        }
                    call.respondDefault(result.toDefaultResponse { it })
                }.docs<PushTokenRequest, NoBody>(
                    "I9 · Store this phone's FCM or APNs token",
                    TAG,
                    notes = "Scope: `app`.",
                )

                delete {
                    call.respondDefault(devices.remove(call.principal().toPrincipal()).toDefaultResponse { it })
                }.docs<NoBody, NoBody>(
                    "I10 · Forget this phone; the next sign-in verifies the number again",
                    TAG,
                    notes = "Scope: `app`.",
                )
            }
        }
    }
}
