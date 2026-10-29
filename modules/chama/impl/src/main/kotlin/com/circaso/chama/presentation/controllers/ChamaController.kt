package com.circaso.chama.presentation.controllers

import com.circaso.chama.domain.models.Decision
import com.circaso.chama.presentation.dto.AttachPaymentRequest
import com.circaso.chama.presentation.dto.ChamaResponse
import com.circaso.chama.presentation.dto.CreateRequestRequest
import com.circaso.chama.presentation.dto.GoalDto
import com.circaso.chama.presentation.dto.LedgerResponse
import com.circaso.chama.presentation.dto.RequestDto
import com.circaso.chama.presentation.dto.SetGoalRequest
import com.circaso.chama.presentation.mappers.chatIdOf
import com.circaso.chama.presentation.mappers.requestIdOf
import com.circaso.chama.presentation.mappers.toCommand
import com.circaso.chama.presentation.mappers.toDto
import com.circaso.chama.presentation.mappers.toResponse
import com.circaso.chama.presentation.mappers.transferIdOf
import com.circaso.chama.presentation.services.ChamaService
import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.principal
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.Instant

private const val TAG = "Chama"
private const val SCOPE = "Scope: `app`."
private const val MALFORMED = "request.malformed"
private const val MEMBER = "chama.not_a_member"
private const val DEFAULT_LIMIT = 50

/**
 * A chama: a group chat's shared goal, held by its treasurer (decision 2026-10-29). CH1 the goal and totals, CH2 set
 * the goal, CH3 the ledger, CH4 ask the group to pay someone, CH5 approve or decline, CH6 link the treasurer's
 * payment. Contributions themselves are ordinary W4 transfers to the treasurer, paid from the chat.
 */
fun Route.chamaController(chamas: ChamaService) {
    route("/chama") {
        authenticate(Scope.APP) {
            route("/chats/{chatId}") {
                get {
                    val userId = call.principal().userId
                    val result = chatIdOf(call.parameters["chatId"]).flatMap { chamas.get(userId, it) }
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<NoBody, ChamaResponse>(
                    "CH1 · The group's goal, totals and open requests",
                    TAG,
                    listOf(MEMBER, "chama.no_goal", MALFORMED),
                    notes =
                        "$SCOPE Members only. `held` is what the treasurer still holds for the group: " +
                            "contributed minus paid out.",
                )

                put("goal") {
                    val userId = call.principal().userId
                    val result =
                        chatIdOf(call.parameters["chatId"]).flatMap { chatId ->
                            call.receive<SetGoalRequest>().toCommand(userId, chatId).flatMap { chamas.setGoal(it) }
                        }
                    call.respondDefault(result.toDefaultResponse { it.toDto() })
                }.docs<SetGoalRequest, GoalDto>(
                    "CH2 · Set or change the group's goal",
                    TAG,
                    listOf(MEMBER, "chama.not_admin", "chama.goal_invalid", "chama.treasurer_not_member", MALFORMED),
                    notes =
                        "$SCOPE Admins only. The treasurer's wallet holds the money; the caller unless " +
                            "`treasurerUserId` names another member. Every member gets `chama/goal.updated`.",
                )

                get("ledger") {
                    val userId = call.principal().userId
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
                    val before =
                        call.request.queryParameters["before"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    val result =
                        chatIdOf(call.parameters["chatId"]).flatMap { chamas.ledger(userId, it, limit, before) }
                    call.respondDefault(result.toDefaultResponse { it.toResponse(limit) })
                }.docs<NoBody, LedgerResponse>(
                    "CH3 · Contributions in and payments out, newest first",
                    TAG,
                    listOf(MEMBER, "chama.no_goal", MALFORMED),
                    notes = "$SCOPE `?limit=` (at most 200) and `?before=<the previous page's next>`.",
                )

                post("requests") {
                    val userId = call.principal().userId
                    val result =
                        chatIdOf(call.parameters["chatId"]).flatMap { chatId ->
                            call
                                .receive<CreateRequestRequest>()
                                .toCommand(userId, chatId)
                                .flatMap { chamas.request(it) }
                        }
                    call.respondDefault(result.toDefaultResponse { it.toDto() })
                }.docs<CreateRequestRequest, RequestDto>(
                    "CH4 · Ask the group to pay someone",
                    TAG,
                    listOf(MEMBER, "chama.no_goal", "chama.amount_invalid", "chama.payee_invalid", MALFORMED),
                    notes =
                        "$SCOPE Any member. Needs two admins' approval (every admin when there are fewer); " +
                            "an admin asking counts as their own. Open 48 hours. Every member gets " +
                            "`chama/request.updated`; the admins still to decide are pushed.",
                )
            }

            route("/requests/{requestId}") {
                post("approve") { decide(chamas, Decision.APPROVE) }.docs<NoBody, RequestDto>(
                    "CH5 · Approve a request",
                    TAG,
                    listOf(
                        "chama.request_not_found",
                        "chama.not_admin",
                        "chama.request_closed",
                        "chama.already_decided",
                        MALFORMED,
                    ),
                    notes =
                        "$SCOPE Admins only, once each. Enough approvals make it `APPROVED`: the treasurer can " +
                            "then pay it (W4 from the chat) and link the transfer (CH6).",
                )
                post("decline") { decide(chamas, Decision.DECLINE) }.docs<NoBody, RequestDto>(
                    "CH5 · Decline a request",
                    TAG,
                    listOf(
                        "chama.request_not_found",
                        "chama.not_admin",
                        "chama.request_closed",
                        "chama.already_decided",
                        MALFORMED,
                    ),
                    notes = "$SCOPE Admins only. One decline closes the request as `DECLINED`.",
                )
                post("payment") {
                    val userId = call.principal().userId
                    val result =
                        requestIdOf(call.parameters["requestId"]).flatMap { requestId ->
                            transferIdOf(call.receive<AttachPaymentRequest>().transferId)
                                .flatMap { chamas.attach(userId, requestId, it) }
                        }
                    call.respondDefault(result.toDefaultResponse { it.toDto() })
                }.docs<AttachPaymentRequest, RequestDto>(
                    "CH6 · Link the treasurer's payment to an approved request",
                    TAG,
                    listOf(
                        "chama.request_not_found",
                        "chama.not_treasurer",
                        "chama.not_approved",
                        "chama.transfer_mismatch",
                        "chama.request_closed",
                        MALFORMED,
                    ),
                    notes =
                        "$SCOPE The treasurer only. The transfer (W4, sent from the chat) must be theirs, " +
                            "to the request's payee, for its amount. `PAID` once it is confirmed on chain " +
                            "(now, or when the wallet says so).",
                )
            }
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.decide(chamas: ChamaService, decision: Decision) {
    val userId = call.principal().userId
    val result = requestIdOf(call.parameters["requestId"]).flatMap { chamas.decide(userId, it, decision) }
    call.respondDefault(result.toDefaultResponse { it.toDto() })
}
