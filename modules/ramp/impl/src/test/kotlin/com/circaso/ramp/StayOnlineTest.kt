package com.circaso.ramp

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.Email
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.OfflineReason
import com.circaso.ramp.domain.models.PaymentAccountCommand
import com.circaso.ramp.domain.models.ProfileCommand
import com.circaso.ramp.domain.models.RestoreOutcome
import com.circaso.ramp.domain.models.SessionTally
import com.circaso.ramp.domain.models.TradingWindow
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.support.SessionRestorer
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.getOrNull
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Merchants stay online across restarts and deploys (decision 2026-11-24): going online leaves an encrypted working
 * copy of the unlocked keys in Redis; a restarted node restores it, checked against the database row, and only a
 * session with no usable copy ends with `RESTART`. Going offline, by any door, deletes the copy before answering.
 */
class StayOnlineTest {
    private val ramp = Harness()
    private val pin = "482913"
    private val bob = PhoneNumber.parse("+254700000002")!!
    private val payhero = CollectionCredentials("api-user", "api-pass-secret", "911")
    private lateinit var portalToken: String

    private fun copyKey(id: MerchantId) = "online:$id"

    /** The only merchant: signed in, approved, funded and connected; offline. */
    private suspend fun ready(): MerchantId {
        ramp.merchants.rows.remove(ramp.crynce.merchantId)
        ramp.sessions.keys.remove(ramp.crynce.merchantId)
        val signedIn = ramp.verifyCodeFor("pay@crynce.co.ke")
        portalToken = signedIn.token
        val id = signedIn.merchant.merchant.merchantId
        ramp.applyAsMerchant(id, TESTNET, ProfileCommand("Crynce Ltd.", PhoneNumber.parse("+254712000111")!!))
        ramp.decideMerchant(id, approve = true, by = "staff")
        ramp.release()
        ramp.treasury.balances[ramp.merchantWallets.find(id, TESTNET)!!.custodyWalletId] = 100_000_000
        assertIs<Resource.Success<*>>(ramp.storePayments(id, TESTNET, PaymentAccountCommand(payhero, pin)))
        return id
    }

    private suspend fun untilStopped(id: MerchantId) =
        ramp.goOnline(id, TESTNET, pin, TradingWindow.UntilStopped).getOrNull()!!

    private suspend fun presence(id: MerchantId) = ramp.getMerchant(id, TESTNET).getOrNull()!!.presence

    private fun takenOffline() = ramp.email.sent.filter { it.second == Email.MerchantTakenOffline }

    private suspend fun buys() = ramp.buy(ramp.user(bob.e164), bob)

    @Test
    fun `a restart restores an open-ended session - still online, still eligible, nobody emailed`() =
        runTest {
            val id = ready()
            val online = untilStopped(id)

            ramp.sessions.restart()
            val tally = ramp.restoreSessions()

            assertEquals(SessionTally(restored = 1), tally)
            assertEquals(online, presence(id), "the same session, as if nothing happened")
            assertEquals(
                "api-pass-secret",
                ramp.sessions
                    .of(id)!!
                    .credentials.password,
            )
            assertIs<Resource.Success<*>>(buys(), "eligible for orders again")
            assertTrue(takenOffline().isEmpty())
            assertNull(ramp.merchants.find(id)!!.offlineReason)
        }

    @Test
    fun `a timed window is restored for what is left of it, and still ends by time`() =
        runTest {
            val id = ready()
            ramp.goOnline(id, TESTNET, pin, 60)
            ramp.clock.advance(1_800)

            ramp.sessions.restart()
            ramp.endSessions()

            assertTrue(presence(id).online)
            assertEquals(Duration.ofMinutes(30), ramp.redis.ttl(copyKey(id)), "the copy lasts as long as the window")
            ramp.clock.advance(1_801)
            ramp.endSessions()
            assertEquals(OfflineReason.WINDOW_ENDED, ramp.merchants.find(id)!!.offlineReason)
            assertNull(ramp.redis.raw(copyKey(id)))
        }

    @Test
    fun `restoring is idempotent - a second run or the minute's sweep changes nothing`() =
        runTest {
            val id = ready()
            untilStopped(id)
            ramp.sessions.restart()

            assertEquals(SessionTally(restored = 1), ramp.restoreSessions())
            val held = ramp.sessions.of(id)
            assertEquals(SessionTally(), ramp.restoreSessions())
            ramp.endSessions()
            assertEquals(RestoreOutcome.HELD, ramp.restorer.restore(id))

            assertTrue(held === ramp.sessions.of(id), "keys already held are never replaced")
            assertTrue(presence(id).online)
        }

    @Test
    fun `the copy is encrypted - neither the password nor the username is in Redis`() =
        runTest {
            val id = ready()
            untilStopped(id)

            val raw = assertNotNull(ramp.redis.raw(copyKey(id)))

            assertTrue(raw.startsWith("v1.TESTNET."))
            assertFalse("api-pass-secret" in raw || "api-user" in raw)
        }

    @Test
    fun `going offline deletes the copy, by every door`() =
        runTest {
            val id = ready()

            untilStopped(id)
            ramp.goOffline(id)
            assertNull(ramp.redis.raw(copyKey(id)), "RM6 DELETE")

            untilStopped(id)
            ramp.signOut(portalToken)
            assertNull(ramp.redis.raw(copyKey(id)), "sign-out")

            untilStopped(id)
            ramp.decideMerchant(id, approve = false, by = "staff")
            assertNull(ramp.redis.raw(copyKey(id)), "a suspension")
        }

    @Test
    fun `after going offline a restart restores nothing and says nothing`() =
        runTest {
            val id = ready()
            untilStopped(id)
            ramp.goOffline(id)

            ramp.sessions.restart()
            val tally = ramp.restoreSessions()

            assertEquals(SessionTally(), tally)
            assertNull(ramp.sessions.of(id))
            assertEquals(OfflineReason.STOPPED, presence(id).offlineReason)
            assertTrue(takenOffline().isEmpty())
        }

    @Test
    fun `a tampered copy ends the session with RESTART and is deleted`() =
        runTest {
            val id = ready()
            untilStopped(id)
            val raw = ramp.redis.raw(copyKey(id))!!
            val sealed = raw.substringAfterLast('.')
            val flipped = (if (sealed[10] == 'A') 'B' else 'A')
            ramp.redis.put(copyKey(id), raw.dropLast(sealed.length) + sealed.replaceRange(10, 11, flipped.toString()))

            ramp.sessions.restart()
            val tally = ramp.restoreSessions()

            assertEquals(SessionTally(unreadable = 1), tally)
            assertEquals(OfflineReason.RESTART, ramp.merchants.find(id)!!.offlineReason)
            assertNull(ramp.redis.raw(copyKey(id)))
            assertEquals(1, takenOffline().size, "the merchant is told to go online again")
        }

    @Test
    fun `a copy moved onto another session doesn't open`() =
        runTest {
            val id = ready()
            untilStopped(id)
            val raw = ramp.redis.raw(copyKey(id))!!
            val parts = raw.split('.')
            ramp.redis.put(copyKey(id), listOf(parts[0], "MAINNET", parts[2], parts[3]).joinToString("."))

            ramp.sessions.restart()

            assertEquals(SessionTally(unreadable = 1), ramp.restoreSessions())
            assertEquals(OfflineReason.RESTART, ramp.merchants.find(id)!!.offlineReason)
        }

    @Test
    fun `a rotated master key, or none, ends the session with RESTART`() =
        runTest {
            val id = ready()
            untilStopped(id)

            ramp.masterKey = "ab".repeat(32)
            ramp.sessions.restart()

            assertEquals(SessionTally(unreadable = 1), ramp.restoreSessions())
            assertEquals(OfflineReason.RESTART, presence(id).offlineReason)
            assertNull(ramp.redis.raw(copyKey(id)))
            assertEquals(RampError.NoMerchantAvailable, (buys() as Resource.Error).error)
        }

    @Test
    fun `Redis losing the copy ends the session with RESTART, as every restart used to`() =
        runTest {
            val id = ready()
            untilStopped(id)

            ramp.restartLosingCopies()

            assertEquals(SessionTally(missing = 1), ramp.restoreSessions())
            assertEquals(OfflineReason.RESTART, ramp.merchants.find(id)!!.offlineReason)
            assertEquals(1, takenOffline().size)
        }

    @Test
    fun `an open-ended copy expires after online_session_max_days - then a restart asks for the PIN`() =
        runTest {
            val id = ready()
            ramp.settingsRows.set(TESTNET) { it.copy(onlineSessionMaxDays = 2) }
            ramp.clock.advance(11) // past the settings cache
            untilStopped(id)

            ramp.clock.advance(Duration.ofDays(1).seconds)
            ramp.endSessions()
            assertTrue(presence(id).online, "no restart: the session goes on regardless of the copy")
            ramp.sessions.restart()
            assertEquals(SessionTally(restored = 1), ramp.restoreSessions(), "within the two days: restored")

            ramp.clock.advance(Duration.ofDays(1).seconds + 1)
            ramp.sessions.restart()

            assertNull(ramp.redis.raw(copyKey(id)), "expired")
            assertEquals(SessionTally(missing = 1), ramp.restoreSessions())
            assertEquals(OfflineReason.RESTART, ramp.merchants.find(id)!!.offlineReason)
        }

    @Test
    fun `a new payment account while online replaces the copy too`() =
        runTest {
            val id = ready()
            untilStopped(id)
            val fresh = CollectionCredentials("api-user-2", "new-pass", "912")
            assertIs<Resource.Success<*>>(ramp.storePayments(id, TESTNET, PaymentAccountCommand(fresh, pin)))

            ramp.sessions.restart()
            ramp.restoreSessions()

            assertEquals(
                "new-pass",
                ramp.sessions
                    .of(id)!!
                    .credentials.password,
            )
        }

    @Test
    fun `a copy whose session the row no longer shows is deleted and loads nothing`() =
        runTest {
            val id = ready()
            untilStopped(id)
            val copy = ramp.redis.raw(copyKey(id))!!
            ramp.goOffline(id)
            ramp.clock.advance(5)
            // A copy left behind (say the delete failed): the row says offline, so it must not bring them back.
            ramp.redis.set(copyKey(id), copy, Duration.ofDays(1))
            ramp.sessions.restart()

            assertEquals(RestoreOutcome.STALE, ramp.restorer.restore(id))

            assertNull(ramp.sessions.of(id))
            assertNull(ramp.redis.raw(copyKey(id)))
        }

    @Test
    fun `going offline while a restore is reading - the restore loses, and the keys go`() =
        runTest {
            val id = ready()
            untilStopped(id)
            ramp.sessions.restart()
            var reads = 0
            // The first read of the row says online; going offline lands right after it, before the keys are put.
            val racing =
                object : MerchantRepository by ramp.merchants {
                    override suspend fun find(merchantId: MerchantId): Merchant? {
                        val row = ramp.merchants.find(merchantId)
                        if (++reads == 1) ramp.goOffline(merchantId)
                        return row
                    }
                }
            val restorer = SessionRestorer(racing, ramp.sessions, ramp.copies, ramp.email, ramp.clock)

            assertEquals(RestoreOutcome.STALE, restorer.restore(id))

            assertNull(ramp.sessions.of(id), "loaded, then dropped once the row said offline")
            assertNull(ramp.redis.raw(copyKey(id)))
            assertEquals(OfflineReason.STOPPED, presence(id).offlineReason)
            assertEquals(RampError.NoMerchantAvailable, (buys() as Resource.Error).error)
        }

    @Test
    fun `a copy newer than the row is a go-online in flight - left alone, and its session isn't ended`() =
        runTest {
            val id = ready()
            ramp.goOnline(id, TESTNET, pin, 60)
            ramp.sessions.restart()
            ramp.clock.advance(5)
            // A go-online writes the keys and the copy, then the row: here it has written the copy only.
            val inFlight = ramp.merchants.find(id)!!
            untilStopped(id)
            ramp.merchants.rows[id] = inFlight
            ramp.sessions.restart()

            assertEquals(RestoreOutcome.PENDING, ramp.restorer.restore(id))
            assertEquals(SessionTally(), ramp.restoreSessions())

            assertNotNull(ramp.redis.raw(copyKey(id)))
            assertNull(ramp.merchants.find(id)!!.offlineReason, "not ended with RESTART")
        }
}
