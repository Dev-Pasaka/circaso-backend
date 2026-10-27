package com.circaso.users.data.cache

import com.circaso.shared.RealtimeEnvelope
import com.circaso.users.api.ConnectionAddedFrame
import com.circaso.users.api.ContactJoinedFrame
import com.circaso.users.api.ProfileUpdatedFrame
import com.circaso.users.api.UsersFrames
import com.circaso.users.domain.ports.UsersFrame
import java.time.Instant

/**
 * The envelope for a frame the domain names, at [seq]: its wire `type` and its class from `users:api`, so the wire
 * shape is fixed in one place (`version` goes out as a number).
 */
internal fun UsersFrame.toEnvelope(seq: Long, ts: Instant): RealtimeEnvelope =
    when (this) {
        is UsersFrame.ContactJoined -> {
            RealtimeEnvelope.of(
                UsersFrames.MODULE,
                UsersFrames.CONTACT_JOINED,
                seq,
                ContactJoinedFrame(userId, displayName),
                ContactJoinedFrame.serializer(),
                ts,
            )
        }

        is UsersFrame.ConnectionAdded -> {
            RealtimeEnvelope.of(
                UsersFrames.MODULE,
                UsersFrames.CONNECTION_ADDED,
                seq,
                ConnectionAddedFrame(userId, displayName),
                ConnectionAddedFrame.serializer(),
                ts,
            )
        }

        is UsersFrame.ProfileUpdated -> {
            RealtimeEnvelope.of(
                UsersFrames.MODULE,
                UsersFrames.PROFILE_UPDATED,
                seq,
                ProfileUpdatedFrame(userId, version),
                ProfileUpdatedFrame.serializer(),
                ts,
            )
        }
    }
