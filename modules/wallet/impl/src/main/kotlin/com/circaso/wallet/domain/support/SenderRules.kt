package com.circaso.wallet.domain.support

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountStatus
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.CircleUser
import com.circaso.wallet.domain.ports.PaymentHolds
import com.circaso.wallet.domain.repositories.WalletRepository
import java.math.BigInteger

/** The sender of a transfer: their Circle user and their wallet on the transfer's chain. */
data class Sender(val user: CircleUser, val wallet: ChainWallet)

/**
 * The rules about the person sending (Wallet → W4, in order): not a support account, the account is `ACTIVE`, the
 * wallet is active on the transfer's chain, no payment hold, an amount that can be sent, and enough on that chain.
 * The limits on amounts are [Limits]' (phase 2F: rules in the database, a rolling window over the transfers).
 */
class SenderRules(
    private val wallets: WalletRepository,
    private val accounts: AccountDirectory,
    private val holds: PaymentHolds,
    private val holdings: HoldingsReader,
    private val custody: StablecoinCustody,
    private val support: SupportAccountRule,
) {
    /**
     * The account may pay, and its wallet is active on [blockchain], with no hold on it. [userId] is the wallet's
     * holder; [signer] the person signing (the same for a personal wallet; a group wallet's owner otherwise), whose
     * account and hold are what count.
     */
    suspend fun sender(
        userId: UserId,
        blockchain: String,
        signer: UserId = userId,
    ): Resource<Sender> {
        support.refusal(signer)?.let { return it }
        if (accounts.status(signer) != AccountStatus.ACTIVE) return Resource.error(WalletError.AccountRestricted)
        custody.supported.find(blockchain) ?: return Resource.error(WalletError.ChainUnsupported)
        val user = wallets.circleUser(userId) ?: return Resource.error(WalletError.NotFound)
        val open = wallets.wallets(userId).filter { it.state != ChainWalletState.CLOSED }
        if (open.isEmpty()) return Resource.error(WalletError.NotReady)
        val wallet =
            open.firstOrNull { it.blockchain == blockchain && it.state == ChainWalletState.ACTIVE }
                ?: return Resource.error(WalletError.NotOnChain)
        if (holds.active(signer) != null) return Resource.error(WalletError.OnHold)
        return Resource.success(Sender(user, wallet))
    }

    /** An asset offered on the chain, above zero. */
    fun checkAmount(blockchain: String, amount: Money): Resource<Unit> {
        val offered =
            custody.supported
                .find(blockchain)
                ?.assets
                .orEmpty()
                .any { it.symbol == amount.asset.name }
        if (!offered || amount.amountMinor <= 0) return Resource.error(WalletError.AmountInvalid)
        return Resource.success(Unit)
    }

    /** Circle's balance now (never the cache) of [amount]'s asset on the sender's chain, in minor units. */
    suspend fun balance(sender: Sender, amount: Money): Resource<Long> {
        val held =
            when (val read = holdings.fresh(sender.user, sender.wallet)) {
                is Resource.Success -> read.data
                is Resource.Error -> return read
            }
        val balance = held.firstOrNull { it.symbol == amount.asset.name }?.amountMinor ?: BigInteger.ZERO
        return Resource.success(balance.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong())
    }
}
