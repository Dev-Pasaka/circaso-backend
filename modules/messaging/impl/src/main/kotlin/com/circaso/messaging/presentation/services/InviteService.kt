package com.circaso.messaging.presentation.services

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.InviteView
import com.circaso.messaging.domain.models.PendingInviteView
import com.circaso.messaging.domain.usecases.AcceptInvite
import com.circaso.messaging.domain.usecases.CancelInvite
import com.circaso.messaging.domain.usecases.DeclineInvite
import com.circaso.messaging.domain.usecases.ListChatInvites
import com.circaso.messaging.domain.usecases.ListMyInvites
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.util.UUID

/** Group invites, M23 to M27 (decision 2026-11-09). */
class InviteService(
    private val listMyInvites: ListMyInvites,
    private val acceptInvite: AcceptInvite,
    private val declineInvite: DeclineInvite,
    private val listChatInvites: ListChatInvites,
    private val cancelInvite: CancelInvite,
) {
    suspend fun mine(caller: UserId): Resource<List<InviteView>> = listMyInvites(caller)

    suspend fun accept(caller: UserId, inviteId: UUID): Resource<ChatView> = acceptInvite(caller, inviteId)

    suspend fun decline(caller: UserId, inviteId: UUID): Resource<Unit> = declineInvite(caller, inviteId)

    suspend fun ofChat(caller: UserId, chatId: ChatId): Resource<List<PendingInviteView>> =
        listChatInvites(caller, chatId)

    suspend fun cancel(
        caller: UserId,
        chatId: ChatId,
        userId: UserId,
    ): Resource<Unit> = cancelInvite(caller, chatId, userId)
}
