package com.circaso.identity.presentation.controllers

import com.circaso.identity.presentation.dto.RecoveryRequest
import com.circaso.identity.presentation.dto.RecoveryResponse
import com.circaso.identity.presentation.mappers.parseUuid
import com.circaso.identity.presentation.mappers.toCommand
import com.circaso.identity.presentation.mappers.toPrincipal
import com.circaso.identity.presentation.mappers.toResponse
import com.circaso.identity.presentation.services.RecoveryService
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.SharedError
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.principal
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Account recovery"

/** Support-led recovery of a lost Google or Apple login. I15 and I17. */
fun Route.recoveryController(recovery: RecoveryService) {
    route("/recovery/requests") {
        authenticate(Scope.ONBOARDING) {
            post {
                val result =
                    call
                        .receive<RecoveryRequest>()
                        .toCommand(
                            call.principal(),
                        ).flatMap { recovery.request(it) }
                call.respondDefault(result.toDefaultResponse(HttpStatusCode.Created) { it.toResponse() })
            }.docs<RecoveryRequest, RecoveryResponse>(
                "I15 · Open a support case for a lost Google or Apple login",
                TAG,
                listOf("identity.recovery_not_eligible", "request.rate_limited"),
                notes = "Scope: `onboarding`.",
            )

            get("{requestId}") {
                val id = parseUuid(call.parameters["requestId"])
                val result =
                    if (id == null) {
                        Resource.error(SharedError.Malformed("requestId", "is not valid"))
                    } else {
                        recovery.get(call.principal().toPrincipal(), id)
                    }
                call.respondDefault(result.map { it.toResponse() }.toDefaultResponse { it })
            }.docs<NoBody, RecoveryResponse>(
                "I17 · Status of your own support case",
                TAG,
                notes = "Scope: `onboarding`.",
            )
        }
    }
}
