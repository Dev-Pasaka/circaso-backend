package com.circaso.wallet

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.UserId
import com.circaso.wallet.data.repositories.PaymentHoldsImpl
import com.circaso.wallet.data.repositories.WalletExpiry
import com.circaso.wallet.di.walletDatabaseSpec
import com.circaso.wallet.domain.models.HoldReason
import com.circaso.wallet.domain.models.PaymentHold
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `wallet.payment_holds` (V11) on PostgreSQL: one row a person, only ever longer, hidden and purged once it ends. */
class PaymentHoldsDataTest {
    private val database: Database = TestPostgres.database(walletDatabaseSpec)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val holds = PaymentHoldsImpl(database, Clock.fixed(now, ZoneOffset.UTC))

    @BeforeTest
    fun clean() = TestPostgres.truncate("wallet")

    private fun hold(hours: Long, reason: HoldReason = HoldReason.VAULT_OPENED) =
        PaymentHold(now.plus(Duration.ofHours(hours)), reason)

    @Test
    fun `a hold is placed, made longer, and never shortened`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val bob = UserId.new()

            holds.place(amina, hold(24))
            holds.place(bob, hold(2))
            holds.place(amina, hold(48, HoldReason.RELINKED))
            holds.place(amina, hold(24))

            assertEquals(hold(48, HoldReason.RELINKED), holds.active(amina))
            assertEquals(hold(2), holds.active(bob))
        }

    @Test
    fun `a hold that has ended is not in force, and the purge deletes it`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val bob = UserId.new()
            holds.place(amina, PaymentHold(Instant.now().minusSeconds(60), HoldReason.VAULT_OPENED))
            holds.place(bob, hold(24))

            assertNull(holds.active(amina))
            WalletExpiry(database).run()

            assertNull(holds.active(amina))
            assertEquals(hold(24), holds.active(bob), "a hold still in force stays")
            assertEquals(
                1,
                TestPostgres.superuser { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM wallet.payment_holds").use { rows ->
                            rows.next()
                            rows.getInt(1)
                        }
                    }
                },
            )
        }
}
