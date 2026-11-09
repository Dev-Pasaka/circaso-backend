package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.InviteState
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.InviteRepository
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.Invitations
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * M27 `DELETE /chats/{chatId}/invites/{userId}` (decision 2026-11-09): an admin, or whoever sent it, takes back an
 * open invite. The person invited gets `invite.removed` and it leaves their inbox; the admins and inviter get
 * `invites.changed`. Someone with no open invite is `messaging.invite_not_found`; a member who neither sent it nor is
 * an admin is `messaging.not_admin`.
 */
class CancelInvite(
    private val invites: InviteRepository,
    private val chats: ChatRepository,
    private val notifier: ChatNotifier,
    private val invitations: Invitations,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: UserId,
        chatId: ChatId,
        userId: UserId,
    ): Resource<Unit> {
        val member = chats.member(chatId, caller) ?: return Resource.error(MessagingError.ChatNotFound)
        val chat = chats.chat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        val now = clock.instant()
        val invite =
            invites.openFor(chatId, listOf(userId), now).firstOrNull()
                ?: return Resource.error(MessagingError.InviteNotFound)
        if (!chat.roleOf(member).isAdmin && invite.invitedBy != caller) return Resource.error(MessagingError.NotAdmin)
        if (!invites.decide(invite.inviteId, InviteState.CANCELLED, now)) {
            return Resource.error(MessagingError.InviteNotFound)
        }
        notifier.inviteRemoved(invite)
        invitations.changed(chatId, invite.invitedBy)
        return Resource.success(Unit)
    }
}
