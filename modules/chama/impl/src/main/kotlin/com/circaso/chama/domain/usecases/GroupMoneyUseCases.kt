package com.circaso.chama.domain.usecases

import com.circaso.chama.api.RequestId
import com.circaso.chama.domain.errors.ChamaError
import com.circaso.chama.domain.models.GroupMoney
import com.circaso.chama.domain.models.GroupSettings
import com.circaso.chama.domain.models.RequestState
import com.circaso.chama.domain.models.SetSettingsCommand
import com.circaso.chama.domain.ports.ChamaFrame
import com.circaso.chama.domain.ports.FrameSender
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.chama.domain.repositories.SettingsRepository
import com.circaso.chama.domain.support.ChamaViews
import com.circaso.chama.domain.support.Members
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.ApprovedPayout
import java.time.Clock
import java.util.UUID

/**
 * CH7 `GET /chama/chats/{chatId}/money` (decision 2026-11-03): the group's money as one member sees it, target or not:
 * its wallet, who approves payouts, the target and what was put towards it, and the open requests. Members only.
 */
class GetGroupMoney(private val views: ChamaViews) {
    suspend operator fun invoke(userId: UserId, chatId: ChatId): Resource<GroupMoney> = views.money(userId, chatId)
}

/**
 * CH8 `DELETE /chama/chats/{chatId}/goal`: an admin removes the group's target (decision 2026-11-03: a target can be
 * added or removed later). The wallet, its money, the ledger and the open requests stay; a new target counts from
 * when it is set. Every member hears `group.updated`. Removing a target that isn't there is `chama.no_goal`.
 */
class RemoveGoal(private val members: Members, private val goals: GoalRepository, private val frames: FrameSender) {
    suspend operator fun invoke(userId: UserId, chatId: ChatId): Resource<Unit> {
        when (val r = members.requireAdmin(chatId, userId)) {
            is Resource.Error -> return r
            is Resource.Success -> Unit
        }
        if (!goals.delete(chatId)) return Resource.error(ChamaError.NoGoal)
        frames.send(members.members(chatId), ChamaFrame.GroupUpdated(chatId, userId))
        return Resource.success(Unit)
    }
}

/**
 * CH9 `PUT /chama/chats/{chatId}/settings`: the group's owner chooses who approves payouts (GW1 "Who approves
 * payouts": two admins, or any admin). Requests already made keep the approvals they were made with. Every member
 * hears `group.updated`, so nobody's approval rule changes unseen.
 */
class SetSettings(
    private val members: Members,
    private val settings: SettingsRepository,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: SetSettingsCommand): Resource<GroupSettings> {
        val everyone =
            when (val r = members.requireMember(command.chatId, command.userId)) {
                is Resource.Error -> return r
                is Resource.Success -> r.data
            }
        if (members.owner(command.chatId) != command.userId) return Resource.error(ChamaError.NotOwner)
        val saved = GroupSettings(command.chatId, command.approvalPolicy, command.userId, clock.instant())
        settings.save(saved)
        frames.send(everyone, ChamaFrame.GroupUpdated(command.chatId, command.userId))
        return Resource.success(saved)
    }
}

/**
 * wallet:api's `PayoutApprovals`, which the wallet asks before it prepares a payment from a group wallet (option A):
 * the request when it is `APPROVED` in that group, with what the admins approved. A paid, declined or expired request
 * answers nothing, so it can't be paid (again).
 */
class FindApprovedPayout(private val requests: RequestRepository) {
    suspend operator fun invoke(chatId: ChatId, requestId: UUID): ApprovedPayout? {
        val request = requests.find(RequestId(requestId)) ?: return null
        if (request.chatId != chatId || request.state != RequestState.APPROVED) return null
        return ApprovedPayout(
            request.requestId.value,
            chatId,
            request.payeeId,
            request.payeeAddress,
            request.amountMinor,
        )
    }
}
