package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.GroupLinkToken
import com.circaso.messaging.api.MemberAdded
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.GroupLink
import com.circaso.messaging.domain.models.InviteState
import com.circaso.messaging.domain.models.LinkJoined
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.ports.LinkRateLimit
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.GroupLinkRepository
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
import com.circaso.users.api.Profiles
import com.circaso.users.api.ReachPolicy
import java.time.Clock

/**
 * M32 `POST /chats/links/{token}/join` (decision 2026-11-14): someone with a group's join link becomes a member
 * straight away; they chose to, so there is no invite to accept. It is the same membership an admin's add and an
 * accept make, by the same steps as `AcceptInvite`: in one transaction the chat's row is locked first, the link is
 * read again (a reset a moment earlier wins), a place is reserved with the conditional `member_count` update (0 rows
 * is `messaging.group_full`), and the `MEMBER` row and `MemberAdded` are written together. The admin who made the
 * link stands as the inviter (`byUserId`), so wallet, chama and calls see a member exactly as before, with no more
 * rights than any member has.
 *
 * Who can't come in by link (all answer `messaging.link_not_allowed`, which never says which):
 * - someone an admin removed from this group, until an admin invites them again (their open invite lets them in);
 * - anyone with a block, either way, between them and the link's maker or any of the group's admins: an admin's add
 *   needs the adder to reach the person, and a link stands for the admins, so it can't bypass a block;
 * - a support account, which is in no groups.
 *
 * Someone already in the group gets the group again and nothing is written, so a retry is safe. Twenty joins per
 * person per hour. After the commit every member gets `member.added` with `joined` and `viaLink`, so phones say the
 * person joined with the group link and send them their sender keys and the group's metadata key, as for any new
 * member; nobody is pushed. An open invite of theirs to the same group is answered by the join.
 */
class JoinByGroupLink(
    private val links: GroupLinkRepository,
    private val invites: InviteRepository,
    private val chats: ChatRepository,
    private val transactor: Transactor,
    private val outbox: MessagingOutbox,
    private val membership: Membership,
    private val views: ChatViews,
    private val notifier: ChatNotifier,
    private val invitations: Invitations,
    private val reach: ReachPolicy,
    private val profiles: Profiles,
    private val rateLimit: LinkRateLimit,
    private val bus: EventBus,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, token: GroupLinkToken): Resource<ChatView> {
        rateLimit.take(ACTION, caller, config.linkJoinsPerHour)?.let {
            return Resource.error(MessagingError.RateLimited(it))
        }
        val link = links.find(token)?.takeIf { it.isLive } ?: return Resource.error(MessagingError.LinkNotFound)
        val chat = chats.chat(link.chatId) ?: return Resource.error(MessagingError.LinkNotFound)
        if (chat.kind != ChatKind.GROUP) return Resource.error(MessagingError.LinkNotFound)
        if (chats.member(chat.chatId, caller) != null) {
            return Resource.success(views.view(chat, chats.lastMessageId(chat.chatId)))
        }
        if (!mayComeIn(caller, link)) return Resource.error(MessagingError.LinkNotAllowed)

        val joined =
            when (val written = transactor.attempt("links.join") { join(caller, token) }) {
                is Resource.Error -> return written
                is Resource.Success -> written.data
            }
        val chatId = joined.chat.chatId
        if (joined.event != null) {
            membership.changed(chatId)
            bus.publish(joined.event)
            notifier.membersAdded(
                recipients = membership.members(chatId),
                chatId = chatId,
                added = listOf(caller),
                by = joined.link.createdBy,
                joined = true,
                viaLink = true,
            )
        }
        joined.answeredInvite?.let {
            notifier.inviteRemoved(it)
            invitations.changed(chatId, it.invitedBy)
        }
        return Resource.success(views.view(joined.chat, chats.lastMessageId(chatId)))
    }

    /** Outside the transaction (users is asked): not a support account, and no block with the maker or an admin. */
    private suspend fun mayComeIn(caller: UserId, link: GroupLink): Boolean {
        if (profiles.get(setOf(caller))[caller]?.support == true) return false
        val standIns = membership.admins(link.chatId) + link.createdBy - caller
        return standIns.none { reach.blockedEitherWay(caller, it) }
    }

    /** The decision and the writes, under the chat's row lock. */
    private suspend fun join(caller: UserId, token: GroupLinkToken): Resource<LinkJoined> {
        val found = links.find(token) ?: return Resource.error(MessagingError.LinkNotFound)
        val chat = chats.lockChat(found.chatId) ?: return Resource.error(MessagingError.LinkNotFound)
        // Read again under the lock: a reset or turning it off (which lock the chat too) a moment earlier wins.
        val link = links.find(token)?.takeIf { it.isLive } ?: return Resource.error(MessagingError.LinkNotFound)
        if (chats.member(chat.chatId, caller) != null) {
            return Resource.success(LinkJoined(chat, link, event = null, answeredInvite = null))
        }
        val now = clock.instant()
        val invite = invites.openFor(chat.chatId, listOf(caller), now).firstOrNull()
        if (invite == null && links.wasRemoved(chat.chatId, caller)) {
            return Resource.error(MessagingError.LinkNotAllowed)
        }
        val reserved =
            chats.reserveSeats(chat.chatId, 1, config.maxGroupMembers)
                ?: return Resource.error(MessagingError.GroupFull)
        chats.addMembers(listOf(Member(chat.chatId, caller, Role.MEMBER, now)))
        if (invite != null) {
            invites.decide(invite.inviteId, InviteState.ACCEPTED, now)
            links.clearRemoval(chat.chatId, caller)
        }
        val event = bus.envelope(MemberAdded(chat.chatId, caller, link.createdBy))
        outbox.write(listOf(event))
        return Resource.success(LinkJoined(reserved, link, event, invite))
    }

    private companion object {
        const val ACTION = "join"
    }
}
