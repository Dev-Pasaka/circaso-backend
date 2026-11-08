package com.circaso.wallet.domain.support

import com.circaso.integrations.api.CustodyWallet
import com.circaso.integrations.api.CustodyWalletState
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.ErrorKind
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
 * A refused transfer in the wallet's words (decision 2026-10-27: custody's `PinLocked`, `InsufficientFunds` and
 * `AddressInvalid` become wallet codes); anything else is `wallet.custody_unavailable`.
 */
fun <T> Resource<T>.orTransferError(): Resource<T> {
    if (this !is Resource.Error) return this
    val mapped =
        when (error) {
            is IntegrationError.InsufficientFunds -> WalletError.InsufficientFunds
            is IntegrationError.AddressInvalid -> WalletError.AddressInvalid
            is IntegrationError.PinLocked -> WalletError.PinLocked
            is IntegrationError.AssetNotSupported -> WalletError.AssetNotSupported
            else -> WalletError.CustodyUnavailable
        }
    return Resource.error(mapped, cause)
}

/**
 * Whether Circle looked at the request and refused it, so asking again with the same key can't succeed (as opposed
 * to Circle being down, where the same request may work later).
 */
val Resource.Error.isRefusal: Boolean get() = error.kind == ErrorKind.INVALID

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
