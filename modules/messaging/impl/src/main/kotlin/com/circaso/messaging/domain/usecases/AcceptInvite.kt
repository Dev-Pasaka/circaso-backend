package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.MemberAdded
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.InviteAccepted
import com.circaso.messaging.domain.models.InviteState
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.InviteRepository
import com.circaso.messaging.domain.repositories.MessagingOutbox
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.Invitations
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import java.time.Clock
import java.util.UUID

/**
 * M24 `POST /chats/invites/{inviteId}/accept` (decision 2026-11-09): the person invited joins the group through the
 * same path an add used to take. In one transaction the chat's row is locked first (the lock order every membership
 * change keeps), then the invite; a place is reserved with the conditional `member_count` update (0 rows is
 * `messaging.group_full`, and the invite stays open), and the member row, the invite's answer and `MemberAdded` (by
 * the inviter, so wallet, chama and calls see a member as before) are written together.
 *
 * After the commit the members get `member.added` with `joined`, so phones show "X joined" and send the newcomer
 * their sender keys and the group's metadata key, exactly as for an add. A retry of an accept that went through
 * answers the group again; anything else that isn't an open invite of the caller's is `messaging.invite_not_found`.
 */
class AcceptInvite(
    private val invites: InviteRepository,
    private val chats: ChatRepository,
    private val transactor: Transactor,
    private val outbox: MessagingOutbox,
    private val membership: Membership,
    private val views: ChatViews,
    private val notifier: ChatNotifier,
    private val invitations: Invitations,
    private val bus: EventBus,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, inviteId: UUID): Resource<ChatView> {
        val found =
            invites.invite(inviteId)?.takeIf { it.userId == caller }
                ?: return Resource.error(MessagingError.InviteNotFound)
        if (found.state == InviteState.ACCEPTED) {
            val chat = chats.chat(found.chatId)
            if (chat == null || chats.member(chat.chatId, caller) == null) {
                return Resource.error(MessagingError.InviteNotFound)
            }
            return Resource.success(views.view(chat, chats.lastMessageId(chat.chatId)))
        }

        val accepted =
            transactor.attempt("invites.accept") {
                val chat = chats.lockChat(found.chatId) ?: return@attempt Resource.error(MessagingError.InviteNotFound)
                val now = clock.instant()
                val invite = invites.lockInvite(inviteId)
                val isMember = chats.member(chat.chatId, caller) != null
                if (invite?.state == InviteState.ACCEPTED && isMember) {
                    // A retry that raced the first accept to the lock: the first one joined them.
                    return@attempt Resource.success(InviteAccepted(chat, invite, event = null))
                }
                if (invite == null ||
                    !invite.isOpenAt(now)
                ) {
                    return@attempt Resource.error(MessagingError.InviteNotFound)
                }
                if (isMember) {
                    invites.decide(inviteId, InviteState.ACCEPTED, now)
                    return@attempt Resource.success(InviteAccepted(chat, invite, event = null))
                }
                val reserved =
                    chats.reserveSeats(chat.chatId, 1, config.maxGroupMembers)
                        ?: return@attempt Resource.error(MessagingError.GroupFull)
                invites.decide(inviteId, InviteState.ACCEPTED, now)
                chats.addMembers(listOf(Member(chat.chatId, caller, Role.MEMBER, now)))
                val event = bus.envelope(MemberAdded(chat.chatId, caller, invite.invitedBy))
                outbox.write(listOf(event))
                Resource.success(InviteAccepted(reserved, invite, event))
            }
        val joined =
            when (accepted) {
                is Resource.Error -> return accepted
                is Resource.Success -> accepted.data
            }

        val chatId = joined.chat.chatId
        if (joined.event != null) {
            membership.changed(chatId)
            bus.publish(joined.event)
            notifier.membersAdded(
                recipients = membership.members(chatId),
                chatId = chatId,
                added = listOf(caller),
                by = joined.invite.invitedBy,
                joined = true,
            )
        }
        invitations.changed(chatId, joined.invite.invitedBy)
        return Resource.success(views.view(joined.chat, chats.lastMessageId(chatId)))
    }
}
