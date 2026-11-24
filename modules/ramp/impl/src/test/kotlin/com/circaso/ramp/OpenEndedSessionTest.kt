package com.circaso.ramp

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.Email
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.OfflineReason
import com.circaso.ramp.domain.models.PaymentAccountCommand
import com.circaso.ramp.domain.models.ProfileCommand
import com.circaso.ramp.domain.models.TradingWindow
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.getOrNull
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Online until stopped (decision 2026-11-23): RM6 with no end time. It covers any payment window and never ends by
 * time; the merchant ends it (RM6 `DELETE`). A restart restores it (StayOnlineTest); a restart that can't (here, Redis
 * lost its working copy) ends it: RM2 says so at once, `ramp.end-sessions` ends it in the database with `RESTART` and
 * emails the merchant once.
 */
class OpenEndedSessionTest {
    private val ramp = Harness()
    private val pin = "482913"
    private val bob = PhoneNumber.parse("+254700000002")!!

    private fun <T> Resource<T>.error() = (this as Resource.Error).error

    /** The only merchant: signed in, approved, funded and connected; offline. */
    private suspend fun ready(): MerchantId {
        ramp.merchants.rows.remove(ramp.crynce.merchantId)
        ramp.sessions.keys.remove(ramp.crynce.merchantId)
        val id =
            ramp
                .verifyCodeFor("pay@crynce.co.ke")
                .merchant.merchant.merchantId
        ramp.applyAsMerchant(id, TESTNET, ProfileCommand("Crynce Ltd.", PhoneNumber.parse("+254712000111")!!))
        ramp.decideMerchant(id, approve = true, by = "staff")
        ramp.release()
        ramp.treasury.balances[ramp.merchantWallets.find(id, TESTNET)!!.custodyWalletId] = 100_000_000
        val payhero = CollectionCredentials("api-user", "api-pass", "911")
        assertIs<Resource.Success<*>>(ramp.storePayments(id, TESTNET, PaymentAccountCommand(payhero, pin)))
        return id
    }

    private suspend fun untilStopped(id: MerchantId) =
        ramp.goOnline(id, TESTNET, pin, TradingWindow.UntilStopped).getOrNull()!!

    private suspend fun presence(id: MerchantId) = ramp.getMerchant(id, TESTNET).getOrNull()!!.presence

    private fun takenOffline() = ramp.email.sent.filter { it.second == Email.MerchantTakenOffline }

    @Test
    fun `going online until stopped has no end time, and the database says so explicitly`() =
        runTest {
            val id = ready()

            val online = untilStopped(id)

            assertTrue(online.online && online.openEnded && online.keysHeld)
            assertNull(online.until, "no end time, not a far-off one")
            assertEquals(ramp.clock.instant(), online.since)
            val row = ramp.merchants.find(id)!!
            assertTrue(row.onlineOpenEnded)
            assertNull(row.onlineUntil)
            assertEquals(online, presence(id))
            assertEquals(Instant.MAX, ramp.sessions.marks[id])
        }

    @Test
    fun `an open-ended session takes orders whatever the payment window, and never ends by time`() =
        runTest {
            val id = ready()
            ramp.settingsRows.set(TESTNET) { it.copy(paymentWindowSeconds = 900) } // the longest staff may set
            ramp.clock.advance(11) // past the settings cache
            ramp.goOnline(id, TESTNET, pin, 15)
            assertEquals(
                RampError.NoMerchantAvailable,
                ramp.buy().error(),
                "a 15-minute window doesn't outlast a 15-minute payment window",
            )

            untilStopped(id)
            ramp.clock.advance(Duration.ofDays(30).seconds)
            ramp.endSessions()

            assertTrue(presence(id).online, "a month later, still online")
            assertEquals(listOf(id), ramp.sessions.renewed, "its mark is renewed while the node holds the keys")
            assertIs<Resource.Success<*>>(ramp.buy(ramp.user(bob.e164), bob))
        }

    @Test
    fun `going offline ends it as it ends a timed window`() =
        runTest {
            val id = ready()
            untilStopped(id)

            val offline = ramp.goOffline(id).getOrNull()!!

            assertFalse(offline.online)
            assertEquals(OfflineReason.STOPPED, offline.offlineReason)
            val row = ramp.merchants.find(id)!!
            assertFalse(row.onlineOpenEnded)
            assertNull(row.onlineSince)
            assertEquals(OfflineReason.STOPPED to ramp.clock.instant(), row.offlineReason to row.offlineAt)
            assertNull(ramp.sessions.of(id))
            assertEquals(RampError.NoMerchantAvailable, ramp.buy().error())
            assertEquals(OfflineReason.STOPPED, presence(id).offlineReason)
            assertTrue(takenOffline().isEmpty(), "only a restart emails")
        }

    @Test
    fun `a restart that lost the copy ends it - on RM2 at once, in the database within the minute, one email`() =
        runTest {
            val id = ready()
            untilStopped(id)

            ramp.restartLosingCopies()

            val seen = presence(id)
            assertFalse(seen.online || seen.keysHeld)
            assertEquals(OfflineReason.RESTART, seen.offlineReason)
            assertEquals(RampError.NoMerchantAvailable, ramp.buy().error(), "no keys, no orders")

            ramp.clock.advance(60)
            ramp.endSessions()

            val row = ramp.merchants.find(id)!!
            assertFalse(row.onlineOpenEnded)
            assertEquals(OfflineReason.RESTART to ramp.clock.instant(), row.offlineReason to row.offlineAt)
            assertEquals(listOf("pay@crynce.co.ke"), takenOffline().map { it.first })
            ramp.endSessions()
            assertEquals(1, takenOffline().size, "once per session")

            val again = untilStopped(id)
            assertTrue(again.online)
            assertNull(presence(id).offlineReason, "going online clears the reason")
        }

    @Test
    fun `a timed window is ended by such a restart too, and still ends by time as before`() =
        runTest {
            val id = ready()
            ramp.goOnline(id, TESTNET, pin, 60)
            ramp.restartLosingCopies()
            ramp.endSessions()
            assertEquals(OfflineReason.RESTART, ramp.merchants.find(id)!!.offlineReason)
            assertNull(ramp.merchants.find(id)!!.onlineUntil)

            ramp.goOnline(id, TESTNET, pin, 60)
            ramp.clock.advance(3_601)
            ramp.endSessions()

            assertEquals(OfflineReason.WINDOW_ENDED, ramp.merchants.find(id)!!.offlineReason)
            assertEquals(1, takenOffline().size, "a window that ran out sends nothing")
        }

    @Test
    fun `the sweep never ends a session opened after it read the row`() =
        runTest {
            val id = ready()
            untilStopped(id)
            val lost = ramp.merchants.find(id)!!
            ramp.restartLosingCopies()
            ramp.clock.advance(5)
            untilStopped(id)

            assertFalse(ramp.merchants.endLostSession(id, lost.onlineSince, ramp.clock.instant()))

            assertTrue(presence(id).online)
            assertNotNull(ramp.sessions.of(id))
        }
}
