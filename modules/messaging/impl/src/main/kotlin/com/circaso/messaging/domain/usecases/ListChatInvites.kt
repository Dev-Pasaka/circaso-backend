package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.PendingInviteView
import com.circaso.messaging.domain.repositories.InviteRepository
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.Membership
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * M26 `GET /chats/{chatId}/invites` (decision 2026-11-09): the group's open invites, oldest first, for group info's
 * "Invited" list. Any member sees them, as they see the members; declined, cancelled and expired invites aren't
 * listed. A chat the caller isn't in answers as if it didn't exist.
 */
class ListChatInvites(
    private val invites: InviteRepository,
    private val membership: Membership,
    private val views: ChatViews,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, chatId: ChatId): Resource<List<PendingInviteView>> {
        if (!membership.isMember(chatId, caller)) return Resource.error(MessagingError.ChatNotFound)
        val open = invites.openIn(chatId, clock.instant())
        val people = views.profiles(open.flatMapTo(HashSet()) { listOf(it.userId, it.invitedBy) })
        return Resource.success(
            open.map { PendingInviteView(it, people.getValue(it.userId), people.getValue(it.invitedBy)) },
        )
    }
}
