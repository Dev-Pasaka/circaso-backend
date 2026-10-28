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
import com.circaso.wallet.domain.models.TransferLimits
import com.circaso.wallet.domain.ports.DailySpend
import com.circaso.wallet.domain.ports.PaymentHolds
import com.circaso.wallet.domain.repositories.WalletRepository
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** The sender of a transfer: their Circle user and their wallet on the transfer's chain. */
data class Sender(val user: CircleUser, val wallet: ChainWallet)

/**
 * The rules about the person sending (Wallet → W4, in order): the account is `ACTIVE`, the wallet is active on the
 * transfer's chain, no payment hold, the amount within limits, and enough on that chain. The daily limit is a
 * reservation in `wal:sent`: a prepared transfer takes its amount, a failed one gives it back.
 */
class SenderRules(
    private val wallets: WalletRepository,
    private val accounts: AccountDirectory,
    private val holds: PaymentHolds,
    private val holdings: HoldingsReader,
    private val spend: DailySpend,
    private val custody: StablecoinCustody,
    private val limits: TransferLimits,
    private val clock: Clock,
) {
    /** The account may pay, and its wallet is active on [blockchain], with no hold on it. */
    suspend fun sender(userId: UserId, blockchain: String): Resource<Sender> {
        if (accounts.status(userId) != AccountStatus.ACTIVE) return Resource.error(WalletError.AccountRestricted)
        custody.supported.find(blockchain) ?: return Resource.error(WalletError.ChainUnsupported)
        val user = wallets.circleUser(userId) ?: return Resource.error(WalletError.NotFound)
        val open = wallets.wallets(userId).filter { it.state != ChainWalletState.CLOSED }
        if (open.isEmpty()) return Resource.error(WalletError.NotReady)
        val wallet =
            open.firstOrNull { it.blockchain == blockchain && it.state == ChainWalletState.ACTIVE }
                ?: return Resource.error(WalletError.NotOnChain)
        if (holds.active(userId) != null) return Resource.error(WalletError.OnHold)
        return Resource.success(Sender(user, wallet))
    }

    /** USDC, offered on the chain, above zero, within the per-payment limit and what is left of today's. */
    suspend fun checkAmount(
        userId: UserId,
        blockchain: String,
        amount: Money,
    ): Resource<Unit> {
        val offered =
            custody.supported
                .find(blockchain)
                ?.assets
                .orEmpty()
                .any { it.symbol == amount.asset.name }
        if (!offered || amount.amountMinor <= 0) return Resource.error(WalletError.AmountInvalid)
        if (amount.amountMinor > limits.perPaymentMinor) return Resource.error(WalletError.LimitExceeded)
        val sentToday = spend.total(userId, today())
        if (sentToday + amount.amountMinor > limits.dailyMinor) return Resource.error(WalletError.LimitExceeded)
        return Resource.success(Unit)
    }

    /** Circle's balance now (never the cache) of the asset on the sender's chain covers [amount]. */
    suspend fun checkBalance(sender: Sender, amount: Money): Resource<Unit> {
        val held =
            when (val read = holdings.fresh(sender.user, sender.wallet)) {
                is Resource.Success -> read.data
                is Resource.Error -> return read
            }
        val balance = held.firstOrNull { it.symbol == amount.asset.name }?.amountMinor ?: BigInteger.ZERO
        if (balance < BigInteger.valueOf(amount.amountMinor)) return Resource.error(WalletError.InsufficientFunds)
        return Resource.success(Unit)
    }

    /**
     * Takes [amount] from the limit of the day a transfer is prepared ([preparedAt]), or answers
     * `wallet.limit_exceeded`, taking nothing, when another transfer took the rest since [checkAmount].
     */
    suspend fun reserve(
        userId: UserId,
        preparedAt: Instant,
        amount: Money,
    ): Resource<Unit> {
        val day = dayOf(preparedAt)
        val total = spend.add(userId, day, amount.amountMinor)
        if (total <= limits.dailyMinor) return Resource.success(Unit)
        spend.add(userId, day, -amount.amountMinor)
        return Resource.error(WalletError.LimitExceeded)
    }

    /** Gives a transfer's amount back to the day it was prepared on: it failed, so nothing left the wallet. */
    suspend fun release(
        userId: UserId,
        preparedAt: Instant,
        amount: Money,
    ) {
        spend.add(userId, dayOf(preparedAt), -amount.amountMinor)
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun dayOf(instant: Instant): LocalDate = LocalDate.ofInstant(instant, clock.zone)
}
