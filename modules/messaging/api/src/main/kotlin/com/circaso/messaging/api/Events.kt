package com.circaso.messaging.api

import com.circaso.shared.DomainEvent
import com.circaso.shared.UserId
import kotlinx.serialization.Serializable

// Published on `events:messaging`. Message traffic is never published: it goes straight to inboxes and sockets.

/** A chat came to be. A direct chat names both people; a group its creator, and each member is a [MemberAdded]. */
@Serializable
data class ChatCreated(
    val chatId: ChatId,
    /** `DIRECT` or `GROUP`. */
    val kind: String,
    val memberIds: List<UserId>? = null,
    val createdBy: UserId? = null,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "messaging.ChatCreated"
    }
}

@Serializable
data class MemberAdded(val chatId: ChatId, val userId: UserId, val byUserId: UserId) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "messaging.MemberAdded"
    }
}

/** Someone left ([byUserId] is themselves) or was removed by an admin. */
@Serializable
data class MemberRemoved(val chatId: ChatId, val userId: UserId, val byUserId: UserId) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "messaging.MemberRemoved"
    }
}

@Serializable
data class GroupDeleted(val chatId: ChatId) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "messaging.GroupDeleted"
    }
}
