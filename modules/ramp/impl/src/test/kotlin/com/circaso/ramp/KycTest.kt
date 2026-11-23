package com.circaso.ramp

import com.circaso.identity.api.AccountStatus
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.KycDeclineReason
import com.circaso.integrations.api.KycDocument
import com.circaso.integrations.api.KycEnvironment
import com.circaso.integrations.api.KycStatus
import com.circaso.ramp.data.support.EnvelopeVault
import com.circaso.ramp.data.support.HmacIdentityKeys
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.BlockReason
import com.circaso.ramp.domain.models.KycStanding
import com.circaso.ramp.domain.models.KycState
import com.circaso.ramp.domain.models.KycView
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.models.VerificationReason
import com.circaso.ramp.domain.models.VerificationStatus
import com.circaso.ramp.domain.support.SettingsBook
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Identity verification for the ramp over in-memory fakes (decision 2026-11-22): starting and resuming a check, what
 * each of the provider's answers does, the daily cap, one identity for one account, a barred identity, the webhook as
 * a doorbell, the sweep, and the gates that rest on it. The testnet verifies in the provider's sandbox, where no
 * identity key is kept; the identity rules are tried with the testnet pointed at the live environment.
 */
class KycTest {
    private val ramp = Harness()
    private val amina get() = ramp.amina
    private val aminaSubject get() = Subject.user(amina)

    private fun <T> Resource<T>.error() = (this as Resource.Error).error

    private fun <T> Resource<T>.value() = (this as Resource.Success).data

    private suspend fun start(userId: UserId = amina): KycView = ramp.startKyc(userId, TESTNET).value()

    private suspend fun status(userId: UserId = amina) = ramp.status(userId, TESTNET).value()

    private fun row(userId: UserId = amina, environment: KycEnvironment = KycEnvironment.SANDBOX) =
        ramp.verificationRows.rows[Subject.user(userId) to environment]

    private fun session(userId: UserId = amina) = ramp.sessionOf(Subject.user(userId))

    /** The testnet verifies real people: identity keys are kept, as they are on mainnet. */
    private fun live() = ramp.requireKyc { it.copy(kycEnvironment = KycEnvironment.LIVE) }

    private val id = KycDocument("KEN", "Identity Card", "12345678")

    // Starting and resuming

    @Test
    fun `before anyone starts, R1 says NONE, required, and that a check may be started`() =
        runTest {
            ramp.requireKyc()

            val before = status()

            assertEquals(KycState.NONE, before.verification.state)
            assertTrue(before.verification.required && before.verification.canStart)
            assertNull(before.verification.reason)
            assertEquals(KycStanding.NONE, before.kyc, "the short form older apps read")
            assertFalse(before.blocked)
            assertEquals(RampError.KycRequired, ramp.quote().error())
        }

    @Test
    fun `starting opens one session with the provider and answers its token, its page and its expiry`() =
        runTest {
            ramp.requireKyc()

            val started = start()

            assertEquals(KycState.PENDING, started.state)
            val link = assertNotNull(started.link)
            assertEquals("token-1", link.token, "what the provider's native SDK starts with")
            assertEquals("https://verify.test/session/token-1", link.url, "the hosted page, as the fallback")
            assertEquals(ramp.clock.instant().plus(Duration.ofHours(24)), link.expiresAt)
            val asked = ramp.didit.started.single()
            assertEquals(KycEnvironment.SANDBOX, asked.environment, "the testnet verifies in the sandbox")
            assertEquals("user:$amina", asked.subject, "our own id, nothing personal")
            assertEquals(RampSettings.SANDBOX_WORKFLOW_USER, asked.workflowId)
            assertNull(asked.callbackUrl, "the app uses the SDK: nowhere to send a browser back to")
            assertEquals(Duration.ofHours(24), asked.validFor)
            val kept = row()!!
            assertEquals(VerificationStatus.PENDING to DIDIT, kept.status to kept.provider)
            assertEquals(1, kept.attempts)
            assertFalse("token-1" in kept.toString() || "token-1" in started.toString(), "the link never prints")
            assertTrue(ramp.frames.kyc.isEmpty(), "the caller has the answer: no frame for a start")
        }

    @Test
    fun `tapping again resumes the open session - no second session, no attempt counted`() =
        runTest {
            ramp.requireKyc()
            val first = start()

            val again = start()
            val third = start()

            assertEquals(first.link!!.token, again.link!!.token)
            assertEquals(first.link!!.url, third.link!!.url)
            assertEquals(1, ramp.didit.started.size, "the provider is asked once")
            assertEquals(1, row()!!.attempts)
            assertTrue(status().verification.canStart, "PENDING with a live link: continue it")
        }

    @Test
    fun `an open session whose link has run out is replaced by a new one`() =
        runTest {
            ramp.requireKyc()
            val first = start()
            ramp.didit.set(session(), KycStatus.EXPIRED)

            ramp.clock.advance(25 * 3_600)
            val second = start()

            assertNotEquals(first.link!!.token, second.link!!.token)
            assertEquals(2, row()!!.attempts)
        }

    @Test
    fun `KYC can be done where it isn't required yet, and R1 says it isn't required`() =
        runTest {
            val view = status().verification
            assertFalse(view.required)
            assertEquals(KycStanding.NOT_REQUIRED, status().kyc)

            assertEquals(KycState.PENDING, start().state)
        }

    // What the provider's answers do

    @Test
    fun `an approval the provider confirms verifies the user, tells them, and opens the ramp to them`() =
        runTest {
            ramp.requireKyc()
            ramp.verified(ramp.crynce)
            start()
            val sessionId = session()
            ramp.didit.approve(sessionId, id)

            assertIs<Resource.Success<Unit>>(ramp.webhook(sessionId))

            val verified = row()!!
            assertEquals(VerificationStatus.VERIFIED, verified.status)
            assertEquals(ramp.clock.instant(), verified.verifiedAt)
            assertNull(verified.sessionToken, "the link is dropped once it is no use")
            assertNull(verified.sessionUrl)
            assertNull(verified.identityKey, "the sandbox reads the same sample document for everyone: no key")
            val (who, network, told) = ramp.frames.kyc.single()
            assertEquals(amina to TESTNET, who to network)
            assertEquals(KycState.VERIFIED, told.state)
            assertFalse(told.canStart)
            assertNull(told.link, "a frame never carries a link")
            assertEquals(sessionId, ramp.didit.decisionQueries.last(), "the decision was read from the provider")

            val after = status()
            assertEquals(KycState.VERIFIED, after.verification.state)
            assertEquals(KycStanding.VERIFIED, after.kyc)
            assertTrue(after.canTradeNow(), "verified, a merchant online, no open order")
            assertIs<Resource.Success<*>>(ramp.buy())
            assertEquals(KycState.VERIFIED, start().state, "starting again answers the state, with no link")
            assertNull(start().link)
        }

    @Test
    fun `the webhook's own word is never taken - a session the provider still calls pending stays pending`() =
        runTest {
            ramp.requireKyc()
            start()

            ramp.webhook(session())

            assertEquals(VerificationStatus.PENDING, row()!!.status)
            assertTrue(ramp.frames.kyc.isEmpty())
            assertEquals(RampError.KycRequired, ramp.quote().error())
        }

    @Test
    fun `a decline says why in our own code, and the user may try again with a new session`() =
        runTest {
            ramp.requireKyc()
            val first = start()
            ramp.didit.decline(session(), KycDeclineReason.FACE_MATCH)
            ramp.webhook(session())

            val declined = status().verification
            assertEquals(KycState.DECLINED, declined.state)
            assertEquals(VerificationReason.FACE_MATCH, declined.reason)
            assertEquals("face_match", declined.reason!!.code)
            assertTrue(declined.canStart)
            assertEquals(
                KycState.DECLINED,
                ramp.frames.kyc
                    .single()
                    .third.state,
            )
            assertEquals(RampError.KycRequired, ramp.quote().error())

            val retry = start()
            assertEquals(KycState.PENDING, retry.state)
            assertNotEquals(first.link!!.token, retry.link!!.token, "a new session")
            assertEquals(2, row()!!.attempts)
            assertNull(row()!!.reason)
        }

    @Test
    fun `each of the provider's reasons has a code of ours, and a screening hit is never named`() =
        runTest {
            ramp.requireKyc { it.copy(kycAttemptsPerDay = 20) }
            val expected =
                mapOf(
                    KycDeclineReason.DOCUMENT to "document",
                    KycDeclineReason.DOCUMENT_EXPIRED to "document_expired",
                    KycDeclineReason.LIVENESS to "liveness",
                    KycDeclineReason.FACE_MATCH to "face_match",
                    KycDeclineReason.OTHER to "declined",
                )
            for ((reason, code) in expected) {
                start()
                ramp.didit.decline(session(), reason)
                ramp.webhook(session())
                assertEquals(code, status().verification.reason?.code)
                assertTrue(status().verification.canStart, code)
            }

            start()
            ramp.didit.decline(session(), KycDeclineReason.SCREENING)
            ramp.webhook(session())
            val screened = status().verification
            assertEquals("not_approved", screened.reason?.code)
            assertFalse(screened.canStart, "support, not another try")
            val before = ramp.didit.started.size
            assertNull(start().link, "asking again answers the state and starts nothing")
            assertEquals(before, ramp.didit.started.size)
        }

    @Test
    fun `a session in review waits with nothing to do, then follows the reviewer's decision`() =
        runTest {
            ramp.requireKyc()
            start()
            ramp.didit.set(session(), KycStatus.IN_REVIEW)
            ramp.webhook(session())

            val waiting = status().verification
            assertEquals(KycState.IN_REVIEW, waiting.state)
            assertFalse(waiting.canStart)
            assertNull(start().link, "nothing to start while reviewers look")
            assertEquals(1, ramp.didit.started.size)

            ramp.didit.approve(session(), id)
            ramp.webhook(session())
            assertEquals(KycState.VERIFIED, status().verification.state)
            assertEquals(listOf(KycState.IN_REVIEW, KycState.VERIFIED), ramp.frames.kyc.map { it.third.state })
        }

    @Test
    fun `a reviewer asking for a step again reopens the same session, with its link`() =
        runTest {
            ramp.requireKyc()
            val first = start()
            ramp.didit.set(session(), KycStatus.IN_REVIEW)
            ramp.webhook(session())

            ramp.didit.set(session(), KycStatus.RESUBMIT)
            ramp.webhook(session())

            val again = status().verification
            assertEquals(KycState.PENDING, again.state)
            assertEquals(VerificationReason.RESUBMISSION_REQUESTED, again.reason)
            assertTrue(again.canStart)
            assertEquals(first.link!!.token, start().link!!.token, "the same session continues")
            assertEquals(1, ramp.didit.started.size)
        }

    // Submitted: finished by the person, not yet decided by the provider

    @Test
    fun `a check the person finished is still PENDING but submitted - nothing to continue, no link, one frame`() =
        runTest {
            ramp.requireKyc()
            start()
            assertFalse(status().verification.submitted, "started, not finished")

            ramp.didit.submit(session())
            ramp.webhook(session())

            val waiting = status().verification
            assertEquals(KycState.PENDING, waiting.state)
            assertTrue(waiting.submitted, "finished: the provider is deciding")
            assertFalse(waiting.canStart, "nothing to continue")
            assertNull(waiting.reason)
            assertNull(waiting.retryAt)
            val told =
                ramp.frames.kyc
                    .single()
                    .third
            assertTrue(told.submitted && !told.canStart, "the frame says so when it flips")
            assertNull(told.link)

            val tapped = start()
            assertTrue(tapped.submitted)
            assertNull(tapped.link, "R0 never reopens a capture that is done")
            assertEquals(1, ramp.didit.started.size, "and never makes another session")
            assertEquals(1, row()!!.attempts)

            ramp.webhook(session())
            assertEquals(1, ramp.frames.kyc.size, "the same news twice is told once")
        }

    @Test
    fun `a submitted check follows the provider's decision, by the status read alone`() =
        runTest {
            ramp.requireKyc()
            start()
            ramp.didit.submit(session())
            ramp.clock.advance(6)

            val waiting = ramp.getKyc(amina, TESTNET).value()
            assertTrue(waiting.submitted, "the SDK closed: the read learns it without a webhook")
            assertFalse(waiting.canStart)

            ramp.clock.advance(6)
            assertTrue(ramp.getKyc(amina, TESTNET).value().submitted, "still deciding: asked again, nothing moves")
            assertEquals(2, ramp.didit.decisionQueries.size, "a submitted check keeps being read")

            ramp.didit.approve(session(), id)
            ramp.clock.advance(6)
            val verified = ramp.getKyc(amina, TESTNET).value()
            assertEquals(KycState.VERIFIED, verified.state)
            assertFalse(verified.submitted, "decided: nothing is awaited any more")
            assertFalse(row()!!.isSubmitted)
            assertEquals(
                listOf(KycState.PENDING to true, KycState.VERIFIED to false),
                ramp.frames.kyc.map { it.third.state to it.third.submitted },
            )
        }

    @Test
    fun `a submitted check that is declined may be started again, with a new session`() =
        runTest {
            ramp.requireKyc()
            val first = start()
            ramp.didit.submit(session())
            ramp.webhook(session())

            ramp.didit.decline(session(), KycDeclineReason.LIVENESS)
            ramp.webhook(session())

            val declined = status().verification
            assertEquals(KycState.DECLINED to false, declined.state to declined.submitted)
            assertTrue(declined.canStart)
            assertNotEquals(first.link!!.token, start().link!!.token)
        }

    @Test
    fun `in review counts as submitted too - the person has nothing left to do`() =
        runTest {
            ramp.requireKyc()
            start()
            ramp.didit.submit(session())
            ramp.webhook(session())

            ramp.didit.set(session(), KycStatus.IN_REVIEW)
            ramp.webhook(session())

            val review = status().verification
            assertEquals(KycState.IN_REVIEW, review.state)
            assertTrue(review.submitted && !review.canStart)
            assertFalse(row()!!.isSubmitted, "the row keeps it for PENDING only: the status says the rest")
        }

    @Test
    fun `a step asked for again is not submitted until the person has redone it`() =
        runTest {
            ramp.requireKyc()
            val first = start()
            ramp.didit.submit(session())
            ramp.webhook(session())

            ramp.didit.set(session(), KycStatus.RESUBMIT)
            ramp.webhook(session())
            val asked = status().verification
            assertEquals(VerificationReason.RESUBMISSION_REQUESTED, asked.reason)
            assertFalse(asked.submitted, "something is asked of the person again")
            assertTrue(asked.canStart)
            assertEquals(first.link!!.token, start().link!!.token, "the same session reopens")

            ramp.didit.submit(session(), KycStatus.RESUBMIT)
            ramp.webhook(session())
            val redone = status().verification
            assertEquals(KycState.PENDING, redone.state)
            assertTrue(redone.submitted && !redone.canStart)
            assertNull(redone.reason, "nothing is asked any more")
            assertNull(start().link)
        }

    @Test
    fun `when the provider doesn't say whether the person finished, what was known stays`() =
        runTest {
            ramp.requireKyc()
            start()
            ramp.didit.set(session(), KycStatus.PENDING, isSubmitted = null)
            ramp.webhook(session())
            assertFalse(status().verification.submitted, "never assumed")
            assertTrue(status().verification.canStart)

            ramp.didit.submit(session())
            ramp.webhook(session())
            ramp.didit.set(session(), KycStatus.PENDING, isSubmitted = null)
            ramp.webhook(session())
            assertTrue(status().verification.submitted, "one missed answer sends nobody back to the capture")
            assertEquals(1, ramp.frames.kyc.size)

            ramp.didit.set(session(), KycStatus.PENDING, isSubmitted = false)
            ramp.webhook(session())
            val back = status().verification
            assertFalse(back.submitted, "the provider's own word that they are back in the check")
            assertTrue(back.canStart)
            assertNotNull(start().link)
        }

    @Test
    fun `a tap on a submitted check asks the provider first, so a decision made since is not missed`() =
        runTest {
            ramp.requireKyc()
            start()
            ramp.didit.submit(session())
            ramp.webhook(session())
            ramp.didit.set(session(), KycStatus.RESUBMIT)
            ramp.clock.advance(6)

            val tapped = start()

            assertFalse(tapped.submitted)
            assertEquals(VerificationReason.RESUBMISSION_REQUESTED, tapped.reason)
            assertNotNull(tapped.link, "the lost webhook didn't strand them on \"we're checking\"")
        }

    @Test
    fun `a submitted check the provider never decides still ends in time, so nobody is locked out`() =
        runTest {
            ramp.requireKyc()
            start()
            ramp.didit.submit(session())
            ramp.clock.advance(5 * 60 + 1)
            ramp.sweepVerifications()
            assertTrue(row()!!.isSubmitted, "the sweep keeps it fresh")
            assertEquals(VerificationStatus.PENDING, row()!!.status)

            ramp.clock.advance(24 * 3_600)
            ramp.sweepVerifications()

            val expired = status().verification
            assertEquals(KycState.EXPIRED to false, expired.state to expired.submitted)
            assertTrue(expired.canStart)
        }

    @Test
    fun `a session the provider ends unfinished is EXPIRED, and may be started again`() =
        runTest {
            ramp.requireKyc()
            start()
            ramp.didit.set(session(), KycStatus.EXPIRED)
            ramp.webhook(session())

            val expired = status().verification
            assertEquals(KycState.EXPIRED, expired.state)
            assertEquals("session_expired", expired.reason?.code)
            assertTrue(expired.canStart)
            assertEquals(KycState.PENDING, start().state)
        }

    @Test
    fun `an approval taken back, or a document that has expired since, un-verifies the user`() =
        runTest {
            ramp.requireKyc()
            ramp.verified(amina)
            assertEquals(KycState.VERIFIED, status().verification.state)

            ramp.didit.set(session(), KycStatus.DOCUMENT_EXPIRED)
            ramp.webhook(session())

            val expired = status().verification
            assertEquals(KycState.EXPIRED, expired.state)
            assertEquals("document_expired", expired.reason?.code)
            assertTrue(expired.canStart)
            assertEquals(RampError.KycRequired, ramp.quote().error())

            ramp.didit.decline(session())
            ramp.webhook(session())
            assertEquals(KycState.DECLINED, status().verification.state)
        }

    @Test
    fun `the same outcome delivered twice is applied once`() =
        runTest {
            ramp.requireKyc()
            start()
            ramp.didit.approve(session(), id)

            repeat(3) { ramp.webhook(session()) }

            assertEquals(1, ramp.frames.kyc.size)
            assertEquals(VerificationStatus.VERIFIED, row()!!.status)
        }

    @Test
    fun `an old session's late news never touches the session that replaced it`() =
        runTest {
            ramp.requireKyc()
            start()
            val old = session()
            ramp.didit.decline(old)
            ramp.webhook(old)
            start()
            val current = session()

            ramp.didit.approve(old, id)
            assertIs<Resource.Success<Unit>>(ramp.webhook(old))

            assertEquals(VerificationStatus.PENDING, row()!!.status, "the old session is nobody's row any more")
            assertEquals(current, row()!!.sessionId)
        }

    // The daily cap

    @Test
    fun `a declined check may be retried up to the day's cap, which lifts at the next Nairobi midnight`() =
        runTest {
            ramp.requireKyc { it.copy(kycAttemptsPerDay = 2) }
            repeat(2) {
                start()
                ramp.didit.decline(session())
                ramp.webhook(session())
            }

            val capped = status().verification
            assertEquals(KycState.DECLINED, capped.state)
            assertFalse(capped.canStart, "today's tries are used up")
            // 12:00 UTC is 15:00 in Nairobi: the day ends nine hours later.
            assertEquals(Instant.parse("2026-11-22T21:00:00Z"), capped.retryAt)
            val refused = assertIs<RampError.KycAttemptsExceeded>(ramp.startKyc(amina, TESTNET).error())
            assertEquals(9 * 3_600L, refused.details?.retryAfterSeconds)
            assertEquals(2, ramp.didit.started.size, "the provider is not asked")

            ramp.clock.advance(9 * 3_600 + 1)
            assertTrue(status().verification.canStart)
            assertNull(status().verification.retryAt)
            assertEquals(KycState.PENDING, start().state)
            assertEquals(3, row()!!.attempts)
        }

    // Identity: one identity, one account; a barred identity

    @Test
    fun `a live approval keeps an identity key, never the document`() =
        runTest {
            live()
            start()
            ramp.didit.approve(session(), id)
            ramp.webhook(session(), KycEnvironment.LIVE)

            val verified = row(environment = KycEnvironment.LIVE)!!
            assertEquals(VerificationStatus.VERIFIED, verified.status)
            val key = assertNotNull(verified.identityKey)
            assertEquals(64, key.length, "an HMAC-SHA256, as hex")
            assertEquals(ramp.identities.of(id), key)
            assertFalse("12345678" in key || "12345678" in verified.toString())
            assertEquals(
                KycEnvironment.LIVE,
                ramp.didit.started
                    .single()
                    .environment,
            )
        }

    @Test
    fun `a second account presenting the same document is refused - identity_in_use - and not verified`() =
        runTest {
            live()
            start()
            ramp.didit.approve(session(), id)
            ramp.webhook(session(), KycEnvironment.LIVE)
            val bob = ramp.user("+254700000002")

            start(bob)
            // The same document, as another scan reads it: spacing, case and punctuation don't make a new person.
            ramp.didit.approve(session(bob), KycDocument(" ken ", "identity  card", "12-345 678"))
            ramp.webhook(session(bob), KycEnvironment.LIVE)

            val refused = status(bob).verification
            assertEquals(KycState.DECLINED, refused.state)
            assertEquals("identity_in_use", refused.reason?.code)
            assertFalse(refused.canStart, "trying again can't help")
            assertNull(row(bob, KycEnvironment.LIVE)!!.identityKey)
            assertEquals(
                KycState.DECLINED,
                ramp.frames.kyc
                    .last()
                    .third.state,
            )
            assertEquals(RampError.KycRequired, ramp.quote(bob).error())
            assertNull(ramp.startKyc(bob, TESTNET).value().link, "asking again starts nothing")
            val aminas = row(environment = KycEnvironment.LIVE)!!
            assertEquals(VerificationStatus.VERIFIED, aminas.status, "amina keeps hers")

            // Another document is another identity.
            val carol = ramp.user("+254700000003")
            start(carol)
            ramp.didit.approve(session(carol), KycDocument("KEN", "Identity Card", "87654321"))
            ramp.webhook(session(carol), KycEnvironment.LIVE)
            assertEquals(KycState.VERIFIED, status(carol).verification.state)
        }

    @Test
    fun `a user and a merchant may be the same person - the rule is one account of each kind`() =
        runTest {
            live()
            start()
            ramp.didit.approve(session(), id)
            ramp.webhook(session(), KycEnvironment.LIVE)

            ramp.startMerchantKyc(ramp.crynce.merchantId, TESTNET)
            val merchantSession = ramp.sessionOf(Subject.merchant(ramp.crynce.merchantId))
            ramp.didit.approve(merchantSession, id)
            ramp.webhook(merchantSession, KycEnvironment.LIVE)

            val merchant = Subject.merchant(ramp.crynce.merchantId)
            val merchantRow = ramp.verificationRows.rows.getValue(merchant to KycEnvironment.LIVE)
            assertEquals(VerificationStatus.VERIFIED, merchantRow.status)
        }

    @Test
    fun `in the sandbox everybody presents the same sample document, so no identity is kept or compared`() =
        runTest {
            ramp.requireKyc()
            val bob = ramp.user("+254700000002")

            ramp.verified(amina, id)
            ramp.verified(bob, id)

            assertEquals(KycState.VERIFIED, status(amina).verification.state)
            assertEquals(KycState.VERIFIED, status(bob).verification.state)
            assertNull(row(bob)!!.identityKey)
        }

    @Test
    fun `barring a user bars their identity - the same person on a new account is refused and barred too`() =
        runTest {
            live()
            start()
            ramp.didit.approve(session(), id)
            ramp.webhook(session(), KycEnvironment.LIVE)

            val block = ramp.blockUser(amina, BlockReason.OPS, "reversed a payment", "staff-1").value()

            assertEquals(amina, block.userId)
            assertEquals(2, ramp.blocks.rows.size, "the user, and the identity they verified with")
            assertTrue(status().blocked)
            assertFalse(status().verification.canStart)
            assertFalse(status().canTradeNow())
            assertEquals(RampError.Blocked, ramp.quote().error())
            assertEquals(RampError.Blocked, ramp.startKyc(amina, TESTNET).error())

            // The same person, a new account, the same document.
            val again = ramp.user("+254700000009")
            start(again)
            ramp.didit.approve(session(again), id)
            ramp.webhook(session(again), KycEnvironment.LIVE)

            val refused = status(again)
            assertEquals(KycState.DECLINED, refused.verification.state)
            assertEquals("blocked", refused.verification.reason?.code)
            assertTrue(refused.blocked, "the new account is barred as well")
            val systems = ramp.blocks.rows.single { it.userId == again }
            assertEquals(BlockReason.IDENTITY to "system", systems.reason to systems.createdBy)
            assertEquals(RampError.Blocked, ramp.quote(again).error())
        }

    @Test
    fun `lifting a block lifts the identity's with it, and staff list who is barred`() =
        runTest {
            live()
            start()
            ramp.didit.approve(session(), id)
            ramp.webhook(session(), KycEnvironment.LIVE)
            ramp.blockUser(amina, BlockReason.OPS, "why", "staff-1")
            val again = ramp.blockUser(amina, BlockReason.OPS, "again", "staff-2").value()
            assertEquals(
                ramp.blocks.rows
                    .first()
                    .blockId,
                again.blockId,
                "already barred: the block there is",
            )
            assertEquals(listOf(amina), ramp.listBlocks(null, 50).value().map { it.userId })
            val seen = ramp.userVerifications(amina).value()
            assertEquals(VerificationStatus.VERIFIED, seen.verifications.single().status)
            assertEquals("why", seen.block?.note)

            assertTrue(ramp.unblockUser(amina).value())

            assertTrue(ramp.blocks.rows.isEmpty())
            assertFalse(status().blocked)
            assertIs<Resource.Success<*>>(ramp.quote())
            assertFalse(ramp.unblockUser(amina).value(), "nothing left to lift")
        }

    @Test
    fun `staff resetting a verification frees its identity for another account`() =
        runTest {
            live()
            start()
            ramp.didit.approve(session(), id)
            ramp.webhook(session(), KycEnvironment.LIVE)
            val bob = ramp.user("+254700000002")
            start(bob)
            ramp.didit.approve(session(bob), id)
            ramp.webhook(session(bob), KycEnvironment.LIVE)
            assertEquals("identity_in_use", status(bob).verification.reason?.code)

            // Amina lost that account: staff release her identity, then let Bob (her new account) start over.
            assertTrue(ramp.resetVerification(aminaSubject, KycEnvironment.LIVE).value())
            assertTrue(ramp.resetVerification(Subject.user(bob), KycEnvironment.LIVE).value())
            assertFalse(ramp.resetVerification(Subject.user(bob), KycEnvironment.LIVE).value())

            assertEquals(KycState.NONE, status().verification.state)
            start(bob)
            ramp.didit.approve(session(bob), id)
            ramp.webhook(session(bob), KycEnvironment.LIVE)
            assertEquals(KycState.VERIFIED, status(bob).verification.state)
        }

    @Test
    fun `a live approval whose document can't be read is declined so another document can be tried`() =
        runTest {
            live()
            start()
            ramp.didit.approve(session(), document = null)
            ramp.webhook(session(), KycEnvironment.LIVE)

            val view = status().verification
            assertEquals(KycState.DECLINED, view.state)
            assertEquals("document_unreadable", view.reason?.code)
            assertTrue(view.canStart)
        }

    @Test
    fun `the identity key is the HMAC of country, type and number, normalised, under a key derived from the master`() {
        val keys = HmacIdentityKeys { EnvelopeVault.masterKey(MASTER_KEY) }
        val key = keys.of(id)!!

        assertEquals("KEN|IDENTITY CARD|12345678", HmacIdentityKeys.message(id))
        val untidy = KycDocument(" ken", "Identity   card ", " a 123-456/78 ")
        assertEquals("KEN|IDENTITY CARD|A12345678", HmacIdentityKeys.message(untidy))
        assertEquals(key, keys.of(KycDocument("ken ", "identity card", "1234 5678")), "the same document, the same key")
        assertNotEquals(key, keys.of(KycDocument("UGA", "Identity Card", "12345678")), "another country")
        assertNotEquals(key, keys.of(KycDocument("KEN", "Passport", "12345678")), "another kind of document")
        assertNotEquals(key, keys.of(KycDocument("KEN", "Identity Card", "12345679")))
        assertEquals(key, HmacIdentityKeys { EnvelopeVault.masterKey(MASTER_KEY) }.of(id), "the same on every server")
        val other = HmacIdentityKeys { EnvelopeVault.masterKey(MASTER_KEY.reversed()) }
        assertNotEquals(key, other.of(id), "another master key gives other keys: it must never change")
        assertTrue(keys.isAvailable)

        val none = HmacIdentityKeys { null }
        assertFalse(none.isAvailable)
        assertNull(none.of(id))
    }

    // The webhook is a doorbell

    @Test
    fun `a webhook for a session the ramp never started is acknowledged and changes nothing`() =
        runTest {
            ramp.requireKyc()
            start()

            assertIs<Resource.Success<Unit>>(ramp.webhook("00000000-0000-4000-8000-000000000000"))

            assertTrue(ramp.didit.decisionQueries.isEmpty(), "nobody is asked about a stranger's session")
            assertEquals(VerificationStatus.PENDING, row()!!.status)
        }

    @Test
    fun `a webhook from the other environment can't speak for a session, even about an approval`() =
        runTest {
            ramp.requireKyc()
            start()
            ramp.didit.approve(session(), id)

            assertIs<Resource.Success<Unit>>(ramp.webhook(session(), KycEnvironment.LIVE))

            assertEquals(VerificationStatus.PENDING, row()!!.status)
            assertTrue(ramp.didit.decisionQueries.isEmpty())
            assertEquals(listOf("wrong_environment"), ramp.alerts.stuck)
        }

    @Test
    fun `when the provider can't be asked the webhook is not acknowledged, so it is delivered again`() =
        runTest {
            ramp.requireKyc()
            start()
            ramp.didit.approve(session(), id)
            ramp.didit.failure = IntegrationError.Unavailable("Didit")

            assertEquals(RampError.KycUnavailable, ramp.webhook(session()).error())
            assertEquals(VerificationStatus.PENDING, row()!!.status, "never verified on the webhook's word")

            ramp.didit.failure = null
            assertIs<Resource.Success<Unit>>(ramp.webhook(session()))
            assertEquals(VerificationStatus.VERIFIED, row()!!.status)
        }

    @Test
    fun `a decision that is about somebody else is never applied`() =
        runTest {
            ramp.requireKyc()
            start()
            ramp.didit.set(session(), KycStatus.APPROVED, document = id, subject = "user:${UserId.new()}")

            ramp.webhook(session())

            assertEquals(VerificationStatus.PENDING, row()!!.status)
            assertEquals(listOf("subject_mismatch"), ramp.alerts.stuck)
        }

    // The status read, and the sweep

    @Test
    fun `reading the status asks the provider while a check is unfinished, at most every few seconds`() =
        runTest {
            ramp.requireKyc()
            start()
            ramp.didit.approve(session(), id)

            assertEquals(KycState.PENDING, ramp.getKyc(amina, TESTNET).value().state, "asked a moment ago")
            assertTrue(ramp.didit.decisionQueries.isEmpty())

            ramp.clock.advance(6)
            val read = ramp.getKyc(amina, TESTNET).value()
            assertEquals(KycState.VERIFIED, read.state, "the SDK closed: the outcome shows without a webhook")
            assertNull(read.link, "the status read never hands out a link")

            ramp.clock.advance(60)
            ramp.getKyc(amina, TESTNET)
            assertEquals(1, ramp.didit.decisionQueries.size, "a finished check is not asked about again")
        }

    @Test
    fun `the sweep reads a pending session whose webhook never came`() =
        runTest {
            ramp.requireKyc()
            start()
            ramp.didit.approve(session(), id)

            assertEquals(0, ramp.sweepVerifications(), "checked a moment ago")
            ramp.clock.advance(5 * 60 + 1)
            assertEquals(1, ramp.sweepVerifications())

            assertEquals(VerificationStatus.VERIFIED, row()!!.status)
            assertEquals(
                KycState.VERIFIED,
                ramp.frames.kyc
                    .single()
                    .third.state,
            )
        }

    @Test
    fun `nobody stays PENDING forever - an abandoned session expires when the provider says so, or in time`() =
        runTest {
            ramp.requireKyc()
            start()
            val bob = ramp.user("+254700000002")
            start(bob)
            ramp.didit.set(session(), KycStatus.EXPIRED)

            ramp.clock.advance(6 * 60)
            ramp.sweepVerifications()
            assertEquals(VerificationStatus.EXPIRED, row()!!.status, "the provider says so")
            assertEquals(VerificationStatus.PENDING, row(bob)!!.status, "still inside its 24 hours")

            ramp.clock.advance(24 * 3_600)
            ramp.sweepVerifications()
            val expired = row(bob)!!
            assertEquals(VerificationStatus.EXPIRED, expired.status, "still pending at the provider: its time is up")
            assertEquals(VerificationReason.SESSION_EXPIRED, expired.reason)
            assertTrue(status(bob).verification.canStart)

            // The provider catching up ("In Progress", then its own expiry) doesn't flip it back and forth.
            ramp.webhook(session(bob))
            assertEquals(VerificationStatus.EXPIRED, row(bob)!!.status)
            assertEquals(2, ramp.frames.kyc.size, "one frame each")
        }

    @Test
    fun `while the provider is out of reach the sweep expires nothing on a guess`() =
        runTest {
            ramp.requireKyc()
            start()
            ramp.didit.failure = IntegrationError.Unavailable("Didit")

            ramp.clock.advance(48 * 3_600)
            assertEquals(1, ramp.sweepVerifications())

            assertEquals(VerificationStatus.PENDING, row()!!.status)
        }

    @Test
    fun `a session whose provider can no longer be asked, or no longer knows it, is expired after its time`() =
        runTest {
            ramp.requireKyc()
            start()
            val bob = ramp.user("+254700000002")
            start(bob)
            ramp.didit.forget(session(bob))
            ramp.clock.advance(6 * 60)
            ramp.sweepVerifications()
            assertEquals(VerificationStatus.EXPIRED, row(bob)!!.status, "the provider has no such session any more")
            assertEquals(VerificationStatus.PENDING, row()!!.status)

            ramp.didit.available -= KycEnvironment.SANDBOX
            ramp.clock.advance(25 * 3_600)
            ramp.sweepVerifications()
            assertEquals(VerificationStatus.EXPIRED, row()!!.status, "the sandbox's keys are gone, and its time is up")
        }

    @Test
    fun `a review the provider settled without a webhook is picked up by the sweep`() =
        runTest {
            ramp.requireKyc()
            start()
            ramp.didit.set(session(), KycStatus.IN_REVIEW)
            ramp.webhook(session())
            ramp.didit.approve(session(), id)

            ramp.clock.advance(31 * 60)
            ramp.sweepVerifications()

            assertEquals(VerificationStatus.VERIFIED, row()!!.status)
        }

    // Where it can't run: clear errors, never a crash

    @Test
    fun `an environment without keys, a missing workflow or a missing master key answer kyc_not_configured`() =
        runTest {
            ramp.requireKyc()
            ramp.didit.available -= KycEnvironment.SANDBOX
            assertEquals(RampError.KycNotConfigured, ramp.startKyc(amina, TESTNET).error())
            ramp.didit.available += KycEnvironment.SANDBOX

            ramp.requireKyc { it.copy(kycWorkflowUser = "") }
            assertEquals(RampError.KycNotConfigured, ramp.startKyc(amina, TESTNET).error())

            ramp.requireKyc { it.copy(kycWorkflowUser = "wf-user", kycProvider = "gone") }
            assertEquals(RampError.KycNotConfigured, ramp.startKyc(amina, TESTNET).error())

            // Live approvals need the identity key: no master key, no live check.
            ramp.requireKyc { it.copy(kycProvider = DIDIT, kycEnvironment = KycEnvironment.LIVE) }
            ramp.masterKey = null
            assertEquals(RampError.KycNotConfigured, ramp.startKyc(amina, TESTNET).error())
            assertTrue(ramp.didit.started.isEmpty(), "nothing was asked of the provider")
            assertNull(row(), "and nothing was written")
            assertEquals(KycState.NONE, status().verification.state)

            ramp.masterKey = MASTER_KEY
            assertEquals(KycState.PENDING, start().state)
        }

    @Test
    fun `the provider out of reach, or refusing, is kyc_unavailable and counts no attempt`() =
        runTest {
            ramp.requireKyc()
            ramp.didit.failure = IntegrationError.Unavailable("Didit")
            assertEquals(RampError.KycUnavailable, ramp.startKyc(amina, TESTNET).error())
            ramp.didit.failure = IntegrationError.Rejected("The identity provider refused the request.")
            assertEquals(RampError.KycUnavailable, ramp.startKyc(amina, TESTNET).error())
            assertEquals(listOf("refused_by_provider"), ramp.alerts.stuck)
            ramp.didit.failure = IntegrationError.NotConfigured("Didit's sandbox environment")
            assertEquals(RampError.KycNotConfigured, ramp.startKyc(amina, TESTNET).error())

            assertNull(row())
            ramp.didit.failure = null
            start()
            assertEquals(1, row()!!.attempts)
        }

    @Test
    fun `verifying is not a trade - it is open with the ramp off, to an active account that isn't barred`() =
        runTest {
            ramp.requireKyc()
            ramp.settingsRows.set(TESTNET) { it.copy(state = RampState.OFF) }
            ramp.book.invalidate()
            assertEquals(KycState.NONE, (ramp.getKyc(amina, TESTNET) as Resource.Success).data.state)
            val started = (ramp.startKyc(amina, TESTNET) as Resource.Success).data
            assertEquals(KycState.PENDING, started.state)
            assertNotNull(started.link, "a check starts although nobody can trade")

            val bob = ramp.user("+254700000002")
            ramp.accounts.statuses[bob] = AccountStatus.SUSPENDED
            assertEquals(RampError.AccountRestricted, ramp.startKyc(bob, TESTNET).error())

            ramp.accounts.statuses[bob] = AccountStatus.ACTIVE
            ramp.blockUser(bob, BlockReason.OPS, "why", "staff-1")
            assertEquals(RampError.Blocked, ramp.startKyc(bob, TESTNET).error())
            val barred = (ramp.getKyc(bob, TESTNET) as Resource.Success).data
            assertFalse(barred.canStart, "someone barred reads their state with nothing to start")
        }

    @Test
    fun `a person verifies from their profile where kyc isn't required, and off the preview list`() =
        runTest {
            // The testnet as it is seeded for staff testing: KYC not required, the ramp in PREVIEW, Amina not on the list.
            ramp.settingsRows.set(
                TESTNET,
            ) { it.copy(state = RampState.PREVIEW, kycRequired = false, kycProvider = DIDIT) }
            ramp.book.invalidate()
            assertEquals(RampError.Unavailable, ramp.status(amina, TESTNET).error(), "trading is still closed to her")

            val before = (ramp.getKyc(amina, TESTNET) as Resource.Success).data
            assertEquals(KycState.NONE to false, before.state to before.required)
            assertTrue(before.canStart)

            val started = (ramp.startKyc(amina, TESTNET) as Resource.Success).data
            assertNotNull(started.link)
            val session = ramp.sessionOf(Subject.user(amina))
            ramp.didit.approve(session, KycDocument("KEN", "Identity Card", "12345678"))
            ramp.webhook(session)

            val after = (ramp.getKyc(amina, TESTNET) as Resource.Success).data
            assertEquals(KycState.VERIFIED, after.state)
            assertFalse(after.required, "verified although nothing asked for it")
        }

    @Test
    fun `where the identity provider isn't set up, verifying answers so - with the ramp off or on`() =
        runTest {
            ramp.settingsRows.set(TESTNET) { it.copy(state = RampState.OFF, kycProvider = DIDIT, kycWorkflowUser = "") }
            ramp.book.invalidate()

            assertEquals(RampError.KycNotConfigured, ramp.startKyc(amina, TESTNET).error())
            assertEquals(KycState.NONE, (ramp.getKyc(amina, TESTNET) as Resource.Success).data.state)
        }

    @Test
    fun `a provider that needs nothing from the person verifies at once, as the local stand-in does`() =
        runTest {
            ramp.requireKyc()
            ramp.verified(ramp.crynce)
            ramp.requireKyc { it.copy(kycProvider = INSTANT) }

            val started = start()

            assertEquals(KycState.VERIFIED, started.state, "no SDK to run: the answer is already the outcome")
            assertNull(started.link)
            assertEquals(
                KycState.VERIFIED,
                ramp.frames.kyc
                    .single()
                    .third.state,
            )
            assertIs<Resource.Success<*>>(ramp.buy())
        }

    @Test
    fun `a verification counts only in the environment it was made in`() =
        runTest {
            ramp.requireKyc()
            ramp.verified(amina)
            assertEquals(KycState.VERIFIED, status().verification.state)

            // Staff point the testnet at the live environment: a sandbox approval is worth nothing there.
            live()
            val onLive = status().verification
            assertEquals(KycState.NONE, onLive.state)
            assertEquals(RampError.KycRequired, ramp.quote().error())

            // And mainnet, which always requires it, never sees the sandbox's.
            ramp.settingsRows.set(Network.MAINNET) { it.copy(state = RampState.ON) }
            ramp.book.invalidate()
            val mainnet = ramp.status(amina, Network.MAINNET).value().verification
            assertEquals(KycState.NONE to true, mainnet.state to mainnet.required)
        }

    @Test
    fun `a frame goes to each network that verifies in the session's environment`() =
        runTest {
            ramp.requireKyc()
            ramp.settingsRows.set(Network.MAINNET) { it.copy(kycEnvironment = KycEnvironment.SANDBOX) }
            ramp.book.invalidate()

            ramp.verified(amina)

            assertEquals(
                setOf(TESTNET, Network.MAINNET),
                ramp.frames.kyc
                    .map { it.second }
                    .toSet(),
            )
        }

    // Settings

    @Test
    fun `a network starts on Didit - the testnet in the sandbox with its workflows, mainnet live with none yet`() =
        runTest {
            val server =
                SettingsBook(MemorySettings(), RecordingAlerts(), ramp.providers, ramp.kycProviders, ramp.clock)
            val testnet = server.of(TESTNET)
            assertEquals(DIDIT to KycEnvironment.SANDBOX, testnet.kycProvider to testnet.kycEnvironment)
            assertEquals("a3cb5782-d5c1-4c35-832d-ed373d80a0ea", testnet.kycWorkflowUser)
            assertEquals("dabdb52a-b4bc-435a-b2bf-a636bb17bad6", testnet.kycWorkflowMerchant)
            assertFalse(testnet.kycRequired)
            assertEquals(3 to 24, testnet.kycAttemptsPerDay to testnet.kycPendingHours)
            assertEquals("https://merchants.circaso.com/kyc/done", testnet.merchantKycCallbackUrl)
            val mainnet = server.of(Network.MAINNET)
            assertEquals(DIDIT to KycEnvironment.LIVE, mainnet.kycProvider to mainnet.kycEnvironment)
            assertEquals("" to "", mainnet.kycWorkflowUser to mainnet.kycWorkflowMerchant)
            assertTrue(mainnet.kycRequired)

            val local = FakeKycProviders(mapOf(DIDIT to ramp.didit, "log" to InstantKycProvider()))
            val laptop = SettingsBook(MemorySettings(), RecordingAlerts(), ramp.providers, local, ramp.clock)
            assertEquals("log", laptop.of(TESTNET).kycProvider, "a developer's machine approves at once")
        }

    @Test
    fun `staff's KYC settings are validated, naming the field`() =
        runTest {
            val current = ramp.getSettings(TESTNET).value()

            suspend fun field(change: (RampSettings) -> RampSettings): String? =
                (ramp.updateSettings(change(current), null, "staff").error() as? RampError.SettingsInvalid)
                    ?.details
                    ?.fields
                    ?.single()
                    ?.field

            assertEquals("kycProvider", field { it.copy(kycProvider = "log") }, "not offered on a server")
            assertEquals("kycWorkflowUser", field { it.copy(kycWorkflowUser = "not an id") })
            assertEquals("kycWorkflowMerchant", field { it.copy(kycWorkflowMerchant = "x") })
            assertEquals("merchantPortalUrl", field { it.copy(merchantPortalUrl = "merchants.circaso.com") })
            assertEquals("merchantPortalUrl", field { it.copy(merchantPortalUrl = "https://merchants.circaso.com/") })
            assertEquals("kycAttemptsPerDay", field { it.copy(kycAttemptsPerDay = 0) })
            assertEquals("kycPendingHours", field { it.copy(kycPendingHours = 673) })

            val changed =
                current.copy(
                    kycRequired = true,
                    kycEnvironment = KycEnvironment.LIVE,
                    kycWorkflowUser = "",
                    kycAttemptsPerDay = 5,
                    kycPendingHours = 48,
                    merchantPortalUrl = "https://merchants.test",
                )
            val saved = ramp.updateSettings(changed, current.version, "staff").value().after
            assertEquals(KycEnvironment.LIVE to "", saved.kycEnvironment to saved.kycWorkflowUser)
            assertEquals(5 to 48, saved.kycAttemptsPerDay to saved.kycPendingHours)
            assertEquals("https://merchants.test/kyc/done", ramp.book.of(TESTNET).merchantKycCallbackUrl)
        }

    private fun com.circaso.ramp.domain.models.RampStatus.canTradeNow(): Boolean =
        kyc != KycStanding.NONE && !blocked && accountActive && merchantOnline && openOrderId == null
}
