package com.circaso.wallet.api

import com.circaso.shared.DomainEvent
import com.circaso.shared.UserId
import kotlinx.serialization.Serializable

// Published on `events:wallet`.

/**
 * A wallet came to be on [blockchain]: the user's first one when the wallet turns `ACTIVE`, and one more each time
 * they add a chain (decision 2026-10-28, a wallet on several chains). Published once per chain.
 */
@Serializable
data class WalletCreated(val userId: UserId, val blockchain: String) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "wallet.WalletCreated"
    }
}
