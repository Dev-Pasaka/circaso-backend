package com.circaso.calls.presentation.controllers

import com.circaso.calls.api.CallId
import com.circaso.calls.domain.errors.CallsError
import com.circaso.calls.domain.models.CallView
import com.circaso.calls.presentation.dto.CallReportRequest
import com.circaso.calls.presentation.dto.CallResponse
import com.circaso.calls.presentation.dto.ConnectedRequest
import com.circaso.calls.presentation.dto.EmptyResponse
import com.circaso.calls.presentation.dto.JoinRequest
import com.circaso.calls.presentation.dto.JoinResponse
import com.circaso.calls.presentation.dto.LeaveRequest
import com.circaso.calls.presentation.dto.MediaRequest
import com.circaso.calls.presentation.dto.PullRequest
import com.circaso.calls.presentation.dto.PullResponse
import com.circaso.calls.presentation.dto.RenegotiateRequest
import com.circaso.calls.presentation.dto.ReportReceivedResponse
import com.circaso.calls.presentation.dto.RingRequest
import com.circaso.calls.presentation.dto.StartCallRequest
import com.circaso.calls.presentation.mappers.MAX_LEAVE_BYTES
import com.circaso.calls.presentation.mappers.MAX_REPORT_BYTES
import com.circaso.calls.presentation.mappers.callIdOf
import com.circaso.calls.presentation.mappers.chatIdOf
import com.circaso.calls.presentation.mappers.leaveCauseOf
import com.circaso.calls.presentation.mappers.toCommand
import com.circaso.calls.presentation.mappers.toReport
import com.circaso.calls.presentation.mappers.toResponse
import com.circaso.calls.presentation.mappers.toUserIds
import com.circaso.calls.presentation.services.CallService
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.principal
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

private const val TAG = "Calls"
private const val SCOPE = "Scope: `app`."
private const val IDEMPOTENT = "Send an `Idempotency-Key`."
private const val MALFORMED = "request.malformed"
private const val NOT_FOUND = "calls.not_found"
private const val MEMBER = "calls.not_a_member"
private const val ENDED = "calls.call_ended"
private const val NOT_IN_CALL = "calls.not_in_call"
private const val SESSION = "calls.session_mismatch"
private const val MEDIA = "calls.media_unavailable"

/**
 * Voice and video calls in a chat, with the media on Cloudflare Realtime's SFU (decision 2026-10-29). CL1 start,
 * CL2 look up (also by chat), CL3 join with the phone's offer, CL4 pull others' tracks, CL5 answer the SFU's offer,
 * CL6 decline, CL7 leave, CL8 mute or camera off, CL10 a call's report. Frames `calls/call.ringing`, `call.updated` and `call.ended`.
 */
fun Route.callController(calls: CallService) {
    route("/calls") {
        authenticate(Scope.APP) {
            post {
                val userId = call.principal().userId
                val body = call.receive<StartCallRequest>()
                val result = chatIdOf(body.chatId).flatMap { calls.start(userId, it, body.video) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<StartCallRequest, CallResponse>(
                "CL1 · Call a chat",
                TAG,
                listOf(MEMBER, MALFORMED),
                notes =
                    "$SCOPE $IDEMPOTENT Everyone else in the chat is rung: `calls/call.ringing`, pushed data-only " +
                        "and high priority to a phone without a socket. The caller then joins with CL3. A chat " +
                        "with a call ringing or active answers that call (its `callId`, `startedBy` someone else " +
                        "perhaps) and rings nobody: join it with CL3. Two people calling each other at once end up " +
                        "in one call.",
            )

            route("/{callId}") {
                get {
                    val userId = call.principal().userId
                    val result = callIdOf(call.parameters["callId"]).flatMap { calls.get(userId, it) }
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<NoBody, CallResponse>(
                    "CL2 · A call",
                    TAG,
                    listOf(NOT_FOUND, MEMBER, MALFORMED),
                    notes = "$SCOPE The chat's members only.",
                )

                post("join") {
                    val userId = call.principal().userId
                    val result =
                        callIdOf(call.parameters["callId"]).flatMap { callId ->
                            call.receive<JoinRequest>().toCommand(userId, callId).flatMap { calls.join(it) }
                        }
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<JoinRequest, JoinResponse>(
                    "CL3 · Join a call with the phone's offer",
                    TAG,
                    listOf(NOT_FOUND, MEMBER, ENDED, "calls.call_full", MEDIA, MALFORMED),
                    notes =
                        "$SCOPE $IDEMPOTENT The server opens an SFU session for this connection, pushes the " +
                            "tracks in the offer and answers with the SFU's SDP. Keep `sessionId` for CL4 and CL5. " +
                            "The second person in makes the call `ACTIVE`; everyone gets `calls/call.updated`. " +
                            "At most 8 people at once. Joining again replaces your earlier session.",
                )

                post("connected") {
                    val userId = call.principal().userId
                    val result =
                        callIdOf(call.parameters["callId"]).flatMap { callId ->
                            call.receive<ConnectedRequest>().toCommand(userId, callId).flatMap { calls.connected(it) }
                        }
                    call.respondDefault(result.map { EmptyResponse }.toDefaultResponse { it })
                }.docs<ConnectedRequest, EmptyResponse>(
                    "CL3b · Say the phone's connection is up",
                    TAG,
                    listOf(NOT_FOUND, ENDED, NOT_IN_CALL, SESSION, MALFORMED),
                    notes =
                        "$SCOPE $IDEMPOTENT When the peer connection of this `sessionId` first turns `connected`. " +
                            "Everyone gets `calls/call.updated` with `connected: true` on your tile, and pulls you " +
                            "(CL4) then: the SFU holds a pull of tracks whose publisher isn't connected yet for " +
                            "about 9 seconds. Saying it twice changes nothing.",
                )

                post("pull") {
                    val userId = call.principal().userId
                    val result =
                        callIdOf(call.parameters["callId"]).flatMap { callId ->
                            call.receive<PullRequest>().toCommand(userId, callId).flatMap { calls.pull(it) }
                        }
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<PullRequest, PullResponse>(
                    "CL4 · Receive other people's tracks",
                    TAG,
                    listOf(NOT_FOUND, ENDED, NOT_IN_CALL, SESSION, MEDIA, MALFORMED),
                    notes =
                        "$SCOPE Name tracks by `userId` and `trackName` (from the call's participants); the " +
                            "server finds their sessions. A track not available is left out of `tracks`. When " +
                            "`requiresRenegotiation`, set `offer` as the remote description and send the answer " +
                            "with CL5. Pull a person once your own connection is up and their tile says " +
                            "`connected` (CL3b); before that the SFU holds the request for about 9 seconds.",
                )

                post("renegotiate") {
                    val userId = call.principal().userId
                    val result =
                        callIdOf(call.parameters["callId"]).flatMap { callId ->
                            call
                                .receive<RenegotiateRequest>()
                                .toCommand(userId, callId)
                                .flatMap { calls.renegotiate(it) }
                        }
                    call.respondDefault(result.map { EmptyResponse }.toDefaultResponse { it })
                }.docs<RenegotiateRequest, EmptyResponse>(
                    "CL5 · Answer the SFU's offer",
                    TAG,
                    listOf(NOT_FOUND, ENDED, NOT_IN_CALL, SESSION, MEDIA, MALFORMED),
                    notes = "$SCOPE The phone's answer to the `offer` of CL4.",
                )

                post("decline") { change(calls::decline) }.docs<NoBody, CallResponse>(
                    "CL6 · Decline a call",
                    TAG,
                    listOf(NOT_FOUND, ENDED, MALFORMED),
                    notes =
                        "$SCOPE $IDEMPOTENT A direct call ends (`DECLINED`, `calls/call.ended` to both); in a " +
                            "group only you are `DECLINED`. Declining what no longer rings you changes nothing.",
                )

                post("ring") {
                    val userId = call.principal().userId
                    val result =
                        callIdOf(call.parameters["callId"]).flatMap { callId ->
                            call.receive<RingRequest>().toUserIds().flatMap { calls.ring(userId, callId, it) }
                        }
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<RingRequest, CallResponse>(
                    "CL9 · Ring people again (add people)",
                    TAG,
                    listOf(NOT_FOUND, ENDED, NOT_IN_CALL, "calls.not_a_group", MALFORMED),
                    notes =
                        "$SCOPE Group calls only, by someone on the call. Members who missed, declined or left are " +
                            "rung again (`calls/call.ringing`, pushed when offline); `userIds` narrows it, empty is " +
                            "everyone not on the call. Each ring lasts 45 seconds.",
                )

                post("leave") {
                    val userId = call.principal().userId
                    val cause = leaveCauseOf(call.boundedBody(MAX_LEAVE_BYTES).leaveText())
                    val result = callIdOf(call.parameters["callId"]).flatMap { calls.leave(userId, it, cause) }
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<LeaveRequest, CallResponse>(
                    "CL7 · Leave a call",
                    TAG,
                    listOf(NOT_FOUND, MALFORMED),
                    notes =
                        "$SCOPE $IDEMPOTENT Answers at once; your tracks are closed at the SFU afterwards. The " +
                            "caller hanging up before anyone answered cancels the call (`CANCELLED`). A direct call " +
                            "ends for both as soon as either hangs up (`COMPLETED`, or `DECLINED` when the callee " +
                            "hangs up unanswered); a group call ends when fewer than two people are left. Everyone " +
                            "gets `calls/call.ended` with the final call. The body is optional (2026-11-13): " +
                            "`cause` says why the phone left and is kept for support; a leave with `STALL` or " +
                            "`RECONNECT_GAVE_UP` that ends the call ends it `DROPPED`. An unknown cause is kept as " +
                            "`OTHER`; no body is a leave without a cause.",
                )

                post("report") {
                    val userId = call.principal().userId
                    val body = call.boundedBody(MAX_REPORT_BYTES)
                    val result =
                        callIdOf(call.parameters["callId"]).flatMap { callId ->
                            body.reportRequest().flatMap { it.toReport(userId, callId) }.flatMap { calls.report(it) }
                        }
                    call.respondDefault(result.toDefaultResponse { ReportReceivedResponse() })
                }.docs<CallReportRequest, ReportReceivedResponse>(
                    "CL10 · Send a call's report",
                    TAG,
                    listOf(NOT_FOUND, "calls.report_invalid", "calls.report_rate_limited", MALFORMED),
                    notes =
                        "$SCOPE $IDEMPOTENT Once per call per phone, after the call ended, perhaps hours later " +
                            "(queued offline): this phone's timeline (at most 400 events), media samples (at most " +
                            "720) and totals, for support to see why a call dropped. A repeat replaces the earlier " +
                            "one. Only someone the call rang or who joined it; anyone else is `calls.not_found`. " +
                            "The body is at most 64 KiB. At most 30 reports an hour. Kept 30 days. Never put " +
                            "message content, phone numbers or names in it.",
                )

                post("media") {
                    val userId = call.principal().userId
                    val result =
                        callIdOf(call.parameters["callId"]).flatMap { callId ->
                            calls.media(call.receive<MediaRequest>().toCommand(userId, callId))
                        }
                    call.respondDefault(result.map { EmptyResponse }.toDefaultResponse { it })
                }.docs<MediaRequest, EmptyResponse>(
                    "CL8 · Microphone and camera on or off",
                    TAG,
                    listOf(NOT_FOUND, ENDED, NOT_IN_CALL, MALFORMED),
                    notes =
                        "$SCOPE $IDEMPOTENT Shown on everyone's tiles through `calls/call.updated`. The tracks " +
                            "stay published; the phone stops sending on them.",
                )
            }
        }
    }

    route("/chats/{chatId}") {
        authenticate(Scope.APP) {
            get("call") {
                val userId = call.principal().userId
                val result = chatIdOf(call.parameters["chatId"]).flatMap { calls.live(userId, it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, CallResponse>(
                "CL2 · The chat's call going on",
                TAG,
                listOf(NOT_FOUND, MEMBER, MALFORMED),
                notes = "$SCOPE The call ringing or active in the chat, so a group can show \"join call\"; else 404.",
            )
        }
    }
}

private suspend fun RoutingContext.change(action: suspend (UserId, CallId) -> Resource<CallView>) {
    val userId = call.principal().userId
    val result = callIdOf(call.parameters["callId"]).flatMap { action(userId, it) }
    call.respondDefault(result.toDefaultResponse { it.toResponse() })
}

/** The request body, read no further than [max] bytes (after any decompression) plus one: "" for none, null when over. */
private suspend fun ApplicationCall.boundedBody(max: Long): String? {
    val bytes = receiveChannel().readRemaining(max + 1).readByteArray()
    return if (bytes.size > max) null else bytes.decodeToString()
}

/** CL7's optional body: none, or over its few bytes, is a leave without a cause. */
private fun String?.leaveText(): String? = this?.takeIf { it.isNotEmpty() }

/** CL10's body as its request: none or over 64 KiB is `calls.report_invalid`, JSON that isn't one is malformed. */
private fun String?.reportRequest(): Resource<CallReportRequest> =
    when {
        this == null -> Resource.error(CallsError.ReportInvalid("body", "at most 64 KiB"))
        isEmpty() -> Resource.error(CallsError.ReportInvalid("body", "required"))
        else -> Resource.success(CircasoJson.decodeFromString(CallReportRequest.serializer(), this))
    }
