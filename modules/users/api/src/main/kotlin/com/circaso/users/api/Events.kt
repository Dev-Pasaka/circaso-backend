package com.circaso.users.api

import com.circaso.shared.DomainEvent
import com.circaso.shared.UserId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Published on `events:users`. */

@Serializable
data class ProfileUpdated(
    val userId: UserId,
    /** The profile's version after the change; consumers drop an event older than the copy they hold. */
    @SerialName("version") val profileVersion: Long,
    val changed: List<String>,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "users.ProfileUpdated"
        const val DISPLAY_NAME = "displayName"
        const val AVATAR = "avatar"
        const val HANDLE = "handle"
        const val ABOUT = "about"
    }
}

@Serializable
data class ConnectionAdded(
    val fromUserId: UserId,
    val toUserId: UserId,
    val source: String,
    val mutual: Boolean,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "users.ConnectionAdded"
    }
}

@Serializable
data class ContactRemoved(val ownerId: UserId, val contactId: UserId) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "users.ContactRemoved"
    }
}

@Serializable
data class UserBlocked(val byUserId: UserId, val targetUserId: UserId) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "users.UserBlocked"
    }
}

@Serializable
data class UserUnblocked(val byUserId: UserId, val targetUserId: UserId) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "users.UserUnblocked"
    }
}
