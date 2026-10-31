package com.circaso.messaging.presentation.mappers

import com.circaso.messaging.domain.models.Presence
import com.circaso.messaging.presentation.dto.ChatPresenceResponse
import com.circaso.messaging.presentation.dto.PresenceDto
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

fun List<Presence>.toPresenceResponse(): ChatPresenceResponse =
    ChatPresenceResponse(map { PresenceDto(it.userId.toString(), it.online, it.lastSeenAt) })

/** `active` or `away`; anything else is a frame the phone can't have meant. */
fun parsePresenceState(raw: String): Resource<Boolean> =
    when (raw) {
        "active" -> Resource.success(true)
        "away" -> Resource.success(false)
        else -> Resource.error(SharedError.Malformed("state", "must be active or away"))
    }
