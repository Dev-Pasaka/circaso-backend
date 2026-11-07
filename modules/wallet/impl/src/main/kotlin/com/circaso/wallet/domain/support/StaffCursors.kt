package com.circaso.wallet.domain.support

import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.models.GroupWalletKey
import com.circaso.wallet.domain.models.TransferKey
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The staff lists' cursors: the last row's `(created_at, id)` as base64url text, opaque to the caller so the key can
 * change without a contract change. Anything that doesn't decode is not a cursor (null): `request.malformed`.
 */
internal object StaffCursors {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(key: TransferKey): String = encode(key.createdAt, key.transferId.value)

    fun encode(key: GroupWalletKey): String = encode(key.createdAt, key.walletId)

    fun transfer(cursor: String): TransferKey? = decode(cursor)?.let { (at, id) -> TransferKey(at, TransferId(id)) }

    fun groupWallet(cursor: String): GroupWalletKey? = decode(cursor)?.let { (at, id) -> GroupWalletKey(at, id) }

    private fun encode(at: Instant, id: UUID): String =
        encoder.encodeToString("${at.epochSecond}:${at.nano}:$id".toByteArray())

    private fun decode(cursor: String): Pair<Instant, UUID>? =
        runCatching {
            val (seconds, nanos, id) = String(decoder.decode(cursor)).split(':').also { require(it.size == 3) }
            Instant.ofEpochSecond(seconds.toLong(), nanos.toLong()) to UUID.fromString(id)
        }.getOrNull()
}
