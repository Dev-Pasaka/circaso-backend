package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MemberAdded
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.repositories.ChatChanges
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.Join
import com.circaso.messaging.domain.repositories.MessagingStore
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.ChatWriter
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.shared.getOrNull
import com.circaso.shared.map
import com.circaso.users.api.ReachPolicy
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * M9 `POST /chats/{chatId}/members`: admins add people, or any member when the group allows it. Each person must be
 * reachable by whoever adds them; the rest are listed in `notAdded`, with no reason. People already in the group
 * are skipped. Inside the version check (decision 2026-10-26) the adder's right and who is already in are read
 * again, and the member count goes up before the rows are written, so two adds at once can't pass 1,000 between
 * them and the count is never below the rows; if the rows then fail to write, the count is set back to the rows.
 */
class AddMembers(
    private val chats: ChatRepository,
    private val writer: ChatWriter,
    private val store: MessagingStore,
    private val reach: ReachPolicy,
    private val membership: Membership,
    private val views: ChatViews,
    private val notifier: ChatNotifier,
    private val bus: EventBus,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: UserId,
        chatId: ChatId,
        memberIds: Set<UserId>,
    ): Resource<ChatView> {
        val adder = chats.member(chatId, caller) ?: return Resource.error(MessagingError.ChatNotFound)
        val chat = chats.chat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        if (!mayAdd(chat, adder)) return Resource.error(MessagingError.NotAdmin)

        val current = chats.members(chatId).map { it.userId }.toSet()
        val asked = memberIds - current
        if (current.size + asked.size > config.maxGroupMembers) return Resource.error(MessagingError.GroupFull)
        val added = reach.reachable(caller, asked)
        val notAdded = (asked - added).sortedBy { it.toString() }
        if (added.isEmpty()) return Resource.success(chat.viewFor(caller, notAdded))

        var joining = emptySet<UserId>()
        val counted =
            writer.update(chatId) { latest ->
                val decided =
                    decide(
                        latest,
                        caller,
                        added,
                    )
                joining = decided.getOrNull().orEmpty()
                decided.map { latest.copy(memberCount = latest.memberCount + it.size) }
            }
        val updated =
            when (counted) {
                is Resource.Error -> return counted
                is Resource.Success -> counted.data
            }
        val joins =
            joining.map { person ->
                join(
                    chatId,
                    person,
                    by = caller,
                )
            }
        write(chatId, joins)
        membership.changed(chatId)

        if (joining.isNotEmpty()) {
            notifier.membersAdded(
                recipients = current + joining,
                chatId = chatId,
                added = joining,
                by = caller,
            )
        }
        return Resource.success(updated.viewFor(caller, notAdded))
    }

    /** Inside the version check: who of [added] to write now, or why not. */
    private suspend fun decide(
        latest: Chat,
        caller: UserId,
        added: Set<UserId>,
    ): Resource<Set<UserId>> {
        if (latest.memberCount <= 0) return Resource.error(MessagingError.ChatNotFound)
        val adder = chats.member(latest.chatId, caller) ?: return Resource.error(MessagingError.ChatNotFound)
        if (!mayAdd(latest, adder)) return Resource.error(MessagingError.NotAdmin)
        val present = chats.members(latest.chatId).map { it.userId }.toSet()
        val joining = added - present
        if (latest.memberCount + joining.size > config.maxGroupMembers) return Resource.error(MessagingError.GroupFull)
        return Resource.success(joining)
    }

    private suspend fun write(chatId: ChatId, joins: List<Join>) {
        try {
            store.commit(ChatChanges(joins = joins))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            writer.recount(chatId)
            throw e
        }
    }

    private fun mayAdd(chat: Chat, adder: Member) =
        chat.kind == ChatKind.GROUP && (chat.roleOf(adder).isAdmin || chat.settings.membersCanAdd)

    private fun join(
        chatId: ChatId,
        person: UserId,
        by: UserId,
    ): Join {
        val member =
            Member(
                chatId,
                person,
                Role.MEMBER,
                clock.instant(),
            )
        val added =
            MemberAdded(
                chatId,
                person,
                by,
            )
        return Join(
            member,
            ChatKind.GROUP,
            bus.envelope(added),
        )
    }

    private suspend fun Chat.viewFor(caller: UserId, notAdded: List<UserId>): ChatView =
        views.view(
            this,
            lastMessageId = chats.userChat(caller, chatId)?.lastMessageId,
            notAdded = notAdded,
        )
}
