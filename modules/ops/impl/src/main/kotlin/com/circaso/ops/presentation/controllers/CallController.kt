package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.presentation.dto.CallDetailResponse
import com.circaso.ops.presentation.dto.CallListResponse
import com.circaso.ops.presentation.mappers.FORBIDDEN
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.PAGED
import com.circaso.ops.presentation.mappers.TAG_CALLS
import com.circaso.ops.presentation.mappers.pageLimit
import com.circaso.ops.presentation.mappers.parseCallId
import com.circaso.ops.presentation.mappers.parseUserId
import com.circaso.ops.presentation.mappers.query
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toDto
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.services.CallService
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Calls for SUPPORT and ADMIN (2026-11-13, `calls.read`): a person's calls, and one call with what each phone said when
 * it left and the report it sent after, to see why a call dropped.
 */
fun Route.callController(calls: CallService, settings: OpsSettings) {
    route("/ops/users/{userId}") {
        staffOnly(Permission.CALLS_READ.roles) {
            get("calls") {
                val result =
                    parseUserId(call.parameters["userId"]).flatMap { userId ->
                        call.pageLimit(settings).flatMap { calls.ofUser(userId, call.query("cursor"), it) }
                    }
                call.respondDefault(
                    result.toDefaultResponse { page ->
                        CallListResponse(page.items.map { it.toDto() }, page.nextCursor)
                    },
                )
            }.docs<NoBody, CallListResponse>(
                "OPS · A person's calls",
                TAG_CALLS,
                listOf(FORBIDDEN, MALFORMED),
                notes =
                    "${roles("SUPPORT", "ADMIN")} Calls the person was rung for or joined, newest first. `endReason` " +
                        "`DROPPED`: nobody hung up, the line dropped. `reports`: how many phones sent a report. $PAGED",
            )
        }
    }
    route("/ops/calls") {
        staffOnly(Permission.CALLS_READ.roles) {
            get("{callId}") {
                val result = parseCallId(call.parameters["callId"]).flatMap { calls.get(it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, CallDetailResponse>(
                "OPS · A call and its reports",
                TAG_CALLS,
                listOf("ops.call_not_found", FORBIDDEN, MALFORMED),
                notes =
                    "${roles("SUPPORT", "ADMIN")} Everyone the call rang or who joined, with `leftCause` (what " +
                        "their phone said when it left) and `report` (the timeline, media samples and totals their " +
                        "phone sent after the call; null when none came or it is over 30 days old).",
            )
        }
    }
}
