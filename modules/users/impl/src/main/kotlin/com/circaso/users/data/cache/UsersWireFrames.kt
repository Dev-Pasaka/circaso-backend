package com.circaso.users.data.cache

import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.UserId
import com.circaso.users.domain.ports.UsersFrame
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * The `data` of each `users` frame as the phone reads it, one class per frame type (decision 2026-10-27). The domain
 * names what happened in [UsersFrame]; these classes fix the wire shape, so `version` goes out as a number.
 */
@Serializable
internal data class ContactJoinedFrame(val userId: UserId, val displayName: String)

@Serializable
internal data class ConnectionAddedFrame(val userId: UserId, val displayName: String)

@Serializable
internal data class ProfileUpdatedFrame(val userId: UserId, val version: Long)

internal const val USERS_MODULE = "users"

/** The envelope for [frame] at [seq], with its `type` and its wire class. */
internal fun UsersFrame.toEnvelope(seq: Long, ts: Instant): RealtimeEnvelope =
    when (this) {
        is UsersFrame.ContactJoined -> {
            RealtimeEnvelope.of(
                USERS_MODULE,
                "contact.joined",
                seq,
                ContactJoinedFrame(userId, displayName),
                ContactJoinedFrame.serializer(),
                ts,
            )
        }

        is UsersFrame.ConnectionAdded -> {
            RealtimeEnvelope.of(
                USERS_MODULE,
                "connection.added",
                seq,
                ConnectionAddedFrame(userId, displayName),
                ConnectionAddedFrame.serializer(),
                ts,
            )
        }

        is UsersFrame.ProfileUpdated -> {
            RealtimeEnvelope.of(
                USERS_MODULE,
                "profile.updated",
                seq,
                ProfileUpdatedFrame(userId, version),
                ProfileUpdatedFrame.serializer(),
                ts,
            )
        }
    }
