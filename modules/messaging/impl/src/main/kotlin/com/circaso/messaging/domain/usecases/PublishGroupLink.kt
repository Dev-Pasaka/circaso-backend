package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.GroupLinkToken
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.GroupLink
import com.circaso.messaging.domain.models.PublishLinkCommand
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.GroupLinkRepository
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.LinkPreviews
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import java.time.Clock

/**
 * M29 `PUT /chats/{chatId}/link` (decision 2026-11-14): an admin makes the group's join link, publishes its preview
 * again, or resets it. The server can't read the group's sealed name, so the admin's phone publishes what the link's
 * page shows, for that link only: the name, the description and a small photo. Nothing else of the group is ever
 * published.
 *
 * One link per group is live. With none, this makes one (a fresh 131-bit token). With one, it replaces the published
 * preview and keeps the token, so links already shared keep working; with `reset`, the old link stops working at once
 * (its preview deleted) and a new one takes its place. All under the chat's row lock, where the caller's right is
 * read, so a demotion a moment earlier can't slip past and two admins at once leave one link. The group's admins get
 * `link.changed`.
 */
class PublishGroupLink(
    private val chats: ChatRepository,
    private val links: GroupLinkRepository,
    private val transactor: Transactor,
    private val membership: Membership,
    private val notifier: ChatNotifier,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: PublishLinkCommand): Resource<GroupLink> {
        if (!LinkPreviews.isValid(command.preview, config)) return Resource.error(MessagingError.LinkInvalid)
        val written = transactor.attempt("links.publish") { write(command) }
        if (written is Resource.Success) notifier.linkChanged(membership.admins(command.chatId), command.chatId)
        return written
    }

    /** Inside the transaction, with the chat's row locked before the caller's role or the link is read. */
    private suspend fun write(command: PublishLinkCommand): Resource<GroupLink> {
        val chatId = command.chatId
        val chat = chats.lockChat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        val caller = chats.member(chatId, command.caller) ?: return Resource.error(MessagingError.ChatNotFound)
        if (chat.kind != ChatKind.GROUP) return Resource.error(MessagingError.NotAGroup)
        if (!chat.roleOf(caller).isAdmin) return Resource.error(MessagingError.NotAdmin)

        val now = clock.instant()
        val live = links.liveOf(chatId)
        if (live != null && !command.reset) {
            links.republish(live.token, command.preview, now)
            return Resource.success(live.copy(preview = command.preview, updatedAt = now))
        }
        if (live != null) links.revoke(chatId, now, now.plus(config.linkTombstone))
        val link =
            GroupLink(
                token = GroupLinkToken.new(),
                chatId = chatId,
                createdBy = command.caller,
                preview = command.preview,
                createdAt = now,
                updatedAt = now,
                revokedAt = null,
            )
        links.insert(link)
        return Resource.success(link)
    }
}
