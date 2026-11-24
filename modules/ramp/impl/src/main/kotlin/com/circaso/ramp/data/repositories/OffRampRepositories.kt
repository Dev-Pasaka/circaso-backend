package com.circaso.ramp.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.data.tables.PayoutHoldsTable
import com.circaso.ramp.data.tables.TestPayoutsTable
import com.circaso.ramp.domain.models.BalanceRead
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.PayoutFee
import com.circaso.ramp.domain.models.TestPayout
import com.circaso.ramp.domain.models.TestPayoutState
import com.circaso.ramp.domain.repositories.PayoutHoldRepository
import com.circaso.ramp.domain.repositories.TestPayoutRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Instant
import java.util.UUID

private const val OPEN = "OPEN"

/**
 * `ramp.payout_holds`: one per sale, inserted with the order under the merchant's row lock and closed once, by
 * `WHERE state = 'OPEN'`, so only the caller that closes it writes what follows.
 */
class PayoutHoldRepositoryImpl(private val database: Database) : PayoutHoldRepository {
    override suspend fun insert(order: Order, at: Instant) {
        database.tx("payout-holds.insert") {
            PayoutHoldsTable.insertIgnore {
                it[orderId] = order.orderId.value
                it[merchantId] = order.merchantId.value
                it[amountMinor] = order.payoutHoldMinor
                it[currency] = order.fiat.currency
                it[state] = OPEN
                it[createdAt] = at
            }
        }
    }

    override suspend fun openTotal(merchantId: MerchantId): Long =
        database.tx("payout-holds.open-total") {
            val total = PayoutHoldsTable.amountMinor.sum()
            PayoutHoldsTable
                .select(total)
                .where { (PayoutHoldsTable.merchantId eq merchantId.value) and (PayoutHoldsTable.state eq OPEN) }
                .single()[total] ?: 0
        }

    override suspend fun release(orderId: RampOrderId, at: Instant): Boolean = close(orderId, "RELEASED", at)

    override suspend fun consume(orderId: RampOrderId, at: Instant): Boolean = close(orderId, "CONSUMED", at)

    private suspend fun close(
        orderId: RampOrderId,
        to: String,
        at: Instant,
    ): Boolean =
        database.tx("payout-holds.close") {
            PayoutHoldsTable.update({
                (PayoutHoldsTable.orderId eq orderId.value) and (PayoutHoldsTable.state eq OPEN)
            }) {
                it[state] = to
                it[closedAt] = at
            } > 0
        }
}

/**
 * `ramp.test_payouts`. `test_payouts_open_key` allows one test in flight per merchant, so an insert that finds one
 * adds nothing; a move is `WHERE state IN (…)`, so two callers can't both record an outcome.
 */
class TestPayoutRepositoryImpl(private val database: Database) : TestPayoutRepository {
    override suspend fun insert(test: TestPayout): Boolean =
        database.tx("test-payouts.insert") {
            TestPayoutsTable
                .insertIgnore {
                    it[testId] = test.testId
                    it[merchantId] = test.merchantId.value
                    it[provider] = test.provider
                    it[amountMinor] = test.amountMinor
                    it[payeePhone] = test.payee.e164
                    it[createdAt] = test.createdAt
                    it.write(test)
                }.insertedCount > 0
        }

    override suspend fun find(testId: UUID): TestPayout? =
        database.tx("test-payouts.find") {
            TestPayoutsTable
                .selectAll()
                .where { TestPayoutsTable.testId eq testId }
                .map { it.toTest() }
                .singleOrNull()
        }

    override suspend fun latest(merchantId: MerchantId): TestPayout? =
        database.tx("test-payouts.latest") {
            TestPayoutsTable
                .selectAll()
                .where { TestPayoutsTable.merchantId eq merchantId.value }
                .orderBy(TestPayoutsTable.createdAt to SortOrder.DESC, TestPayoutsTable.testId to SortOrder.DESC)
                .limit(1)
                .map { it.toTest() }
                .singleOrNull()
        }

    override suspend fun move(test: TestPayout, from: Set<TestPayoutState>): Boolean =
        database.tx("test-payouts.move") {
            TestPayoutsTable.update({
                (TestPayoutsTable.testId eq test.testId) and (TestPayoutsTable.state inList from.map { it.name })
            }) { it.write(test) } > 0
        }

    override suspend fun open(before: Instant, limit: Int): List<TestPayout> =
        database.tx("test-payouts.open") {
            TestPayoutsTable
                .selectAll()
                .where {
                    (TestPayoutsTable.state inList OPEN_STATES) and (TestPayoutsTable.updatedAt less before)
                }.orderBy(TestPayoutsTable.updatedAt to SortOrder.ASC)
                .limit(limit)
                .map { it.toTest() }
                .toList()
        }

    /** The columns a test's outcome changes. */
    private fun UpdateBuilder<*>.write(test: TestPayout) {
        this[TestPayoutsTable.state] = test.state.name
        this[TestPayoutsTable.outcome] = test.outcome
        this[TestPayoutsTable.providerReference] = test.providerReference
        this[TestPayoutsTable.secondaryReference] = test.secondaryReference
        this[TestPayoutsTable.receipt] = test.receipt
        this[TestPayoutsTable.paidAmountMinor] = test.paidAmountMinor
        this[TestPayoutsTable.providerFeeMinor] = test.providerFee?.feeMinor
        this[TestPayoutsTable.providerFeeSource] = test.providerFee?.source
        this[TestPayoutsTable.balanceBeforeMinor] = test.balanceBefore?.amountMinor
        this[TestPayoutsTable.balanceBeforeSource] = test.balanceBefore?.source
        this[TestPayoutsTable.balanceAfterMinor] = test.balanceAfter?.amountMinor
        this[TestPayoutsTable.balanceAfterSource] = test.balanceAfter?.source
        this[TestPayoutsTable.serviceBeforeMinor] = test.serviceBefore?.amountMinor
        this[TestPayoutsTable.serviceBeforeSource] = test.serviceBefore?.source
        this[TestPayoutsTable.serviceAfterMinor] = test.serviceAfter?.amountMinor
        this[TestPayoutsTable.serviceAfterSource] = test.serviceAfter?.source
        this[TestPayoutsTable.updatedAt] = test.updatedAt
    }

    private fun ResultRow.toTest() =
        TestPayout(
            testId = this[TestPayoutsTable.testId],
            merchantId = MerchantId(this[TestPayoutsTable.merchantId]),
            provider = this[TestPayoutsTable.provider],
            amountMinor = this[TestPayoutsTable.amountMinor],
            payee = payer(this[TestPayoutsTable.payeePhone]),
            state = TestPayoutState.valueOf(this[TestPayoutsTable.state]),
            outcome = this[TestPayoutsTable.outcome],
            providerReference = this[TestPayoutsTable.providerReference],
            secondaryReference = this[TestPayoutsTable.secondaryReference],
            receipt = this[TestPayoutsTable.receipt],
            paidAmountMinor = this[TestPayoutsTable.paidAmountMinor],
            providerFee =
                this[TestPayoutsTable.providerFeeMinor]?.let { fee ->
                    PayoutFee(fee, this[TestPayoutsTable.providerFeeSource].orEmpty())
                },
            balanceBefore = read(this[TestPayoutsTable.balanceBeforeMinor], this[TestPayoutsTable.balanceBeforeSource]),
            balanceAfter = read(this[TestPayoutsTable.balanceAfterMinor], this[TestPayoutsTable.balanceAfterSource]),
            serviceBefore = read(this[TestPayoutsTable.serviceBeforeMinor], this[TestPayoutsTable.serviceBeforeSource]),
            serviceAfter = read(this[TestPayoutsTable.serviceAfterMinor], this[TestPayoutsTable.serviceAfterSource]),
            createdAt = this[TestPayoutsTable.createdAt],
            updatedAt = this[TestPayoutsTable.updatedAt],
        )

    private fun read(amount: Long?, source: String?): BalanceRead? = amount?.let { BalanceRead(it, source.orEmpty()) }

    private companion object {
        val OPEN_STATES = TestPayoutState.entries.filter { it.isOpen }.map { it.name }
    }
}
