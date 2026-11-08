package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.api.WalletOwnerType
import com.circaso.wallet.data.tables.FeeLedgerTable
import com.circaso.wallet.data.tables.FeeQuotesTable
import com.circaso.wallet.data.tables.FeeRulesTable
import com.circaso.wallet.data.tables.LimitRulesTable
import com.circaso.wallet.domain.models.Fee
import com.circaso.wallet.domain.models.FeeEntry
import com.circaso.wallet.domain.models.FeeKind
import com.circaso.wallet.domain.models.FeeParties
import com.circaso.wallet.domain.models.FeeQuote
import com.circaso.wallet.domain.models.FeeRule
import com.circaso.wallet.domain.models.FeeState
import com.circaso.wallet.domain.models.GasEstimate
import com.circaso.wallet.domain.models.GasSample
import com.circaso.wallet.domain.models.GasSource
import com.circaso.wallet.domain.models.LimitRule
import com.circaso.wallet.domain.models.NoFeeReason
import com.circaso.wallet.domain.repositories.FeeLedgerRepository
import com.circaso.wallet.domain.repositories.FeeQuoteRepository
import com.circaso.wallet.domain.repositories.RuleRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Dollars as the rules store them, in micro-dollars (USDC's minor units) as the domain counts them. */
private fun BigDecimal.toMicro(): Long = movePointRight(MICRO).setScale(0, RoundingMode.HALF_UP).longValueExact()

private const val MICRO = 6

/** Enough recent quotes to find every chain's last estimate. */
private const val LATEST_ESTIMATE_ROWS = 500

private fun ownerType(text: String?): WalletOwnerType? = text?.let { WalletOwnerType.valueOf(it) }

private fun asset(text: String?): String? = text?.trim()?.uppercase()?.ifEmpty { null }

/** A `fee_rules` row as the domain reads it. */
internal fun ResultRow.toFeeRule() =
    FeeRule(
        ruleId = this[FeeRulesTable.ruleId],
        version = this[FeeRulesTable.version],
        kind = this[FeeRulesTable.kind]?.let { FeeKind.valueOf(it) },
        senderType = ownerType(this[FeeRulesTable.senderType]),
        receiverType = ownerType(this[FeeRulesTable.receiverType]),
        blockchain = this[FeeRulesTable.blockchain],
        asset = asset(this[FeeRulesTable.asset]),
        walletId = this[FeeRulesTable.walletId]?.let(::WalletId),
        rateBps = this[FeeRulesTable.rateBps],
        capBaseMinor = this[FeeRulesTable.capBaseUsd].toMicro(),
        minFeeMinor = this[FeeRulesTable.minFeeUsd]?.toMicro(),
        gasShareMaxBps = this[FeeRulesTable.gasShareMaxBps],
        gasMarginBps = this[FeeRulesTable.gasMarginBps],
        effectiveFrom = this[FeeRulesTable.effectiveFrom],
        effectiveTo = this[FeeRulesTable.effectiveTo],
        note = this[FeeRulesTable.note],
        createdAt = this[FeeRulesTable.createdAt],
    )

/** A `limit_rules` row as the domain reads it. */
internal fun ResultRow.toLimitRule() =
    LimitRule(
        ruleId = this[LimitRulesTable.ruleId],
        version = this[LimitRulesTable.version],
        ownerType = ownerType(this[LimitRulesTable.ownerType]),
        walletId = this[LimitRulesTable.walletId]?.let(::WalletId),
        asset = asset(this[LimitRulesTable.asset]),
        perPaymentMaxMinor = this[LimitRulesTable.perPaymentMaxUsd]?.toMicro(),
        dailyMaxMinor = this[LimitRulesTable.dailyMaxUsd]?.toMicro(),
        window = Duration.ofHours(this[LimitRulesTable.windowHours].toLong()),
        effectiveFrom = this[LimitRulesTable.effectiveFrom],
        effectiveTo = this[LimitRulesTable.effectiveTo],
        note = this[LimitRulesTable.note],
        createdAt = this[LimitRulesTable.createdAt],
    )

/** `fee_rules` and `limit_rules`: small tables read whole; the domain picks the versions in force. */
class RuleRepositoryImpl(private val database: Database) : RuleRepository {
    override suspend fun feeRules(): List<FeeRule> =
        database.tx("fee-rules.all") {
            FeeRulesTable.selectAll().map { it.toFeeRule() }.toList()
        }

    override suspend fun limitRules(): List<LimitRule> =
        database.tx("limit-rules.all") {
            LimitRulesTable.selectAll().map { it.toLimitRule() }.toList()
        }
}

/** `fee_quotes`, by its id; `wallet.purge-expired` drops them once they have run out. */
class FeeQuoteRepositoryImpl(private val database: Database) : FeeQuoteRepository {
    override suspend fun insert(quote: FeeQuote) {
        database.tx("fee-quotes.insert") {
            FeeQuotesTable.insert {
                val fee = quote.fee
                it[quoteId] = quote.quoteId
                it[holderId] = quote.holder.value
                it[signerId] = quote.signerId.value
                it[blockchain] = quote.blockchain
                it[payeeKey] = quote.payeeKey
                it[amountMinor] = quote.amount.amountMinor.toBigDecimal()
                it[asset] = quote.amount.asset.symbol
                it[assetDecimals] =
                    quote.amount.asset.decimals
                        .toShort()
                it[feeMinor] = fee.amountMinor.toBigDecimal()
                it[feeUsdMinor] = fee.usdMinor
                it[usdPrice] = fee.usdPrice
                it[rateBps] = fee.rateBps
                it[minimum] = fee.minimum
                it[ruleId] = fee.ruleId
                it[ruleVersion] = fee.ruleVersion
                it[gasMinor] = fee.gasMinor
                it[gasShareMaxBps] = fee.gasShareMaxBps
                it[gasSource] = fee.gasSource?.name
                it[kind] = fee.parties.kind.name
                it[senderType] = fee.parties.senderType.name
                it[receiverType] = fee.parties.receiverType?.name
                it[feeAddress] = fee.feeAddress
                it[noFeeReason] = fee.reason?.name
                it[createdAt] = quote.createdAt
                it[expiresAt] = quote.expiresAt
            }
        }
    }

    override suspend fun find(quoteId: UUID): FeeQuote? =
        database.tx("fee-quotes.find") {
            FeeQuotesTable
                .selectAll()
                .where { FeeQuotesTable.quoteId eq quoteId }
                .map { it.toQuote() }
                .singleOrNull()
        }

    override suspend fun latestEstimates(since: Instant): List<GasEstimate> =
        database.tx("fee-quotes.latest-estimates") {
            FeeQuotesTable
                .select(FeeQuotesTable.blockchain, FeeQuotesTable.gasMinor, FeeQuotesTable.createdAt)
                .where {
                    (FeeQuotesTable.createdAt greaterEq since) and FeeQuotesTable.gasMinor.isNotNull() and
                        (FeeQuotesTable.gasSource.isNull() or (FeeQuotesTable.gasSource eq GasSource.ESTIMATE.name))
                }.orderBy(FeeQuotesTable.createdAt, SortOrder.DESC)
                .limit(LATEST_ESTIMATE_ROWS)
                .map {
                    GasEstimate(
                        it[FeeQuotesTable.blockchain],
                        it[FeeQuotesTable.gasMinor] ?: 0,
                        it[FeeQuotesTable.createdAt],
                    )
                }.toList()
                .distinctBy { it.blockchain }
        }

    private fun ResultRow.toQuote(): FeeQuote {
        val holder = UserId(this[FeeQuotesTable.holderId])
        val money =
            moneyOf(this[FeeQuotesTable.amountMinor], this[FeeQuotesTable.asset], this[FeeQuotesTable.assetDecimals])
        val parties =
            FeeParties(
                kind = FeeKind.valueOf(this[FeeQuotesTable.kind]),
                senderType = WalletOwnerType.valueOf(this[FeeQuotesTable.senderType]),
                receiverType = ownerType(this[FeeQuotesTable.receiverType]),
                blockchain = this[FeeQuotesTable.blockchain],
                asset = money.asset,
                walletId = WalletId(holder.value),
            )
        return FeeQuote(
            quoteId = this[FeeQuotesTable.quoteId],
            holder = holder,
            signerId = UserId(this[FeeQuotesTable.signerId]),
            blockchain = this[FeeQuotesTable.blockchain],
            payeeKey = this[FeeQuotesTable.payeeKey],
            amount = money,
            fee =
                Fee(
                    amountMinor = this[FeeQuotesTable.feeMinor].toBigIntegerExact(),
                    usdMinor = this[FeeQuotesTable.feeUsdMinor],
                    usdPrice = this[FeeQuotesTable.usdPrice].stripTrailingZeros(),
                    rateBps = this[FeeQuotesTable.rateBps],
                    minimum = this[FeeQuotesTable.minimum],
                    ruleId = this[FeeQuotesTable.ruleId],
                    ruleVersion = this[FeeQuotesTable.ruleVersion],
                    gasMinor = this[FeeQuotesTable.gasMinor],
                    gasShareMaxBps = this[FeeQuotesTable.gasShareMaxBps],
                    gasSource = this[FeeQuotesTable.gasSource]?.let { GasSource.valueOf(it) },
                    parties = parties,
                    feeAddress = this[FeeQuotesTable.feeAddress],
                    reason = this[FeeQuotesTable.noFeeReason]?.let { NoFeeReason.valueOf(it) },
                ),
            createdAt = this[FeeQuotesTable.createdAt],
            expiresAt = this[FeeQuotesTable.expiresAt],
        )
    }
}

/** `fee_ledger`: one row per transfer, moved by a guard like the transfer's, so a repeated update changes nothing. */
class FeeLedgerRepositoryImpl(private val database: Database) : FeeLedgerRepository {
    override suspend fun insert(entry: FeeEntry) {
        database.tx("fee-ledger.insert") {
            FeeLedgerTable.insert {
                val fee = entry.fee
                it[transferId] = entry.transferId.value
                it[holderId] = entry.holder.value
                it[blockchain] = fee.parties.blockchain
                it[asset] = entry.amount.asset.symbol
                it[assetDecimals] =
                    entry.amount.asset.decimals
                        .toShort()
                it[amountMinor] = entry.amount.amountMinor.toBigDecimal()
                it[feeMinor] = if (fee.charged) fee.amountMinor.toBigDecimal() else java.math.BigDecimal.ZERO
                it[feeUsdMinor] = if (fee.charged) fee.usdMinor else 0
                it[usdPrice] = fee.usdPrice
                it[rateBps] = fee.rateBps
                it[minimum] = fee.minimum
                it[ruleId] = fee.ruleId
                it[ruleVersion] = fee.ruleVersion
                it[kind] = fee.parties.kind.name
                it[senderType] = fee.parties.senderType.name
                it[receiverType] = fee.parties.receiverType?.name
                it[feeAddress] = fee.feeAddress
                it[noFeeReason] = fee.reason?.name
                it[gasEstimateMinor] = fee.gasMinor
                it[gasShareMaxBps] = fee.gasShareMaxBps
                it[gasSource] = fee.gasSource?.name
                it[state] = entry.state.name
                it[createdAt] = entry.createdAt
                it[updatedAt] = entry.createdAt
            }
        }
    }

    override suspend fun settle(
        transferId: TransferId,
        state: FeeState,
        txHash: String?,
        actualGasNative: String?,
        actualGasMinor: Long?,
        at: Instant,
    ): FeeEntry? {
        // Collected from a quote, or from void when an expired transfer turns out signed; void only from a quote.
        val from =
            when (state) {
                FeeState.COLLECTED -> listOf(FeeState.QUOTED, FeeState.VOID)
                FeeState.VOID -> listOf(FeeState.QUOTED)
                FeeState.QUOTED -> return null
            }.map { it.name }
        return database.tx("fee-ledger.settle") {
            FeeLedgerTable
                .updateReturning(where = {
                    (FeeLedgerTable.transferId eq transferId.value) and (FeeLedgerTable.state inList from)
                }) {
                    it[FeeLedgerTable.state] = state.name
                    it[updatedAt] = at
                    txHash?.let { value -> it[FeeLedgerTable.txHash] = value }
                    actualGasNative?.let { value -> it[FeeLedgerTable.actualGasNative] = value }
                    actualGasMinor?.let { value -> it[FeeLedgerTable.actualGasMinor] = value }
                }.map { it.toEntry() }
                .singleOrNull()
        }
    }

    override suspend fun recentActualGas(since: Instant, limit: Int): List<GasSample> =
        database.tx("fee-ledger.recent-gas") {
            FeeLedgerTable
                .select(
                    FeeLedgerTable.blockchain,
                    FeeLedgerTable.asset,
                    FeeLedgerTable.actualGasMinor,
                    FeeLedgerTable.createdAt,
                ).where {
                    (FeeLedgerTable.createdAt greaterEq since) and
                        (FeeLedgerTable.state eq FeeState.COLLECTED.name) and
                        FeeLedgerTable.actualGasMinor.isNotNull()
                }.orderBy(FeeLedgerTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map {
                    GasSample(
                        it[FeeLedgerTable.blockchain],
                        it[FeeLedgerTable.asset],
                        it[FeeLedgerTable.actualGasMinor] ?: 0,
                        it[FeeLedgerTable.createdAt],
                    )
                }.toList()
        }

    private fun ResultRow.toEntry(): FeeEntry {
        val holder = UserId(this[FeeLedgerTable.holderId])
        val money =
            moneyOf(this[FeeLedgerTable.amountMinor], this[FeeLedgerTable.asset], this[FeeLedgerTable.assetDecimals])
        return FeeEntry(
            transferId = TransferId(this[FeeLedgerTable.transferId]),
            holder = holder,
            amount = money,
            fee =
                Fee(
                    amountMinor = this[FeeLedgerTable.feeMinor].toBigIntegerExact(),
                    usdMinor = this[FeeLedgerTable.feeUsdMinor],
                    usdPrice = this[FeeLedgerTable.usdPrice].stripTrailingZeros(),
                    rateBps = this[FeeLedgerTable.rateBps],
                    minimum = this[FeeLedgerTable.minimum],
                    ruleId = this[FeeLedgerTable.ruleId],
                    ruleVersion = this[FeeLedgerTable.ruleVersion],
                    gasMinor = this[FeeLedgerTable.gasEstimateMinor],
                    gasShareMaxBps = this[FeeLedgerTable.gasShareMaxBps],
                    gasSource = this[FeeLedgerTable.gasSource]?.let { GasSource.valueOf(it) },
                    parties =
                        FeeParties(
                            kind = FeeKind.valueOf(this[FeeLedgerTable.kind]),
                            senderType = WalletOwnerType.valueOf(this[FeeLedgerTable.senderType]),
                            receiverType = ownerType(this[FeeLedgerTable.receiverType]),
                            blockchain = this[FeeLedgerTable.blockchain],
                            asset = money.asset,
                            walletId = WalletId(holder.value),
                        ),
                    feeAddress = this[FeeLedgerTable.feeAddress],
                    reason = this[FeeLedgerTable.noFeeReason]?.let { NoFeeReason.valueOf(it) },
                ),
            state = FeeState.valueOf(this[FeeLedgerTable.state]),
            createdAt = this[FeeLedgerTable.createdAt],
        )
    }
}
