package com.circaso.ramp

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.DatabaseException
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.integrations.api.KycDocument
import com.circaso.integrations.api.KycEnvironment
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.data.repositories.BlockRepositoryImpl
import com.circaso.ramp.data.repositories.DatabaseTransactor
import com.circaso.ramp.data.repositories.SettingsRepositoryImpl
import com.circaso.ramp.data.repositories.VerificationRepositoryImpl
import com.circaso.ramp.data.support.EnvelopeVault
import com.circaso.ramp.data.support.HmacIdentityKeys
import com.circaso.ramp.di.rampDatabaseSpec
import com.circaso.ramp.domain.models.Block
import com.circaso.ramp.domain.models.BlockReason
import com.circaso.ramp.domain.models.KycState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.models.SubjectKind
import com.circaso.ramp.domain.models.Verification
import com.circaso.ramp.domain.models.VerificationChange
import com.circaso.ramp.domain.models.VerificationReason
import com.circaso.ramp.domain.models.VerificationStatus
import com.circaso.ramp.domain.models.VerifiedOutcome
import com.circaso.ramp.domain.support.SettingsBook
import com.circaso.ramp.domain.support.Verifications
import com.circaso.ramp.domain.usecases.BlockUser
import com.circaso.shared.Network
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Identity verification on PostgreSQL (the ramp's own Flyway scripts, `V4__kyc.sql` among them): the row per subject
 * and environment, the guards outcomes move through, the unique index that lets one identity verify one account of
 * each kind, the block list's claims, and the settings the migration adds.
 */
class KycDataTest {
    private val database: Database = TestPostgres.database(rampDatabaseSpec)
    private val rows = VerificationRepositoryImpl(database)
    private val blocks = BlockRepositoryImpl(database)
    private val settingsRows = SettingsRepositoryImpl(database)
    private val transactor = DatabaseTransactor(database)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val clock = TestClock(now)
    private val alerts = RecordingAlerts()
    private val frames = RecordingFrames()
    private val didit = FakeKycProvider(clock)
    private val kycProviders = FakeKycProviders(mapOf(DIDIT to didit))
    private val book = SettingsBook(settingsRows, alerts, FakeProviders(emptyMap()), kycProviders, clock)
    private val identities = HmacIdentityKeys { EnvelopeVault.masterKey(MASTER_KEY) }
    private val verifications =
        Verifications(rows, blocks, kycProviders, identities, book, transactor, frames, alerts, clock)

    @BeforeTest
    fun clean() =
        runBlocking<Unit> {
            TestPostgres.truncate("ramp")
            book.invalidate()
        }

    private fun sql(statement: String): Int =
        TestPostgres.superuser { connection -> connection.createStatement().use { it.executeUpdate(statement) } }

    private fun count(query: String): Int =
        TestPostgres.superuser { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(query).use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

    private fun pending(
        subject: Subject,
        environment: KycEnvironment = KycEnvironment.LIVE,
        sessionId: String = "session-${Uuid7.generate()}",
    ) = Verification(
        subject = subject,
        environment = environment,
        status = VerificationStatus.PENDING,
        reason = null,
        provider = DIDIT,
        sessionId = sessionId,
        sessionToken = "token",
        sessionUrl = "https://verify.test/session/token",
        sessionExpiresAt = now.plusSeconds(86_400),
        identityKey = null,
        attempts = 1,
        attemptDay = LocalDate.parse("2026-11-22"),
        attemptsOnDay = 1,
        startedAt = now,
        checkedAt = now,
        verifiedAt = null,
        createdAt = now,
        updatedAt = now,
    )

    private suspend fun started(subject: Subject, environment: KycEnvironment = KycEnvironment.LIVE): Verification =
        pending(subject, environment).also { rows.save(it) }

    private fun user() = Subject.user(UserId.new())

    @Test
    fun `a verification is one row per subject and environment, found by its session, written whole`() =
        runBlocking<Unit> {
            val amina = user()
            val live = started(amina)
            val sandbox = started(amina, KycEnvironment.SANDBOX)

            assertEquals(live, rows.find(amina, KycEnvironment.LIVE))
            assertEquals(sandbox, rows.bySession(sandbox.sessionId!!))
            assertEquals(
                listOf(KycEnvironment.SANDBOX, KycEnvironment.LIVE),
                rows.ofSubject(amina).map { it.environment },
            )
            assertNull(rows.find(user(), KycEnvironment.LIVE))
            assertNull(rows.bySession("nobody's"))

            // A new session replaces the row's session and counts; the first day it was made stays.
            val retried =
                live.copy(sessionId = "session-2", attempts = 2, attemptsOnDay = 2, createdAt = now.plusSeconds(60))
            rows.save(retried)
            val kept = rows.find(amina, KycEnvironment.LIVE)!!
            assertEquals("session-2" to 2, kept.sessionId to kept.attempts)
            assertEquals(now, kept.createdAt)
            assertNull(rows.bySession(live.sessionId!!), "the old session finds nobody now")
            assertEquals(2, count("SELECT count(*) FROM ramp.verifications"))
        }

    @Test
    fun `a move is guarded on the session, and only the caller that moved it is told`() =
        runBlocking<Unit> {
            val amina = user()
            val row = started(amina)
            val declined = VerificationChange(VerificationStatus.DECLINED, VerificationReason.FACE_MATCH)

            val moved = assertNotNull(rows.move(amina, row.environment, row.sessionId!!, declined, now.plusSeconds(5)))
            assertEquals(VerificationStatus.DECLINED to VerificationReason.FACE_MATCH, moved.status to moved.reason)
            assertEquals(now.plusSeconds(5), moved.checkedAt)
            assertNull(rows.move(amina, row.environment, row.sessionId!!, declined, now), "already exactly there")
            assertNull(rows.move(amina, row.environment, "another-session", declined, now), "not this row's session")
            assertNull(rows.move(amina, KycEnvironment.SANDBOX, row.sessionId!!, declined, now), "another environment")

            // The same status for another reason is a move; a status with no reason clears it.
            val other = VerificationChange(VerificationStatus.DECLINED, VerificationReason.DOCUMENT)
            assertEquals(
                VerificationReason.DOCUMENT,
                rows.move(amina, row.environment, row.sessionId!!, other, now)?.reason,
            )
            val review = VerificationChange(VerificationStatus.IN_REVIEW)
            assertNull(rows.move(amina, row.environment, row.sessionId!!, review, now)?.reason)
            assertNull(rows.move(amina, row.environment, row.sessionId!!, review, now))
            val resubmit = VerificationChange(VerificationStatus.PENDING, VerificationReason.RESUBMISSION_REQUESTED)
            assertNotNull(rows.move(amina, row.environment, row.sessionId!!, resubmit, now))
            assertNotNull(
                rows.move(amina, row.environment, row.sessionId!!, VerificationChange(VerificationStatus.PENDING), now),
            )
        }

    @Test
    fun `submitted is kept with the row, is a move of its own, and is cleared by every other outcome`() =
        runBlocking<Unit> {
            val amina = user()
            val row = started(amina)
            assertFalse(rows.find(amina, row.environment)!!.isSubmitted, "a started check is not a finished one")
            val submitted = VerificationChange(VerificationStatus.PENDING, isSubmitted = true)

            val moved = assertNotNull(rows.move(amina, row.environment, row.sessionId!!, submitted, now))
            assertTrue(moved.isSubmitted && moved.status == VerificationStatus.PENDING)
            assertNotNull(moved.sessionToken, "the link is kept: a step may be asked for again")
            assertNull(rows.move(amina, row.environment, row.sessionId!!, submitted, now), "already exactly there")
            assertTrue(rows.bySession(row.sessionId!!)!!.isSubmitted)

            // Back in the check is a move too; and any other outcome clears it.
            val pending = VerificationChange(VerificationStatus.PENDING)
            assertFalse(assertNotNull(rows.move(amina, row.environment, row.sessionId!!, pending, now)).isSubmitted)
            rows.move(amina, row.environment, row.sessionId!!, submitted, now)
            val review = VerificationChange(VerificationStatus.IN_REVIEW)
            assertFalse(assertNotNull(rows.move(amina, row.environment, row.sessionId!!, review, now)).isSubmitted)

            val bob = user()
            val bobs = started(bob)
            rows.move(bob, bobs.environment, bobs.sessionId!!, submitted, now)
            val verified = rows.verify(bob, bobs.environment, bobs.sessionId!!, "key-bob", now)
            assertFalse(assertIs<VerifiedOutcome.Moved>(verified).verification.isSubmitted)

            // Written whole by a new session: a new check starts unfinished.
            rows.move(amina, row.environment, row.sessionId!!, submitted, now)
            rows.save(row.copy(sessionId = "session-2"))
            assertFalse(rows.find(amina, row.environment)!!.isSubmitted)
        }

    @Test
    fun `racing outcomes for one session are applied once`() =
        runBlocking<Unit> {
            val amina = user()
            val row = started(amina)
            val declined = VerificationChange(VerificationStatus.DECLINED, VerificationReason.DOCUMENT)

            val moves =
                List(
                    10,
                ) { async { rows.move(amina, row.environment, row.sessionId!!, declined, now) } }.awaitAll()
            assertEquals(1, moves.count { it != null })

            val bob = user()
            val bobs = started(bob)
            val verifies =
                List(10) { async { rows.verify(bob, bobs.environment, bobs.sessionId!!, "key-bob", now) } }.awaitAll()
            assertEquals(1, verifies.count { it is VerifiedOutcome.Moved })
            assertEquals(9, verifies.count { it == VerifiedOutcome.AlreadyThere })
        }

    @Test
    fun `verifying claims the identity, clears the link, and one identity verifies one account of each kind`() =
        runBlocking<Unit> {
            val amina = user()
            val row = started(amina)

            val outcome = rows.verify(amina, row.environment, row.sessionId!!, "identity-1", now.plusSeconds(9))

            val verified = assertIs<VerifiedOutcome.Moved>(outcome).verification
            assertEquals(VerificationStatus.VERIFIED to "identity-1", verified.status to verified.identityKey)
            assertEquals(now.plusSeconds(9), verified.verifiedAt)
            assertNull(verified.sessionToken)
            assertNull(verified.sessionUrl)
            assertNull(verified.sessionExpiresAt)
            assertEquals(row.sessionId, verified.sessionId, "the session stays: a later webhook still finds the row")

            // A second user with the same identity loses the claim, and nothing of theirs is written.
            val bob = user()
            val bobs = started(bob)
            assertEquals(
                VerifiedOutcome.IdentityInUse,
                rows.verify(bob, bobs.environment, bobs.sessionId!!, "identity-1", now),
            )
            val after = rows.find(bob, KycEnvironment.LIVE)!!
            assertEquals(VerificationStatus.PENDING, after.status)
            assertNull(after.identityKey)

            // A merchant is another kind, the sandbox another environment: neither collides.
            val merchant = Subject.merchant(MerchantId.new())
            val merchants = started(merchant)
            assertIs<VerifiedOutcome.Moved>(
                rows.verify(merchant, merchants.environment, merchants.sessionId!!, "identity-1", now),
            )
            val tester = started(bob, KycEnvironment.SANDBOX)
            assertIs<VerifiedOutcome.Moved>(rows.verify(bob, tester.environment, tester.sessionId!!, "identity-1", now))

            // No key (the sandbox) claims nothing: any number of subjects verify.
            val carol = started(user(), KycEnvironment.SANDBOX)
            val dan = started(user(), KycEnvironment.SANDBOX)
            assertIs<VerifiedOutcome.Moved>(rows.verify(carol.subject, carol.environment, carol.sessionId!!, null, now))
            assertIs<VerifiedOutcome.Moved>(rows.verify(dan.subject, dan.environment, dan.sessionId!!, null, now))
        }

    @Test
    fun `two accounts racing to verify one identity - exactly one wins`() =
        runBlocking<Unit> {
            val contenders = List(8) { started(user()) }

            val outcomes =
                contenders
                    .map { async { rows.verify(it.subject, it.environment, it.sessionId!!, "one-identity", now) } }
                    .awaitAll()

            assertEquals(1, outcomes.count { it is VerifiedOutcome.Moved })
            assertEquals(7, outcomes.count { it == VerifiedOutcome.IdentityInUse })
            assertEquals(1, count("SELECT count(*) FROM ramp.verifications WHERE identity_key = 'one-identity'"))
        }

    @Test
    fun `an identity survives a new session, and a reset frees it for another account`() =
        runBlocking<Unit> {
            val amina = user()
            val row = started(amina)
            rows.verify(amina, row.environment, row.sessionId!!, "identity-1", now)

            // Her document expired and she starts again: the row keeps her identity while the new check runs.
            rows.save(pending(amina, sessionId = "session-again"))
            val again = rows.find(amina, KycEnvironment.LIVE)!!
            assertEquals(VerificationStatus.PENDING to "identity-1", again.status to again.identityKey)

            val bob = started(user())
            assertEquals(
                VerifiedOutcome.IdentityInUse,
                rows.verify(bob.subject, bob.environment, bob.sessionId!!, "identity-1", now),
            )
            assertEquals(again, rows.delete(amina, KycEnvironment.LIVE))
            assertNull(rows.delete(amina, KycEnvironment.LIVE))
            assertIs<VerifiedOutcome.Moved>(
                rows.verify(bob.subject, bob.environment, bob.sessionId!!, "identity-1", now),
            )
        }

    @Test
    fun `the sweep's read is the unfinished rows the provider wasn't asked about lately, oldest first`() =
        runBlocking<Unit> {
            val old = started(user()).also { rows.touch(it.subject, it.environment, now.minusSeconds(900)) }
            val older = started(user()).also { rows.touch(it.subject, it.environment, now.minusSeconds(1_800)) }
            val fresh = started(user())
            val reviewed = started(user()).also { rows.touch(it.subject, it.environment, now.minusSeconds(900)) }
            rows.move(
                reviewed.subject,
                reviewed.environment,
                reviewed.sessionId!!,
                VerificationChange(VerificationStatus.IN_REVIEW),
                now.minusSeconds(900),
            )
            val done =
                started(
                    user(),
                ).also { rows.verify(it.subject, it.environment, it.sessionId!!, null, now.minusSeconds(900)) }

            val stale = rows.stale(setOf(VerificationStatus.PENDING), now.minusSeconds(300), 10)

            assertEquals(listOf(older.subject, old.subject), stale.map { it.subject })
            assertEquals(
                listOf(
                    older.subject,
                ),
                rows.stale(setOf(VerificationStatus.PENDING), now.minusSeconds(300), 1).map {
                    it.subject
                },
            )
            assertEquals(
                listOf(reviewed.subject),
                rows.stale(setOf(VerificationStatus.IN_REVIEW), now.minusSeconds(300), 10).map { it.subject },
            )
            assertTrue(fresh.subject !in stale.map { it.subject } && done.subject !in stale.map { it.subject })
        }

    @Test
    fun `the table refuses a status, an environment or a kind the code doesn't know`() {
        runBlocking { started(user()) }

        assertFailsWith<SQLException> { sql("UPDATE ramp.verifications SET status = 'NONE'") }
        assertFailsWith<SQLException> { sql("UPDATE ramp.verifications SET environment = 'STAGING'") }
        assertFailsWith<SQLException> { sql("UPDATE ramp.verifications SET subject_kind = 'STAFF'") }
    }

    // Blocks

    private fun block(
        userId: UserId? = null,
        identityKey: String? = null,
        source: UserId? = null,
        at: Instant = now,
    ) = Block(Uuid7.generate(at), userId, identityKey, BlockReason.OPS, "why", source, "staff-1", at)

    @Test
    fun `a block is one claim per user and per identity, looked up either way`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val bob = UserId.new()

            assertTrue(blocks.add(block(userId = amina)))
            assertFalse(blocks.add(block(userId = amina)), "already barred")
            assertTrue(blocks.add(block(identityKey = "identity-1", source = amina)))
            assertFalse(blocks.add(block(identityKey = "identity-1")))

            assertEquals("why", blocks.ofUser(amina)?.note)
            assertNull(blocks.ofUser(bob))
            assertTrue(blocks.isBlocked(amina, null))
            assertFalse(blocks.isBlocked(bob, null))
            assertTrue(blocks.isBlocked(bob, "identity-1"), "another account, the barred identity")
            assertFalse(blocks.isBlocked(bob, "identity-2"))
            assertTrue(blocks.hasIdentity("identity-1"))
            assertFalse(blocks.hasIdentity("identity-2"))
        }

    @Test
    fun `lifting a user's block lifts the identity blocks that came from it, and no one else's`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val bob = UserId.new()
            blocks.add(block(userId = amina))
            blocks.add(block(identityKey = "aminas", source = amina))
            blocks.add(block(userId = bob))
            blocks.add(block(identityKey = "bobs", source = bob))

            assertEquals(2, blocks.removeUser(amina))

            assertFalse(blocks.isBlocked(amina, "aminas"))
            assertTrue(blocks.isBlocked(bob, null) && blocks.hasIdentity("bobs"))
            assertEquals(0, blocks.removeUser(amina))
        }

    @Test
    fun `the list is barred users, newest first, a page at a time - never an identity's own row`() =
        runBlocking<Unit> {
            val users = List(5) { UserId.new() }
            users.forEachIndexed {
                index,
                userId,
                ->
                blocks.add(block(userId = userId, at = now.plusSeconds(index.toLong())))
            }
            blocks.add(block(identityKey = "identity-1", source = users.first(), at = now.plusSeconds(60)))

            val first = blocks.users(null, 2)
            val second = blocks.users(PageCursor(first.last().createdAt, first.last().blockId), 2)
            val third = blocks.users(PageCursor(second.last().createdAt, second.last().blockId), 2)

            assertEquals(users.reversed(), (first + second + third).map { it.userId })
            assertTrue((first + second + third).none { it.identityKey != null })
        }

    @Test
    fun `a block row names a user or an identity, never both and never neither`() {
        val id = Uuid7.generate()
        val both =
            "INSERT INTO ramp.blocks (block_id, user_id, identity_key, reason, created_by, created_at) " +
                "VALUES ('$id', '${Uuid7.generate()}', 'k', 'OPS', 's', now())"
        val neither =
            "INSERT INTO ramp.blocks (block_id, reason, created_by, created_at) VALUES ('$id', 'OPS', 's', now())"
        val unknown =
            "INSERT INTO ramp.blocks (block_id, identity_key, reason, created_by, created_at) " +
                "VALUES ('$id', 'k', 'BECAUSE', 's', now())"

        assertFailsWith<SQLException> { sql(both) }
        assertFailsWith<SQLException> { sql(neither) }
        assertFailsWith<SQLException> { sql(unknown) }
    }

    // The whole path on PostgreSQL

    @Test
    fun `a live approval, a second account on the same identity, and a barred identity, end to end`() =
        runBlocking<Unit> {
            val current =
                book
                    .of(
                        TESTNET,
                    ).copy(kycRequired = true, kycEnvironment = KycEnvironment.LIVE, kycWorkflowUser = "wf-user")
            settingsRows.update(current, null, "staff", now)
            book.invalidate()
            val settings = book.of(TESTNET)
            val id = KycDocument("KEN", "Identity Card", "12345678")
            val amina = UserId.new()
            val bob = UserId.new()

            suspend fun approve(userId: UserId, document: KycDocument) {
                verifications.start(Subject.user(userId), settings)
                val session = didit.sessionOf(Subject.user(userId))
                didit.approve(session, document)
                verifications.refresh(rows.bySession(session)!!)
            }

            approve(amina, id)
            assertTrue(verifications.isVerified(Subject.user(amina), KycEnvironment.LIVE))
            assertEquals(identities.of(id), rows.find(Subject.user(amina), KycEnvironment.LIVE)!!.identityKey)
            assertEquals(0, count("SELECT count(*) FROM ramp.verifications WHERE session_token IS NOT NULL"))

            approve(bob, KycDocument("ken", "identity card", "1234 5678"))
            val refused = verifications.view(Subject.user(bob), settings)
            assertEquals(KycState.DECLINED to VerificationReason.IDENTITY_IN_USE, refused.state to refused.reason)
            assertFalse(verifications.isVerified(Subject.user(bob), KycEnvironment.LIVE))

            // Staff bar Amina: her identity is barred with her, and the same person on a new account is barred too.
            BlockUser(rows, blocks, transactor, clock)(amina, BlockReason.OPS, "reversed a payment", "staff-1")
            assertTrue(verifications.isBlocked(amina, KycEnvironment.LIVE))
            assertEquals(1, count("SELECT count(*) FROM ramp.blocks WHERE identity_key IS NOT NULL"))
            rows.delete(Subject.user(amina), KycEnvironment.LIVE)
            val carol = UserId.new()
            approve(carol, id)
            val barred = verifications.view(Subject.user(carol), settings)
            assertEquals(KycState.DECLINED to VerificationReason.BLOCKED, barred.state to barred.reason)
            assertTrue(verifications.isBlocked(carol, KycEnvironment.LIVE))
            assertEquals(BlockReason.IDENTITY, blocks.ofUser(carol)?.reason)
            assertEquals(
                listOf(KycState.VERIFIED, KycState.DECLINED, KycState.DECLINED),
                frames.kyc.filter { it.second == TESTNET }.map { it.third.state },
            )
        }

    // The migration's settings

    @Test
    fun `a network is seeded with its KYC settings, and staff's changes are kept`() =
        runBlocking<Unit> {
            val testnet = book.of(TESTNET)
            val mainnet = book.of(Network.MAINNET)
            assertEquals(DIDIT to KycEnvironment.SANDBOX, testnet.kycProvider to testnet.kycEnvironment)
            assertEquals(RampSettings.SANDBOX_WORKFLOW_USER, testnet.kycWorkflowUser)
            assertEquals(RampSettings.SANDBOX_WORKFLOW_MERCHANT, testnet.kycWorkflowMerchant)
            assertEquals(KycEnvironment.LIVE to "", mainnet.kycEnvironment to mainnet.kycWorkflowUser)
            assertEquals("https://merchants.circaso.com", mainnet.merchantPortalUrl)

            val changed =
                testnet.copy(
                    kycRequired = true,
                    kycEnvironment = KycEnvironment.LIVE,
                    kycWorkflowUser = "wf-live-user",
                    kycWorkflowMerchant = "",
                    merchantPortalUrl = "https://merchants.test",
                    kycAttemptsPerDay = 5,
                    kycPendingHours = 48,
                )
            val saved = assertNotNull(settingsRows.update(changed, testnet.version, "staff", now))
            assertEquals(changed.copy(version = saved.version, updatedBy = "staff", updatedAt = now), saved)
            assertEquals(saved, settingsRows.load().getValue(TESTNET))
        }

    @Test
    fun `the table keeps the KYC settings within their ranges, and KYC on for mainnet`() =
        runBlocking<Unit> {
            val testnet = book.of(TESTNET)
            val mainnet = book.of(Network.MAINNET)

            assertFailsWith<DatabaseException> {
                settingsRows.update(
                    testnet.copy(kycAttemptsPerDay = 0),
                    null,
                    "s",
                    now,
                )
            }
            assertFailsWith<DatabaseException> {
                settingsRows.update(
                    testnet.copy(kycPendingHours = 673),
                    null,
                    "s",
                    now,
                )
            }
            assertFailsWith<DatabaseException> {
                settingsRows.update(
                    mainnet.copy(kycRequired = false),
                    null,
                    "s",
                    now,
                )
            }
            assertFailsWith<SQLException> { sql("UPDATE ramp.settings SET kyc_environment = 'STAGING'") }
        }

    @Test
    fun `a row written before the KYC columns existed gets working defaults - didit, live, three tries, a day`() {
        // What V4 does to a row that was already there: every new column has a default, so the row stays valid.
        sql(
            "INSERT INTO ramp.settings (network, state, chains, order_min_minor, order_max_minor, daily_limit_minor, " +
                "prompts_per_hour, fee_bps, merchant_share_bps, fee_settle_hour_utc, quote_seconds, " +
                "payment_window_seconds, kyc_required, callback_base_url, collections_provider, updated_at) " +
                "VALUES ('MAINNET', 'OFF', 'BASE', 1000, 500000, 2000000, 0, 150, 7000, 21, 60, 120, true, " +
                "'https://api.circaso.com', 'payhero', now())",
        )

        val row = runBlocking { settingsRows.load().getValue(Network.MAINNET) }

        assertEquals(DIDIT to KycEnvironment.LIVE, row.kycProvider to row.kycEnvironment)
        assertEquals("" to "", row.kycWorkflowUser to row.kycWorkflowMerchant)
        assertEquals("https://merchants.circaso.com", row.merchantPortalUrl)
        assertEquals(3 to 24, row.kycAttemptsPerDay to row.kycPendingHours)
    }
}
