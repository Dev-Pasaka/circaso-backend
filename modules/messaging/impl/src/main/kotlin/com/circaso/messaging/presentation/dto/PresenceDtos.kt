package com.circaso.messaging.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/** The same shape as the `presence.changed` frame's data. */
@Serializable
data class PresenceDto(
    val userId: String,
    val online: Boolean,
    @Serializable(with = InstantSerializer::class) val lastSeenAt: Instant?,
)

@Serializable
data class ChatPresenceResponse(val members: List<PresenceDto>)
