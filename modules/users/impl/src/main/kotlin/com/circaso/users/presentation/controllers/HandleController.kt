package com.circaso.users.presentation.controllers

import com.circaso.shared.Scope
import com.circaso.users.presentation.dto.ClaimHandleRequest
import com.circaso.users.presentation.dto.HandleAvailabilityResponse
import com.circaso.users.presentation.dto.MyProfileResponse
import com.circaso.users.presentation.dto.PublicProfileDto
import com.circaso.users.presentation.mappers.toDto
import com.circaso.users.presentation.mappers.toResponse
import com.circaso.users.presentation.services.HandleService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

private const val TAG = "Handles"
private const val SCOPE = "Scope: `app`."

/** @handles: availability, claiming and exact-match lookup. U5, U6 and U9. */
fun Route.handleController(handles: HandleService) {
    authenticate(Scope.APP) {
        route("/handles") {
            get("{handle}/availability") {
                call.respondDefault(
                    handles
                        .check(
                            call.userId(),
                            call.parameters["handle"].orEmpty(),
                        ).toDefaultResponse {
                            it.toResponse()
                        },
                )
            }.docs<NoBody, HandleAvailabilityResponse>(
                "U5 · Whether a @handle is free, taken or invalid, with a suggestion",
                TAG,
                listOf("request.rate_limited"),
                notes = "$SCOPE 30 handle lookups per user per hour, shared with U9 and connecting by handle.",
            )
        }

        route("/me") {
            put("handle") {
                call.respondDefault(
                    handles
                        .claim(
                            call.userId(),
                            call.receive<ClaimHandleRequest>().handle,
                        ).toDefaultResponse {
                            it.toResponse()
                        },
                )
            }.docs<ClaimHandleRequest, MyProfileResponse>(
                "U6 · Claim or change your @handle whenever the new one is free",
                TAG,
                listOf("users.handle_invalid", "users.handle_taken", "users.handle_cooldown"),
                notes = SCOPE,
            )
        }

        route("/users") {
            get("by-handle/{handle}") {
                call.respondDefault(
                    handles
                        .getByHandle(
                            call.userId(),
                            call.parameters["handle"].orEmpty(),
                        ).toDefaultResponse {
                            it.toDto()
                        },
                )
            }.docs<NoBody, PublicProfileDto>(
                "U9 · Exact-match @handle lookup",
                TAG,
                listOf("users.handle_not_found", "request.rate_limited"),
                notes = "$SCOPE 30 lookups per user per hour.",
            )
        }
    }
}
