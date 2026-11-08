package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.wallet.api.ReportBucket
import com.circaso.wallet.api.WalletOwnerType
import com.circaso.wallet.data.tables.FeeLedgerTable
import com.circaso.wallet.data.tables.TransfersTable
import com.circaso.wallet.data.tables.WalletOwnersTable
import com.circaso.wallet.domain.models.ChainAssetSums
import com.circaso.wallet.domain.models.FeeState
import com.circaso.wallet.domain.models.GroupWalletKey
import com.circaso.wallet.domain.models.PeriodSums
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferKey
import com.circaso.wallet.domain.models.TransferParty
import com.circaso.wallet.domain.models.TransferQuery
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.repositories.WalletReportRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.javatime.JavaLocalDateColumnType
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Staff reads over `transfers`, `fee_ledger` and `wallet_owners` (decision 2026-11-08), each on an index of
 * V9__staff_reports: keyset pages newest first, and aggregates bounded by the report's range.
 */
class WalletReportRepositoryImpl(private val database: Database) : WalletReportRepository {
    override suspend fun transfers(
        query: TransferQuery,
        after: TransferKey?,
        limit: Int,
    ): List<Transfer> =
        database.tx("reports.transfers") {
            // With a party, transfers_sender_idx or transfers_recipient_idx; without, transfers_newest_idx (or
            // transfers_confirmed_idx for confirmed ones). Every one is (…, created_at DESC, transfer_id DESC).
            TransfersTable
                .selectAll()
                .where { conditions(query, after) }
                .orderBy(TransfersTable.createdAt to SortOrder.DESC, TransfersTable.transferId to SortOrder.DESC)
                .limit(limit)
                .map { it.toTransfer() }
                .toList()
        }

    override suspend fun feesPaidBy(holder: UserId): Long =
        database.tx("reports.fees-paid-by") {
            // fee_ledger_collected_idx (holder_id WHERE state = 'COLLECTED').
            val fees = FeeLedgerTable.feeUsdMinor.sum()
            FeeLedgerTable
                .select(fees)
                .where { (FeeLedgerTable.holderId eq holder.value) and collected() }
                .map { it[fees] ?: 0 }
                .single()
        }

    override suspend fun confirmedByPeriod(
        from: Instant,
        until: Instant,
        bucket: ReportBucket,
    ): Map<LocalDate, PeriodSums> =
        database.tx("reports.confirmed-by-period") {
            // A range of transfers_confirmed_idx (created_at WHERE state = 'CONFIRMED').
            val period = UtcPeriod(TransfersTable.createdAt, bucket)
            val count = TransfersTable.transferId.count()
            val volume = TransfersTable.usdTotalMinor.sum()
            TransfersTable
                .select(period, count, volume)
                .where { confirmed() and createdIn(TransfersTable.createdAt, from, until) }
                .groupBy(period)
                .map { it[period] to PeriodSums(it[count], it[volume] ?: 0) }
                .toList()
                .toMap()
        }

    override suspend fun feesByPeriod(
        from: Instant,
        until: Instant,
        bucket: ReportBucket,
    ): Map<LocalDate, Long> =
        database.tx("reports.fees-by-period") {
            // A range of fee_ledger_report_idx (created_at, blockchain).
            val period = UtcPeriod(FeeLedgerTable.createdAt, bucket)
            val fees = FeeLedgerTable.feeUsdMinor.sum()
            FeeLedgerTable
                .select(period, fees)
                .where { collected() and createdIn(FeeLedgerTable.createdAt, from, until) }
                .groupBy(period)
                .map { it[period] to (it[fees] ?: 0) }
                .toList()
                .toMap()
        }

    override suspend fun confirmedByChainAsset(from: Instant, until: Instant): List<ChainAssetSums> =
        database.tx("reports.confirmed-by-chain-asset") {
            val count = TransfersTable.transferId.count()
            val amount = TransfersTable.amountMinor.sum()
            val volume = TransfersTable.usdTotalMinor.sum()
            TransfersTable
                .select(
                    TransfersTable.blockchain,
                    TransfersTable.asset,
                    TransfersTable.assetDecimals,
                    count,
                    amount,
                    volume,
                ).where { confirmed() and createdIn(TransfersTable.createdAt, from, until) }
                .groupBy(TransfersTable.blockchain, TransfersTable.asset, TransfersTable.assetDecimals)
                .map {
                    ChainAssetSums(
                        blockchain = it[TransfersTable.blockchain],
                        asset = it[TransfersTable.asset],
                        decimals = it[TransfersTable.assetDecimals].toInt(),
                        count = it[count],
                        amountMinor = (it[amount] ?: BigDecimal.ZERO).toBigIntegerExact(),
                        volumeUsdMicros = it[volume] ?: 0,
                    )
                }.toList()
        }

    override suspend fun feesByChainAsset(from: Instant, until: Instant): Map<Pair<String, String>, Long> =
        database.tx("reports.fees-by-chain-asset") {
            val fees = FeeLedgerTable.feeUsdMinor.sum()
            FeeLedgerTable
                .select(FeeLedgerTable.blockchain, FeeLedgerTable.asset, fees)
                .where { collected() and createdIn(FeeLedgerTable.createdAt, from, until) }
                .groupBy(FeeLedgerTable.blockchain, FeeLedgerTable.asset)
                .map { (it[FeeLedgerTable.blockchain] to it[FeeLedgerTable.asset]) to (it[fees] ?: 0) }
                .toList()
                .toMap()
        }

    override suspend fun groupWalletCount(): Long =
        database.tx("reports.group-wallet-count") {
            val count = WalletOwnersTable.walletId.count()
            WalletOwnersTable
                .select(count)
                .where { isGroup() }
                .map { it[count] }
                .single()
        }

    override suspend fun groupWallets(after: GroupWalletKey?, limit: Int): List<WalletOwner.Group> =
        database.tx("reports.group-wallets") {
            // wallet_owners_groups_newest_idx: (created_at DESC, wallet_id DESC) WHERE owner_type = 'GROUP'.
            WalletOwnersTable
                .selectAll()
                .where { isGroup() and (after?.let(::before) ?: Op.TRUE) }
                .orderBy(WalletOwnersTable.createdAt to SortOrder.DESC, WalletOwnersTable.walletId to SortOrder.DESC)
                .limit(limit)
                .map { it.toGroup() }
                .toList()
        }

    private fun conditions(query: TransferQuery, after: TransferKey?): Op<Boolean> =
        listOfNotNull(
            query.state?.let { TransfersTable.state eq it.name },
            query.blockchain?.let { TransfersTable.blockchain eq it },
            query.asset?.let { TransfersTable.asset eq it },
            query.party?.let(::partyIs),
            query.from?.let { TransfersTable.createdAt greaterEq it },
            query.until?.let { TransfersTable.createdAt less it },
            after?.let(::before),
        ).reduceOrNull { all, part -> all and part } ?: Op.TRUE

    private fun partyIs(party: TransferParty): Op<Boolean> =
        when (party) {
            is TransferParty.Sender -> TransfersTable.senderId eq party.userId.value
            is TransferParty.Recipient -> TransfersTable.recipientId eq party.userId.value
        }

    /** Transfers after [key] newest first; the leading `<=` makes it a range of the index rather than a filter. */
    private fun before(key: TransferKey): Op<Boolean> =
        (TransfersTable.createdAt lessEq key.createdAt) and
            (
                (TransfersTable.createdAt less key.createdAt) or
                    (
                        (TransfersTable.createdAt eq key.createdAt) and
                            (TransfersTable.transferId less key.transferId.value)
                    )
            )

    private fun before(key: GroupWalletKey): Op<Boolean> =
        (WalletOwnersTable.createdAt lessEq key.createdAt) and
            (
                (WalletOwnersTable.createdAt less key.createdAt) or
                    ((WalletOwnersTable.createdAt eq key.createdAt) and (WalletOwnersTable.walletId less key.walletId))
            )

    private fun createdIn(
        column: Column<Instant>,
        from: Instant,
        until: Instant,
    ): Op<Boolean> = (column greaterEq from) and (column less until)

    private fun confirmed(): Op<Boolean> = TransfersTable.state eq TransferState.CONFIRMED.name

    private fun collected(): Op<Boolean> = FeeLedgerTable.state eq FeeState.COLLECTED.name

    private fun isGroup(): Op<Boolean> = WalletOwnersTable.ownerType eq WalletOwnerType.GROUP.name
}

/**
 * `date_trunc('<unit>', created_at AT TIME ZONE 'UTC')::date`: the first UTC day of the period an instant falls in
 * (ISO weeks start on Monday). The unit comes from [ReportBucket] only, never from a caller's text, and the instant is
 * the DSL's own column, so nothing is spliced in.
 */
private class UtcPeriod(private val instant: Expression<Instant>, bucket: ReportBucket) :
    ExpressionWithColumnType<LocalDate>() {
    private val unit =
        when (bucket) {
            ReportBucket.DAY -> "day"
            ReportBucket.WEEK -> "week"
            ReportBucket.MONTH -> "month"
        }

    override val columnType: IColumnType<LocalDate> = JavaLocalDateColumnType()

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("date_trunc('$unit', (")
        queryBuilder.append(instant)
        queryBuilder.append(") AT TIME ZONE 'UTC')::date")
    }
}
