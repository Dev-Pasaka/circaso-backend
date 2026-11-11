package com.circaso.identity.domain.support

import com.circaso.identity.domain.models.GroupKeyPosition
import com.circaso.shared.Uuid7
import java.util.Base64

/**
 * The group key list's cursor: the last entry's `(chatId, metaVersion)` as base64url text, opaque to the phone so the
 * key can change without a contract change. Anything that doesn't decode to one is not a cursor (null), which the use
 * case answers with `request.malformed`.
 */
internal object GroupKeyCursors {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(position: GroupKeyPosition): String =
        encoder.encodeToString("${position.chatId}:${position.metaVersion}".toByteArray())

    fun decode(cursor: String): GroupKeyPosition? =
        runCatching {
            val (chat, version) = String(decoder.decode(cursor)).split(':').also { require(it.size == 2) }
            GroupKeyPosition(requireNotNull(Uuid7.parse(chat)), version.toInt().also { require(it >= 0) })
        }.getOrNull()
}
