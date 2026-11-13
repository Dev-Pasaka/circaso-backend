package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.wallet.data.tables.PaymentHoldsTable
import com.circaso.wallet.domain.models.HoldReason
import com.circaso.wallet.domain.models.PaymentHold
import com.circaso.wallet.domain.ports.PaymentHolds
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Clock

/**
 * `wallet.payment_holds` (decision 2026-10-28, built 2026-11-11): one row per person, read with `expires_at > now`
 * from the app's clock.
 */
class PaymentHoldsImpl(private val database: Database, private val clock: Clock) : PaymentHolds {
    override suspend fun active(userId: UserId): PaymentHold? =
        database.tx("payment-holds.active") {
            PaymentHoldsTable
                .selectAll()
                .where {
                    (PaymentHoldsTable.userId eq userId.value) and (PaymentHoldsTable.expiresAt greater clock.instant())
                }.map { PaymentHold(it[PaymentHoldsTable.expiresAt], HoldReason.valueOf(it[PaymentHoldsTable.reason])) }
                .singleOrNull()
        }

    /**
     * `INSERT … ON CONFLICT DO NOTHING`, then for a person already on hold `UPDATE … WHERE expires_at < :until`: a hold
     * only ever gets longer, so a repeated or late event, or a shorter hold for another reason, never shortens one.
     * Both statements are atomic on their own, so two events placing at once can't lose the longer hold.
     */
    override suspend fun place(userId: UserId, hold: PaymentHold) {
        database.tx("payment-holds.place") {
            val inserted =
                PaymentHoldsTable
                    .insertIgnore {
                        it[this.userId] = userId.value
                        it[reason] = hold.reason.name
                        it[expiresAt] = hold.until
                    }.insertedCount
            if (inserted == 0) {
                PaymentHoldsTable.update({
                    (PaymentHoldsTable.userId eq userId.value) and (PaymentHoldsTable.expiresAt less hold.until)
                }) {
                    it[reason] = hold.reason.name
                    it[expiresAt] = hold.until
                }
            }
        }
    }
}
