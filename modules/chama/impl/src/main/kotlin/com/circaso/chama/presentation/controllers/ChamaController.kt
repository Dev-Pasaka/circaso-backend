package com.circaso.chama.presentation.controllers

import com.circaso.chama.domain.models.Decision
import com.circaso.chama.presentation.dto.AttachPaymentRequest
import com.circaso.chama.presentation.dto.ChamaResponse
import com.circaso.chama.presentation.dto.CreateRequestRequest
import com.circaso.chama.presentation.dto.GoalDto
import com.circaso.chama.presentation.dto.GroupMoneyResponse
import com.circaso.chama.presentation.dto.GroupSettingsDto
import com.circaso.chama.presentation.dto.GroupSettingsRequest
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
import io.ktor.server.routing.delete
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
 * A group's money (decision 2026-11-03, Spaces phase 2: chamas and groups are one thing). The money sits in the
 * group's own wallet (wallet W14); a target is optional. CH1 the target and totals, CH2 set the target, CH3 the
 * ledger, CH4 ask the group to pay someone, CH5 approve or decline, CH6 link the payment (optional now), CH7 the
 * group's money target or not, CH8 remove the target, CH9 who approves payouts. Contributions are W4 payments with
 * `to: { groupId }`; payouts are W4 from the group wallet with `payoutRequestId`, by its signer.
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
                        "$SCOPE Members only. `contributed` counts towards the target since it was set; `held` is " +
                            "what the ledger says the group wallet holds (every contribution minus every payout; W1 " +
                            "with `?walletId=` has the real balance). `isTreasurer` means you send from the group " +
                            "wallet. A group without a target is `chama.no_goal` here: read CH7.",
                )

                get("money") {
                    val userId = call.principal().userId
                    val result = chatIdOf(call.parameters["chatId"]).flatMap { chamas.money(userId, it) }
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<NoBody, GroupMoneyResponse>(
                    "CH7 · The group's money: wallet, who approves, target and open requests",
                    TAG,
                    listOf(MEMBER, MALFORMED),
                    notes =
                        "$SCOPE Members only, for any group (decision 2026-11-03). `wallet` is null until the " +
                            "owner sets one up (W14): show no money actions then. `goal` is null without a target. " +
                            "Reload on `chama/group.updated`, `goal.updated`, `contribution.added` and " +
                            "`request.updated`.",
                )

                put("settings") {
                    val userId = call.principal().userId
                    val result =
                        chatIdOf(call.parameters["chatId"]).flatMap { chatId ->
                            call.receive<GroupSettingsRequest>().toCommand(userId, chatId).flatMap {
                                chamas.setSettings(it)
                            }
                        }
                    call.respondDefault(result.toDefaultResponse { it.toDto() })
                }.docs<GroupSettingsRequest, GroupSettingsDto>(
                    "CH9 · Choose who approves the group's payouts",
                    TAG,
                    listOf(MEMBER, "chama.not_owner", MALFORMED),
                    notes =
                        "$SCOPE The group's owner only. `TWO_ADMINS` (every admin when there are fewer; the " +
                            "default) or `ANY_ADMIN`. Set it right after W14 with what the owner chose in New Group. " +
                            "Requests already made keep theirs. Every member gets `chama/group.updated`.",
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
                    listOf(MEMBER, "chama.not_admin", "chama.goal_invalid", "chama.no_wallet", MALFORMED),
                    notes =
                        "$SCOPE Admins only. A target needs the group's wallet, active (`chama.no_wallet`: set it " +
                            "up first, GW7); its money goes there. `treasurerUserId` is ignored. Contributions count " +
                            "from when the target is first set. Every member gets `chama/goal.updated`.",
                )

                delete("goal") {
                    val userId = call.principal().userId
                    val result = chatIdOf(call.parameters["chatId"]).flatMap { chamas.removeGoal(userId, it) }
                    call.respondDefault(result.toDefaultResponse { NoBody })
                }.docs<NoBody, NoBody>(
                    "CH8 · Remove the group's target",
                    TAG,
                    listOf(MEMBER, "chama.not_admin", "chama.no_goal", MALFORMED),
                    notes =
                        "$SCOPE Admins only. The wallet, its money, the ledger and open requests stay; a new " +
                            "target counts from when it is set. Every member gets `chama/group.updated`.",
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
                    listOf(MEMBER, MALFORMED),
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
                    listOf(MEMBER, "chama.no_wallet", "chama.amount_invalid", "chama.payee_invalid", MALFORMED),
                    notes =
                        "$SCOPE Any member, in a group with an active wallet (target or not). Needs the approvals " +
                            "the group chose (CH9: two admins, every admin when there are fewer, or any admin); an " +
                            "admin asking counts as their own. Open 48 hours. Every member gets " +
                            "`chama/request.updated`; the admins still to decide are pushed, and the wallet's signer " +
                            "once it is approved.",
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
                        "$SCOPE Admins only, once each. Enough approvals make it `APPROVED`: the group wallet's " +
                            "signer can then pay it (W4 with `walletId` and `payoutRequestId`).",
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
                    "CH6 · Link the group wallet's payment to an approved request (optional)",
                    TAG,
                    listOf(
                        "chama.request_not_found",
                        "chama.no_wallet",
                        "chama.not_signer",
                        "chama.not_approved",
                        "chama.transfer_mismatch",
                        "chama.request_closed",
                        MALFORMED,
                    ),
                    notes =
                        "$SCOPE The group wallet's signer only. Optional: a W4 with `payoutRequestId` is tied " +
                            "to the request already, and the request is `PAID` when the wallet confirms it. Linking " +
                            "early shows the others it is being paid. The transfer must leave the group's wallet, " +
                            "to the request's payee, for its amount. `PAID` once confirmed on chain.",
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
