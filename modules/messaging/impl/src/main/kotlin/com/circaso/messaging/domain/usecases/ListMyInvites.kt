package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.models.InviteView
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.InviteRepository
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * M23 `GET /chats/invites` (decision 2026-11-09): the caller's open invites, newest first, each with the group's
 * sealed name and photo as they now are, its member count and who invited them. Never the members or messages: the
 * person isn't in the group yet. An invite whose group is gone isn't listed.
 */
class ListMyInvites(
    private val invites: InviteRepository,
    private val chats: ChatRepository,
    private val views: ChatViews,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId): Resource<List<InviteView>> {
        val open = invites.openOf(caller, clock.instant())
        if (open.isEmpty()) return Resource.success(emptyList())
        val inviters = views.profiles(open.mapTo(HashSet()) { it.invitedBy })
        val listed =
            open.mapNotNull { invite ->
                chats.chat(invite.chatId)?.let { InviteView(invite, it, inviters.getValue(invite.invitedBy)) }
            }
        return Resource.success(listed)
    }
}
