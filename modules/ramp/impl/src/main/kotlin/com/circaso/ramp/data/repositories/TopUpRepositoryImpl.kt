package com.circaso.ramp.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.data.tables.TopUpsTable
import com.circaso.ramp.domain.models.TopUp
import com.circaso.ramp.domain.models.TopUpConfirmation
import com.circaso.ramp.domain.models.TopUpFailure
import com.circaso.ramp.domain.models.TopUpState
import com.circaso.ramp.domain.models.TopUpTarget
import com.circaso.ramp.domain.repositories.TopUpRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Instant
import java.util.UUID

/**
 * `ramp.topups`. `topups_open_key` allows one open top-up per merchant and wallet, so an insert that finds one adds
 * nothing; a move is `WHERE state IN (…)`, so two callers (the portal's read, the sweep, a callback) can't both close
 * the same top-up.
 */
class TopUpRepositoryImpl(private val database: Database) : TopUpRepository {
    override suspend fun insert(topUp: TopUp): Boolean =
        database.tx("topups.insert") {
            TopUpsTable
                .insertIgnore {
                    it[topUpId] = topUp.topUpId
                    it[merchantId] = topUp.merchantId.value
                    it[wallet] = topUp.target.name
                    it[provider] = topUp.provider
                    it[amountMinor] = topUp.amountMinor
                    it[payerPhone] = topUp.payer.e164
                    it[createdAt] = topUp.createdAt
                    it.write(topUp)
                }.insertedCount > 0
        }

    override suspend fun find(topUpId: UUID): TopUp? =
        database.tx("topups.find") {
            TopUpsTable
                .selectAll()
                .where { TopUpsTable.topUpId eq topUpId }
                .map { it.toTopUp() }
                .singleOrNull()
        }

    override suspend fun open(merchantId: MerchantId, target: TopUpTarget): TopUp? =
        database.tx("topups.open") {
            TopUpsTable
                .selectAll()
                .where {
                    (TopUpsTable.merchantId eq merchantId.value) and
                        (TopUpsTable.wallet eq target.name) and
                        (TopUpsTable.state inList OPEN_STATES)
                }.limit(1)
                .map { it.toTopUp() }
                .singleOrNull()
        }

    override suspend fun recent(merchantId: MerchantId, limit: Int): List<TopUp> =
        database.tx("topups.recent") {
            TopUpsTable
                .selectAll()
                .where { TopUpsTable.merchantId eq merchantId.value }
                .orderBy(TopUpsTable.createdAt to SortOrder.DESC, TopUpsTable.topUpId to SortOrder.DESC)
                .limit(limit)
                .map { it.toTopUp() }
                .toList()
        }

    override suspend fun move(topUp: TopUp, from: Set<TopUpState>): Boolean =
        database.tx("topups.move") {
            TopUpsTable.update({
                (TopUpsTable.topUpId eq topUp.topUpId) and (TopUpsTable.state inList from.map { it.name })
            }) { it.write(topUp) } > 0
        }

    override suspend fun stale(before: Instant, limit: Int): List<TopUp> =
        database.tx("topups.stale") {
            TopUpsTable
                .selectAll()
                .where { (TopUpsTable.state inList OPEN_STATES) and (TopUpsTable.updatedAt less before) }
                .orderBy(TopUpsTable.updatedAt to SortOrder.ASC)
                .limit(limit)
                .map { it.toTopUp() }
                .toList()
        }

    override suspend fun hasOpen(merchantId: MerchantId): Boolean =
        database.tx("topups.has-open") {
            TopUpsTable
                .selectAll()
                .where { (TopUpsTable.merchantId eq merchantId.value) and (TopUpsTable.state inList OPEN_STATES) }
                .limit(1)
                .map { true }
                .singleOrNull() ?: false
        }

    /** The columns a top-up's progress changes. */
    private fun UpdateBuilder<*>.write(topUp: TopUp) {
        this[TopUpsTable.state] = topUp.state.name
        this[TopUpsTable.failure] = topUp.failure?.name
        this[TopUpsTable.outcome] = topUp.outcome
        this[TopUpsTable.providerReference] = topUp.providerReference
        this[TopUpsTable.checkoutId] = topUp.checkoutId
        this[TopUpsTable.receipt] = topUp.receipt
        this[TopUpsTable.confirmedBy] = topUp.confirmedBy?.name
        this[TopUpsTable.balanceBeforeMinor] = topUp.balanceBeforeMinor
        this[TopUpsTable.balanceAfterMinor] = topUp.balanceAfterMinor
        this[TopUpsTable.updatedAt] = topUp.updatedAt
        this[TopUpsTable.closedAt] = topUp.closedAt
    }

    private fun ResultRow.toTopUp() =
        TopUp(
            topUpId = this[TopUpsTable.topUpId],
            merchantId = MerchantId(this[TopUpsTable.merchantId]),
            target = TopUpTarget.valueOf(this[TopUpsTable.wallet]),
            provider = this[TopUpsTable.provider],
            amountMinor = this[TopUpsTable.amountMinor],
            payer = payer(this[TopUpsTable.payerPhone]),
            state = TopUpState.valueOf(this[TopUpsTable.state]),
            failure = this[TopUpsTable.failure]?.let { TopUpFailure.valueOf(it) },
            outcome = this[TopUpsTable.outcome],
            providerReference = this[TopUpsTable.providerReference],
            checkoutId = this[TopUpsTable.checkoutId],
            receipt = this[TopUpsTable.receipt],
            confirmedBy = this[TopUpsTable.confirmedBy]?.let { TopUpConfirmation.valueOf(it) },
            balanceBeforeMinor = this[TopUpsTable.balanceBeforeMinor],
            balanceAfterMinor = this[TopUpsTable.balanceAfterMinor],
            createdAt = this[TopUpsTable.createdAt],
            updatedAt = this[TopUpsTable.updatedAt],
            closedAt = this[TopUpsTable.closedAt],
        )

    private companion object {
        val OPEN_STATES = TopUpState.entries.filter { it.isOpen }.map { it.name }
    }
}
