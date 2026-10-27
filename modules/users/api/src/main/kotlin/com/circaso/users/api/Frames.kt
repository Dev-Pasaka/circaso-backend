package com.circaso.users.api

import com.circaso.shared.UserId
import kotlinx.serialization.Serializable

/**
 * The `data` of each realtime frame `users` sends, one class per frame type (decision 2026-10-27). They live in the
 * contract so the gateway reads them typed when it decides on a push.
 */
object UsersFrames {
    const val MODULE = "users"
    const val CONTACT_JOINED = "contact.joined"
    const val CONNECTION_ADDED = "connection.added"
    const val PROFILE_UPDATED = "profile.updated"
}

/** `users/contact.joined`: someone whose number the receiver has saved joined Circaso. */
@Serializable
data class ContactJoinedFrame(val userId: UserId, val displayName: String)

/** `users/connection.added`: a QR or handle connection with [userId] was made. */
@Serializable
data class ConnectionAddedFrame(val userId: UserId, val displayName: String)

/** `users/profile.updated`: [userId]'s profile is now at [version], a number. */
@Serializable
data class ProfileUpdatedFrame(val userId: UserId, val version: Long)
