package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.InviteKey
import com.circaso.messaging.domain.models.InvitesSent
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.Invitations
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.ReachPolicy

/**
 * M9 `POST /chats/{chatId}/members`: admins invite people, or any member when the group allows it (decision
 * 2026-11-09: adding someone sends them an invite, and they join when they accept it). Each person must be reachable
 * by whoever invites them; the rest are listed in `notAdded`, with no reason. People already in the group are
 * skipped, and anyone whose invite is still open keeps it; both those and the new invites are listed in `invited`.
 *
 * Reach is asked of users first, outside the transaction. Then, in one transaction, the chat's row is locked before
 * anything else (the lock order every membership change keeps), the adder's right and who is already in are read
 * again, and the invites are written. The 1,000 cap is checked here against the members and the people asked for,
 * and again, with a seat reserved, when each invite is accepted.
 */
class AddMembers(
    private val chats: ChatRepository,
    private val transactor: Transactor,
    private val reach: ReachPolicy,
    private val membership: Membership,
    private val views: ChatViews,
    private val invitations: Invitations,
    private val config: MessagingConfig,
) {
    suspend operator fun invoke(
        caller: UserId,
        chatId: ChatId,
        memberIds: Set<UserId>,
        inviteKeys: List<InviteKey> = emptyList(),
    ): Resource<ChatView> {
        val adder = chats.member(chatId, caller) ?: return Resource.error(MessagingError.ChatNotFound)
        val chat = chats.chat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        if (!mayAdd(chat, adder)) return Resource.error(MessagingError.NotAdmin)
        val keys =
            when (val checked = invitations.keysOf(inviteKeys)) {
                is Resource.Error -> return checked
                is Resource.Success -> checked.data
            }
        val asked = memberIds - membership.members(chatId)
        if (chat.memberCount + asked.size > config.maxGroupMembers) return Resource.error(MessagingError.GroupFull)
        val reachable = reach.reachable(caller, asked)
        val notAdded = (asked - reachable).sortedBy { it.toString() }
        if (reachable.isEmpty()) return Resource.success(viewOf(chat, notAdded, emptyList()))

        val sent =
            when (val written = invite(caller, chatId, reachable, keys)) {
                is Resource.Error -> return written
                is Resource.Success -> written.data
            }
        invitations.announce(sent, caller)
        val invited = (sent.invites.map { it.userId } + sent.alreadyInvited).sortedBy { it.toString() }
        return Resource.success(viewOf(sent.chat, notAdded, invited))
    }

    /** The decision and the writes, under the chat's row lock. */
    private suspend fun invite(
        caller: UserId,
        chatId: ChatId,
        reachable: Set<UserId>,
        keys: Map<UserId, String>,
    ): Resource<InvitesSent> =
        transactor.attempt("chats.add-members") {
            val latest = chats.lockChat(chatId) ?: return@attempt Resource.error(MessagingError.ChatNotFound)
            val adder = chats.member(chatId, caller) ?: return@attempt Resource.error(MessagingError.ChatNotFound)
            if (!mayAdd(latest, adder)) return@attempt Resource.error(MessagingError.NotAdmin)
            val present = chats.members(chatId).map { it.userId }.toSet()
            Resource.success(invitations.write(latest, caller, reachable - present, keys))
        }

    private fun mayAdd(chat: Chat, adder: Member) =
        chat.kind == ChatKind.GROUP && (chat.roleOf(adder).isAdmin || chat.settings.membersCanAdd)

    private suspend fun viewOf(
        chat: Chat,
        notAdded: List<UserId>,
        invited: List<UserId>,
    ): ChatView = views.view(chat, chats.lastMessageId(chat.chatId), notAdded = notAdded, invited = invited)
}
