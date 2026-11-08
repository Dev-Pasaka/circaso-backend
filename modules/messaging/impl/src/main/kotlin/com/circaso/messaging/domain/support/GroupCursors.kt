package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.GroupKey
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The staff group list's cursor: the last group's key as base64url text, opaque to the caller. Anything that doesn't
 * decode is not a cursor (null), which the use case answers with `request.malformed`.
 */
internal object GroupCursors {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(key: GroupKey): String =
        encoder.encodeToString("${key.createdAt.epochSecond}:${key.createdAt.nano}:${key.chatId.value}".toByteArray())

    fun decode(cursor: String): GroupKey? =
        runCatching {
            val (seconds, nanos, id) = String(decoder.decode(cursor)).split(':').also { require(it.size == 3) }
            GroupKey(Instant.ofEpochSecond(seconds.toLong(), nanos.toLong()), ChatId(UUID.fromString(id)))
        }.getOrNull()
}
