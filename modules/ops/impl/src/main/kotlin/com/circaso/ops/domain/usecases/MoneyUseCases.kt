package com.circaso.ops.domain.usecases

import com.circaso.identity.api.AccountAdmin
import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Finance
import com.circaso.ops.domain.models.TransferView
import com.circaso.ops.domain.models.TransferViewPage
import com.circaso.ops.domain.models.UserTransfers
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.domain.support.TransferParties
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.wallet.api.OpsHoldings
import com.circaso.wallet.api.ReportBucket
import com.circaso.wallet.api.TransferFilter
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.TransferPage
import com.circaso.wallet.api.WalletReports
import java.time.Duration
import java.time.Instant

// Money, read-only, for FINANCE and ADMIN (decision 2026-11-08). Staff never move money: every figure here is the
// wallet's own record (Circle for balances).

/** Puts both sides on a page of transfers: people, groups, outside addresses and Circaso's fee wallet. */
private suspend fun TransferPage.named(parties: TransferParties): TransferViewPage =
    TransferViewPage(parties.of(items), nextCursor)

class GetUserWallets(private val accounts: AccountAdmin, private val wallets: WalletReports) {
    suspend operator fun invoke(userId: UserId): Resource<OpsHoldings> {
        accounts.account(userId) ?: return Resource.error(OpsError.UserNotFound)
        return wallets.holdings(userId.value)
    }
}

/** A user's transfers, sent and received; the first page also carries the fees they have paid in all. */
class ListUserTransfers(
    private val accounts: AccountAdmin,
    private val wallets: WalletReports,
    private val parties: TransferParties,
) {
    suspend operator fun invoke(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<UserTransfers> {
        accounts.account(userId) ?: return Resource.error(OpsError.UserNotFound)
        return wallets.transfersOf(userId, cursor, limit).flatMap { page ->
            Resource.success(UserTransfers(page.named(parties), wallets.feesPaidBy(userId)))
        }
    }
}

class ListTransactions(private val wallets: WalletReports, private val parties: TransferParties) {
    suspend operator fun invoke(
        filter: TransferFilter,
        cursor: String?,
        limit: Int,
    ): Resource<TransferViewPage> = wallets.transfers(filter, cursor, limit).map { it.named(parties) }
}

class GetTransaction(private val wallets: WalletReports, private val parties: TransferParties) {
    suspend operator fun invoke(transferId: TransferId): Resource<TransferView> {
        val transfer = wallets.transfer(transferId) ?: return Resource.error(OpsError.TransferNotFound)
        return Resource.success(parties.of(transfer))
    }
}

/** Volumes and fees over a range, by period and by chain and coin. The range is at most [OpsSettings.maxRangeDays]. */
class GetFinanceReport(private val wallets: WalletReports, private val settings: OpsSettings) {
    suspend operator fun invoke(
        from: Instant,
        to: Instant,
        bucket: ReportBucket,
    ): Resource<Finance> {
        if (!to.isAfter(from) || Duration.between(from, to) > Duration.ofDays(settings.maxRangeDays.toLong())) {
            return Resource.error(
                SharedError.Malformed("to", "must be after from, at most ${settings.maxRangeDays} days"),
            )
        }
        return Resource.success(Finance(wallets.report(from, to, bucket), wallets.groupWalletCount()))
    }
}
