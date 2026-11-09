package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatListing
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.MemberView
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.shared.UserId
import com.circaso.users.api.Profiles
import com.circaso.users.api.PublicProfile
import java.util.UUID

/** Shown for a member whose profile users can't give (a deleted account), as users names an empty profile. */
private const val FALLBACK_DISPLAY_NAME = "Circaso user"

/**
 * Builds a chat as the API answers it: members with their public profiles from users, and roles as the chat sees
 * them ([Chat.roleOf]). `Profiles` shows an avatar to anyone who may reach its owner or shares a group with them
 * (users → Privacy defaults), so group members see each other's.
 */
class ChatViews(private val chats: ChatRepository, private val profiles: Profiles) {
    suspend fun view(
        chat: Chat,
        lastMessageId: UUID?,
        created: Boolean? = null,
        notAdded: List<UserId>? = null,
        invited: List<UserId>? = null,
    ): ChatView {
        val single = views(listOf(chat to lastMessageId)).single()
        return single.copy(created = created, notAdded = notAdded, invited = invited)
    }

    /** Public profiles of [ids], with the same fallback as members get: for invites, which aren't members. */
    suspend fun profiles(ids: Set<UserId>): Map<UserId, PublicProfile> {
        val found = if (ids.isEmpty()) emptyMap() else profiles.get(ids)
        return ids.associateWith { found[it] ?: fallbackProfile(it) }
    }

    /** Many chats with one profile lookup for every member of all of them. */
    suspend fun views(chatsWithLast: List<Pair<Chat, UUID?>>): List<ChatView> =
        views(chatsWithLast, chats.members(chatsWithLast.map { (chat, _) -> chat.chatId }))

    /** The chat list (M6), whose members came with it: only the profile lookup is left. */
    suspend fun views(listing: ChatListing): List<ChatView> =
        views(listing.lines.map { it.chat to it.lastMessageId }, listing.members)

    private suspend fun views(
        chatsWithLast: List<Pair<Chat, UUID?>>,
        membersByChat: Map<ChatId, List<Member>>,
    ): List<ChatView> {
        val ids =
            membersByChat.values
                .flatten()
                .map { it.userId }
                .toSet()
        val found = if (ids.isEmpty()) emptyMap() else profiles.get(ids)
        return chatsWithLast.map { (chat, last) ->
            val members = membersByChat[chat.chatId].orEmpty().sortedBy { it.joinedAt }
            ChatView(chat, members.map { it.toView(chat, found) }, last)
        }
    }

    private fun Member.toView(chat: Chat, found: Map<UserId, PublicProfile>): MemberView {
        val profile = found[userId] ?: fallbackProfile(userId)
        return MemberView(profile, chat.roleOf(this), joinedAt)
    }

    private fun fallbackProfile(userId: UserId) =
        PublicProfile(
            userId,
            FALLBACK_DISPLAY_NAME,
            null,
            null,
            null,
        )
}
