package com.circaso.ramp

import com.circaso.integrations.api.KycDocument
import com.circaso.integrations.api.KycEnvironment
import com.circaso.integrations.api.KycStatus
import com.circaso.ramp.api.UserVerificationChanged
import com.circaso.ramp.domain.models.Subject
import com.circaso.shared.EventEnvelope
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.decode
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The verified badge (2026-11-24): ramp says `ramp.UserVerificationChanged` whenever a user's **live** verification
 * moves, and never for the sandbox or a merchant. Identity keeps it; users shows it.
 */
class VerifiedBadgeTest {
    private val ramp = Harness()
    private val amina get() = ramp.amina
    private val id = KycDocument("KEN", "Identity Card", "12345678")

    private fun session() = ramp.sessionOf(Subject.user(amina))

    private fun live() = ramp.requireKyc { it.copy(kycEnvironment = KycEnvironment.LIVE) }

    private fun announced(): List<UserVerificationChanged> =
        ramp.outbox.written
            .filter { it.type == UserVerificationChanged.TYPE }
            .map { it.decode() }

    private fun published(): List<EventEnvelope> = ramp.bus.published.filter { it.type == UserVerificationChanged.TYPE }

    @Test
    fun `a live approval announces the user verified, with when, written and published`() =
        runTest {
            live()
            assertTrue(ramp.startKyc(amina, Network.TESTNET) is Resource.Success)
            assertTrue(announced().isEmpty(), "starting a check is not a change of the badge")
            ramp.didit.approve(session(), id)
            ramp.webhook(session(), KycEnvironment.LIVE)

            val event = announced().last()
            assertEquals(amina, event.userId)
            assertTrue(event.verified)
            assertEquals(ramp.clock.now, event.verifiedAt)
            assertEquals(ramp.clock.now, event.changedAt)
            assertEquals(announced().size, published().size)
            assertFalse(
                "12345678" in
                    ramp.outbox.written
                        .last()
                        .data
                        .toString(),
                "nothing from the ID",
            )
        }

    @Test
    fun `an approval taken back announces the badge gone`() =
        runTest {
            live()
            ramp.startKyc(amina, Network.TESTNET)
            ramp.didit.approve(session(), id)
            ramp.webhook(session(), KycEnvironment.LIVE)
            ramp.clock.now = ramp.clock.now.plusSeconds(60)

            ramp.didit.set(session(), KycStatus.DOCUMENT_EXPIRED)
            ramp.webhook(session(), KycEnvironment.LIVE)

            val event = announced().last()
            assertFalse(event.verified)
            assertNull(event.verifiedAt)
            assertEquals(ramp.clock.now, event.changedAt)
        }

    @Test
    fun `a sandbox approval never announces anything - it is simulated`() =
        runTest {
            ramp.requireKyc()
            ramp.startKyc(amina, Network.TESTNET)
            ramp.didit.approve(session(), id)
            ramp.webhook(session())

            assertTrue(announced().isEmpty())
            assertTrue(published().isEmpty())
        }

    @Test
    fun `staff resetting a live verification announces it gone, and a sandbox reset says nothing`() =
        runTest {
            live()
            ramp.startKyc(amina, Network.TESTNET)
            ramp.didit.approve(session(), id)
            ramp.webhook(session(), KycEnvironment.LIVE)
            ramp.clock.now = ramp.clock.now.plusSeconds(60)

            assertTrue((ramp.resetVerification(Subject.user(amina), KycEnvironment.LIVE) as Resource.Success).data)

            val event = announced().last()
            assertFalse(event.verified)
            assertEquals(ramp.clock.now, event.changedAt)
            val before = announced().size
            ramp.resetVerification(Subject.user(amina), KycEnvironment.SANDBOX)
            assertEquals(before, announced().size)
        }

    @Test
    fun `the announce job says recent live rows again, which is also the backfill`() =
        runTest {
            live()
            ramp.startKyc(amina, Network.TESTNET)
            ramp.didit.approve(session(), id)
            ramp.webhook(session(), KycEnvironment.LIVE)
            ramp.outbox.written.clear()

            assertEquals(1, ramp.announceVerifications())
            val again = assertNotNull(announced().singleOrNull())
            assertTrue(again.verified)

            ramp.clock.now = ramp.clock.now.plus(Duration.ofDays(15))
            assertEquals(0, ramp.announceVerifications(), "older than the window: already said")
        }
}
