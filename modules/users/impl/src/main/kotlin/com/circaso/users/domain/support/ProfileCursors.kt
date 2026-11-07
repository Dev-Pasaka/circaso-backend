package com.circaso.users.domain.support

import com.circaso.shared.UserId
import com.circaso.users.domain.models.ProfileIdResults
import com.circaso.users.domain.models.ProfileKey
import java.util.Base64
import java.util.UUID

/**
 * The staff search's cursor: the last row's key as base64url text (`<userId>:<sortKey>`), opaque to the caller. The
 * id goes first because it has a fixed shape, so a sort key holding `:` still splits. Anything that doesn't decode is
 * not a cursor (null), which the use case answers with `request.malformed`.
 */
internal object ProfileCursors {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(key: ProfileKey): String = encoder.encodeToString("${key.userId}:${key.sortKey}".toByteArray())

    fun decode(cursor: String): ProfileKey? =
        runCatching {
            val text = String(decoder.decode(cursor))
            val id = UUID.fromString(text.substringBefore(':'))
            require(text.contains(':'))
            ProfileKey(text.substringAfter(':'), UserId(id))
        }.getOrNull()
}

/** One page of a staff search: [fetched] holds one row more than [limit] when there is a next page. */
internal fun pageOf(fetched: List<ProfileKey>, limit: Int) =
    ProfileIdResults(
        fetched.take(limit).map { it.userId },
        if (fetched.size > limit) ProfileCursors.encode(fetched[limit - 1]) else null,
    )
