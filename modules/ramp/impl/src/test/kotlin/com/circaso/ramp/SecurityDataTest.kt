package com.circaso.ramp

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.data.repositories.MerchantAlertRepositoryImpl
import com.circaso.ramp.data.repositories.MerchantSecurityRepositoryImpl
import com.circaso.ramp.data.repositories.PasskeyRepositoryImpl
import com.circaso.ramp.data.repositories.SecuritySettingsRepositoryImpl
import com.circaso.ramp.data.repositories.WithdrawalDestinationRepositoryImpl
import com.circaso.ramp.data.repositories.WithdrawalRepositoryImpl
import com.circaso.ramp.di.rampDatabaseSpec
import com.circaso.ramp.domain.models.AlertFacts
import com.circaso.ramp.domain.models.AlertKind
import com.circaso.ramp.domain.models.AlertState
import com.circaso.ramp.domain.models.MerchantAlert
import com.circaso.ramp.domain.models.Passkey
import com.circaso.ramp.domain.models.SecuritySettings
import com.circaso.ramp.domain.models.Withdrawal
import com.circaso.ramp.domain.models.WithdrawalDestination
import com.circaso.ramp.domain.models.WithdrawalState
import com.circaso.shared.Uuid7
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Merchant security on PostgreSQL (V16, 2026-11-24): passkeys (one row per credential), the lock and email choices,
 * alerts (one per dedupe key, a lock link used once), the settings' version check, and the withdrawal moves the lock
 * depends on: a claim before sending, a cancel of unclaimed ones only, frozen cooling addresses.
 */
class SecurityDataTest {
    private val database: Database = TestPostgres.database(rampDatabaseSpec)
    private val passkeys = PasskeyRepositoryImpl(database)
    private val security = MerchantSecurityRepositoryImpl(database)
    private val alerts = MerchantAlertRepositoryImpl(database)
    private val settings = SecuritySettingsRepositoryImpl(database)
    private val destinations = WithdrawalDestinationRepositoryImpl(database)
    private val withdrawals = WithdrawalRepositoryImpl(database)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val merchant = MerchantId.new()

    @BeforeTest
    fun clean() = runBlocking<Unit> { TestPostgres.truncate("ramp") }

    private fun passkey(credentialId: ByteArray = Random.nextBytes(16)) =
        Passkey(
            Uuid7.generate(now),
            merchant,
            credentialId,
            byteArrayOf(1, 2, 3),
            0,
            "Mac",
            listOf("internal", "hybrid"),
            true,
            false,
            now,
            null,
        )

    private fun alert(dedupe: String, kind: AlertKind = AlertKind.ADDRESS_ADDED) =
        MerchantAlert(
            alertId = Uuid7.generate(now),
            merchantId = merchant,
            kind = kind,
            dedupeKey = dedupe,
            facts = AlertFacts(time = "24 Nov 2026, 10:00 EAT", label = "Ledger", address = "0xabc"),
            state = AlertState.PENDING,
            attempts = 0,
            nextAttemptAt = now,
            createdAt = now,
            linkExpiresAt = now.plusSeconds(3_600),
        )

    @Test
    fun `passkeys round trip, one row per credential, uses recorded, deleted`() =
        runBlocking<Unit> {
            val key = passkey()
            assertTrue(passkeys.insert(key))
            assertFalse(passkeys.insert(passkey(key.credentialId)), "the same credential twice")
            val found = passkeys.byCredentialId(key.credentialId)!!
            assertEquals(listOf("internal", "hybrid") to "Mac", found.transports to found.label)
            passkeys.used(key.passkeyId, 7, true, now)
            val used = passkeys.find(key.passkeyId)!!
            assertEquals(Triple(7L, true, now), Triple(used.signCount, used.backedUp, used.lastUsedAt))
            assertEquals(1, passkeys.count(merchant))
            assertTrue(passkeys.delete(key.passkeyId))
            assertEquals(0, passkeys.count(merchant))
        }

    @Test
    fun `the lock is set once and lifted once, beside the email choices`() =
        runBlocking<Unit> {
            assertFalse(security.find(merchant).locked)
            security.setChoices(merchant, orderEmails = false, dailySummary = true, now)
            val alertId = Uuid7.generate(now)
            assertTrue(security.lock(merchant, alertId, 2, now))
            assertFalse(security.lock(merchant, null, 0, now), "already locked")
            val locked = security.find(merchant)
            assertEquals(Triple(true, alertId, 2), Triple(locked.locked, locked.lockedAlertId, locked.lockedCancelled))
            assertEquals(false to true, locked.orderEmails to locked.dailySummary)
            assertEquals(listOf(merchant), security.withDailySummary())
            assertTrue(security.unlock(merchant, "staff", "spoke to them", now))
            assertFalse(security.unlock(merchant, "staff", "again", now))
            assertEquals("staff", security.find(merchant).unlockedBy)
        }

    @Test
    fun `an alert is one row per dedupe key, due until sent, and its lock link is used once`() =
        runBlocking<Unit> {
            val first = alert("address-added-1")
            assertTrue(alerts.insert(first))
            assertFalse(alerts.insert(alert("address-added-1")), "the same event again")
            assertEquals(listOf(first.alertId), alerts.due(now, 10).map { it.alertId })
            assertEquals("Ledger", alerts.find(first.alertId)!!.facts.label)
            alerts.retry(first.alertId, 1, now.plusSeconds(60), "unavailable")
            assertTrue(alerts.due(now, 10).isEmpty())
            alerts.sent(first.alertId, now)
            assertEquals(AlertState.SENT, alerts.find(first.alertId)!!.state)
            assertTrue(alerts.useLink(first.alertId, now))
            assertFalse(alerts.useLink(first.alertId, now), "once")
            assertEquals(1, alerts.recent(merchant, 10).size)
        }

    @Test
    fun `the settings start off and change only from the version in force`() =
        runBlocking<Unit> {
            val start = settings.get()
            assertFalse(start.merchantPasskeyRequired)
            assertTrue(settings.update(SecuritySettings(true, updatedBy = "s", reason = "r"), start.version, now))
            assertFalse(settings.update(SecuritySettings(false), start.version, now), "an old version")
            val after = settings.get()
            assertEquals(true to start.version + 1, after.merchantPasskeyRequired to after.version)
        }

    @Test
    fun `a claimed withdrawal can't be cancelled, an unclaimed one can, and cooling addresses freeze`() =
        runBlocking<Unit> {
            val cooling =
                WithdrawalDestination(
                    Uuid7.generate(now),
                    merchant,
                    TESTNET,
                    "BASE-SEPOLIA",
                    "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
                    "L",
                    now,
                    now.plusSeconds(86_400),
                )
            val ready =
                cooling.copy(
                    destinationId = Uuid7.generate(now),
                    address = "0xab5801a7d398351b8be11c439e05c5b3259aec9b",
                    usableAt = now,
                )
            destinations.insert(cooling)
            destinations.insert(ready)

            fun w(key: String) =
                Withdrawal(
                    Uuid7.generate(now),
                    merchant,
                    TESTNET,
                    "BASE-SEPOLIA",
                    ready.destinationId,
                    ready.address,
                    "L",
                    1_000_000,
                    WithdrawalState.REQUESTED,
                    key,
                    createdAt = now,
                    updatedAt = now,
                )
            val claimed = w("a")
            val unclaimed = w("b")
            withdrawals.insert(claimed)
            withdrawals.insert(unclaimed)

            assertTrue(withdrawals.claim(claimed.withdrawalId, now))
            assertTrue(withdrawals.claim(claimed.withdrawalId, now), "claimed already: still its to send")
            val gone = withdrawals.cancelUnclaimed(merchant, null, "locked", now)
            assertEquals(listOf(unclaimed.withdrawalId), gone.map { it.withdrawalId })
            assertEquals(WithdrawalState.CANCELLED, withdrawals.find(unclaimed.withdrawalId)!!.state)
            assertFalse(withdrawals.claim(unclaimed.withdrawalId, now), "a cancelled one is never claimed")
            assertNotNull(withdrawals.find(claimed.withdrawalId)!!.sendClaimedAt)
            assertEquals(
                1_000_000,
                withdrawals.totalSince(merchant, TESTNET, now.minusSeconds(1)),
                "cancelled not counted",
            )

            assertEquals(1, destinations.freezeCooling(merchant, now))
            assertNotNull(destinations.find(cooling.destinationId)!!.frozenAt)
            assertNull(destinations.find(ready.destinationId)!!.frozenAt)

            assertTrue(withdrawals.sent(claimed.withdrawalId, "tx-1", now))
            assertTrue(withdrawals.noteHash(claimed.withdrawalId, "0xh", now))
            assertFalse(withdrawals.noteHash(claimed.withdrawalId, "0xh", now), "the first time only")
        }
}
