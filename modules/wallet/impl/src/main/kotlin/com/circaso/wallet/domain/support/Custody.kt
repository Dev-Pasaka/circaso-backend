package com.circaso.wallet.domain.support

import com.circaso.integrations.api.CustodyWallet
import com.circaso.integrations.api.CustodyWalletState
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import java.time.Instant

/**
 * A custody failure in the wallet's words: whatever Circle said (down, refused, a bad token), the app sees
 * `wallet.custody_unavailable`. The integration has already logged the detail, by operation.
 */
fun <T> Resource<T>.orCustodyUnavailable(): Resource<T> {
    if (this !is Resource.Error) return this
    return Resource.error(WalletError.CustodyUnavailable, cause)
}

/**
 * Addresses as the wallet stores and compares them: an EVM address (`0x` and 40 hex digits) in lowercase, since its
 * case is only a checksum; anything else (a Solana base58 address) exactly as given, since there case matters.
 */
object Addresses {
    private val EVM = Regex("^0x[0-9a-fA-F]{40}$")

    fun normalise(address: String): String = if (EVM.matches(address)) address.lowercase() else address
}

/** Circle's wallet as our row for [userId]. */
fun CustodyWallet.toChainWallet(userId: UserId, at: Instant): ChainWallet =
    ChainWallet(
        circleWalletId = walletId,
        userId = userId,
        blockchain = blockchain,
        accountType = accountType,
        address = Addresses.normalise(address),
        state = if (state == CustodyWalletState.LIVE) ChainWalletState.ACTIVE else ChainWalletState.FROZEN,
        createdAt = at,
    )
