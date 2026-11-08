package com.circaso.identity.domain.support

import com.circaso.identity.domain.models.AccountKey
import com.circaso.shared.UserId
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The staff list's cursor: the last row's key as base64url text, opaque to the caller so the key can change without
 * a contract change. Anything that doesn't decode to a key is not a cursor (null), which the use case answers with
 * `request.malformed`.
 */
internal object AdminCursors {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(key: AccountKey): String =
        encoder.encodeToString(
            "${key.createdAt.epochSecond}:${key.createdAt.nano}:${key.userId}".toByteArray(),
        )

    fun decode(cursor: String): AccountKey? =
        runCatching {
            val (seconds, nanos, id) = String(decoder.decode(cursor)).split(':').also { require(it.size == 3) }
            AccountKey(Instant.ofEpochSecond(seconds.toLong(), nanos.toLong()), UserId(UUID.fromString(id)))
        }.getOrNull()
}
