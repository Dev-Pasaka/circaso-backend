package com.circaso.wallet.presentation.warmup

import com.circaso.infrastructure.api.WarmUp
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.repositories.RuleRepository
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.repositories.TransferRepository
import com.circaso.wallet.domain.repositories.WalletOwnerRepository
import com.circaso.wallet.domain.repositories.WalletRepository
import java.time.Clock
import java.time.Duration

private const val SAMPLE = 20
private const val PAGE = 20

/**
 * Wallet's reads that stay in our database, read-only: the wallets, owners, history and spending of a real party
 * (someone with a history row this month; a random id when there is none), a transfer lookup for random ids, and the
 * fee and limit rules. Never Circle or any vendor: no balances, no challenges.
 */
class WalletWarmUp(
    private val wallets: WalletRepository,
    private val owners: WalletOwnerRepository,
    private val transfers: TransferRepository,
    private val transactions: TransactionRepository,
    private val rules: RuleRepository,
    private val clock: Clock,
) : WarmUp {
    override val name = "wallet"

    override suspend fun run() {
        val now = clock.instant()
        val userId = transactions.parties(now.minus(Duration.ofDays(30)), SAMPLE).randomOrNull() ?: UserId.new()
        wallets.circleUser(userId)
        wallets.wallets(userId)
        owners.bySigner(userId)
        transactions.history(userId, TransferState.entries.toSet(), null, null, null, PAGE)
        transactions.find(userId, Uuid7.generate())
        transfers.spentSince(userId, now.minus(Duration.ofDays(1)))
        transfers.byKey(userId, Uuid7.generate())
        transfers.find(TransferId(Uuid7.generate()))
        rules.feeRules()
        rules.limitRules()
    }
}
