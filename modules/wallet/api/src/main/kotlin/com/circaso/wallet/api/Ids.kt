package com.circaso.wallet.api

import com.circaso.shared.Uuid7
import com.circaso.shared.UuidIdSerializer
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One transfer prepared by the wallet (W4). It is also Circle's `refId`, so every update Circle sends about the
 * transaction leads back to it, and the payment card in a chat carries it.
 */
@Serializable(with = TransferId.Serializer::class)
@JvmInline
value class TransferId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): TransferId = TransferId(Uuid7.generate())

        fun parse(raw: String): TransferId? = Uuid7.parse(raw)?.let(::TransferId)
    }

    object Serializer : UuidIdSerializer<TransferId>("TransferId", ::TransferId, TransferId::value)
}

/**
 * One wallet (decision 2026-11-03, Spaces phase 2: a wallet belongs to a person or a group). A person's own wallet
 * has their user id as its id, so every personal wallet that existed before stays what it was; a group wallet gets
 * a fresh id when its owner sets it up (W14). It is also the key the wallet's tables file the wallet under (the
 * "holder"), which is why a personal wallet needs no row to say whose it is.
 */
@Serializable(with = WalletId.Serializer::class)
@JvmInline
value class WalletId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): WalletId = WalletId(Uuid7.generate())

        fun parse(raw: String): WalletId? = Uuid7.parse(raw)?.let(::WalletId)
    }

    object Serializer : UuidIdSerializer<WalletId>("WalletId", ::WalletId, WalletId::value)
}

/** Who a wallet belongs to. `SPACE` arrives with Spaces (phase 3); the tables already take it. */
enum class WalletOwnerType { PERSON, GROUP, SPACE }

/**
 * An amount as the wallet hands it out in events and frames (decision 2026-10-28: amounts are strings everywhere in
 * the wallet). [amountMinor] is the exact integer in the asset's smallest unit, [amount] the same value in whole units
 * for display ("20.5"). Never logged.
 */
@Serializable
data class WalletAmount(val asset: String, val amountMinor: String, val amount: String) {
    override fun toString(): String = "WalletAmount($asset, ***)"
}
