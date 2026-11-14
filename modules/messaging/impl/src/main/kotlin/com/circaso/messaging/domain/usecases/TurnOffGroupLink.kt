package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.GroupLinkRepository
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * M30 `DELETE /chats/{chatId}/link` (decision 2026-11-14): an admin turns the group's join link off. It stops working
 * at once and the published preview (name, description, photo) is deleted in the same statement; only an empty
 * tombstone stays until the purge. With no link there is nothing to do, and that is a success too, so a retry is
 * safe. The people who already joined stay.
 */
class TurnOffGroupLink(
    private val chats: ChatRepository,
    private val links: GroupLinkRepository,
    private val transactor: Transactor,
    private val membership: Membership,
    private val notifier: ChatNotifier,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, chatId: ChatId): Resource<Unit> {
        val turnedOff =
            transactor.attempt("links.turn-off") {
                val chat = chats.lockChat(chatId) ?: return@attempt Resource.error(MessagingError.ChatNotFound)
                val member = chats.member(chatId, caller) ?: return@attempt Resource.error(MessagingError.ChatNotFound)
                if (chat.kind != ChatKind.GROUP) return@attempt Resource.error(MessagingError.NotAGroup)
                if (!chat.roleOf(member).isAdmin) return@attempt Resource.error(MessagingError.NotAdmin)
                val now = clock.instant()
                Resource.success(links.revoke(chatId, now, now.plus(config.linkTombstone)))
            }
        return when (turnedOff) {
            is Resource.Error -> {
                turnedOff
            }

            is Resource.Success -> {
                if (turnedOff.data) notifier.linkChanged(membership.admins(chatId), chatId)
                Resource.success(Unit)
            }
        }
    }
}
