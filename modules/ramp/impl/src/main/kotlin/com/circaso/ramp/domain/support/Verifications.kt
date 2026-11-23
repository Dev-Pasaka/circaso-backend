package com.circaso.ramp.domain.support

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.KycDecision
import com.circaso.integrations.api.KycDeclineReason
import com.circaso.integrations.api.KycEnvironment
import com.circaso.integrations.api.KycProviders
import com.circaso.integrations.api.KycSession
import com.circaso.integrations.api.KycSessionRequest
import com.circaso.integrations.api.KycStatus
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Block
import com.circaso.ramp.domain.models.BlockReason
import com.circaso.ramp.domain.models.KycLink
import com.circaso.ramp.domain.models.KycState
import com.circaso.ramp.domain.models.KycView
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.models.SubjectKind
import com.circaso.ramp.domain.models.Verification
import com.circaso.ramp.domain.models.VerificationChange
import com.circaso.ramp.domain.models.VerificationReason
import com.circaso.ramp.domain.models.VerificationStatus
import com.circaso.ramp.domain.models.VerifiedOutcome
import com.circaso.ramp.domain.ports.FrameSender
import com.circaso.ramp.domain.ports.IdentityKeys
import com.circaso.ramp.domain.ports.KycGate
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.ports.Transactor
import com.circaso.ramp.domain.repositories.BlockRepository
import com.circaso.ramp.domain.repositories.VerificationRepository
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Identity verification for the ramp (decision 2026-11-22): who passed KYC, starting and resuming a check, and
 * applying what the provider decided.
 *
 * **A verification belongs to a provider environment, not a network.** A sandbox approval is simulated and must never
 * count where real people are verified; each network's settings name the environment it trusts, and a subject is
 * verified on a network when their row in that environment says so.
 *
 * **Only the provider's own answer moves a verification.** A webhook, the sweep and the app's status read all end in
 * [refresh], which asks the provider for the session's decision and applies it through the repository's guards, so
 * the same outcome is never applied twice and an old session's late news never touches a newer one.
 *
 * **Identity.** On a live approval the document's country, type and number are turned into the identity key and
 * dropped. A key that is barred refuses the account and bars it too; a key that already verified another account of
 * the same kind refuses this one (`identity_in_use`). In the sandbox no key is kept at all: its checks read the same
 * sample document for everybody, so a key there would make every tester the same person.
 */
class Verifications(
    private val rows: VerificationRepository,
    private val blocks: BlockRepository,
    private val providers: KycProviders,
    private val identities: IdentityKeys,
    private val settings: SettingsBook,
    private val transactor: Transactor,
    private val frames: FrameSender,
    private val alerts: RampAlerts,
    private val clock: Clock,
) : KycGate {
    /** How asking the provider about a session went. */
    enum class Asked {
        /** The provider answered, and what it said was applied. */
        ANSWERED,

        /** The provider couldn't be reached: nothing changed, and asking again later may work. */
        DOWN,

        /** Nobody can be asked any more: the provider isn't offered here, or its environment has no keys. */
        GONE,
    }

    data class Refreshed(val verification: Verification, val asked: Asked)

    override suspend fun isVerified(subject: Subject, environment: KycEnvironment): Boolean =
        rows.find(subject, environment)?.status == VerificationStatus.VERIFIED

    override suspend fun isBlocked(userId: UserId, environment: KycEnvironment): Boolean =
        blocks.isBlocked(userId, rows.find(Subject.user(userId), environment)?.identityKey)

    /** The subject's verification as [current] network's settings see it. */
    suspend fun view(subject: Subject, current: RampSettings): KycView =
        view(rows.find(subject, current.kycEnvironment), current)

    /**
     * What the app is told about [row] (null: never started) under [current]. [withLink] hands out the open check's
     * link, which only the calls that start or resume a check do.
     */
    fun view(
        row: Verification?,
        current: RampSettings,
        withLink: Boolean = false,
    ): KycView {
        val now = clock.instant()
        val capped = row != null && row.attemptsOn(Periods.merchantDay(now)) >= current.kycAttemptsPerDay
        val needsNewSession =
            when (row?.status) {
                null -> true
                VerificationStatus.PENDING -> !row.hasLiveLink(now)
                VerificationStatus.DECLINED, VerificationStatus.EXPIRED -> row.reason?.canRetry != false
                VerificationStatus.IN_REVIEW, VerificationStatus.VERIFIED -> false
            }
        val canContinue = row?.hasLiveLink(now) == true
        return KycView(
            state = row?.status?.let { KycState.valueOf(it.name) } ?: KycState.NONE,
            required = current.enforcesKyc,
            canStart = canContinue || (needsNewSession && !capped),
            reason = row?.reason,
            retryAt = if (needsNewSession && capped) Periods.nextMerchantDay(now) else null,
            link =
                row
                    ?.takeIf { withLink && canContinue }
                    ?.let { KycLink(it.sessionToken, it.sessionUrl, it.sessionExpiresAt) },
        )
    }

    /**
     * Starts a check for [subject], or hands back the one still open: a tap never makes a second session while the
     * first one's link works. A declined or expired check may be started again, up to the settings' cap a day; one
     * that can't be retried (an identity in use, a barred identity) answers its state with no link. [callbackUrl] is
     * where the provider's hosted page returns to (the merchant portal); the app passes none.
     */
    suspend fun start(
        subject: Subject,
        current: RampSettings,
        callbackUrl: String? = null,
    ): Resource<KycView> {
        val now = clock.instant()
        val environment = current.kycEnvironment
        val row = rows.find(subject, environment)
        val before = view(row, current, withLink = true)
        if (before.link != null) return Resource.success(before)
        val startsAgain = row == null || row.status == VerificationStatus.PENDING || row.status.isRetryable
        if (!startsAgain || row?.reason?.canRetry == false) return Resource.success(before)
        if (!before.canStart) return Resource.error(RampError.KycAttemptsExceeded(secondsUntil(before.retryAt, now)))

        val session =
            when (val opened = open(subject, current, callbackUrl)) {
                is Resource.Error -> return opened
                is Resource.Success -> opened.data
            }
        val started = started(row, subject, current, session, now)
        rows.save(started)
        // A provider that needs nothing from the person (the local stand-in) has decided already: read it now.
        val needsThePerson = session.token != null || session.url != null
        val after = if (needsThePerson) started else refresh(started).verification
        return Resource.success(view(after, current, withLink = true))
    }

    /**
     * Asks the provider what it decided about [row]'s session and applies it. Nothing changes when the provider
     * can't be asked, or when its answer is about somebody else.
     */
    suspend fun refresh(row: Verification): Refreshed {
        val now = clock.instant()
        val sessionId = row.sessionId ?: return Refreshed(row, Asked.GONE)
        val provider = providers.named(row.provider)
        if (provider == null || !provider.isAvailable(row.environment)) {
            rows.touch(row.subject, row.environment, now)
            return Refreshed(row, Asked.GONE)
        }
        val decision =
            when (val asked = provider.decision(row.environment, sessionId)) {
                is Resource.Success -> asked.data
                is Resource.Error -> return unanswered(row, asked.error is IntegrationError.Rejected, now)
            }
        if (decision.subject != null && decision.subject != row.subject.reference) {
            // The provider's session is about another reference than the one we started it for: never applied.
            alerts.stuck("kyc.decision", "subject_mismatch")
            rows.touch(row.subject, row.environment, now)
            return Refreshed(row, Asked.ANSWERED)
        }
        return Refreshed(apply(row, sessionId, decision, now), Asked.ANSWERED)
    }

    /** [refresh], when the row is unfinished and the provider wasn't asked in the last [notWithin]. */
    suspend fun refreshIfDue(row: Verification?, notWithin: Duration = STATUS_RECHECK): Verification? {
        if (row == null || !row.status.isOpen) return row
        if (row.checkedAt.plus(notWithin).isAfter(clock.instant())) return row
        return refresh(row).verification
    }

    /** Ends a check nobody finished in time, so its subject can start another. Tells the user when it moved. */
    suspend fun expire(row: Verification): Verification {
        val sessionId = row.sessionId ?: return row
        val expired = VerificationChange(VerificationStatus.EXPIRED, VerificationReason.SESSION_EXPIRED)
        return moved(row, sessionId, expired, clock.instant())
    }

    private suspend fun open(
        subject: Subject,
        current: RampSettings,
        callbackUrl: String?,
    ): Resource<KycSession> {
        val environment = current.kycEnvironment
        val workflow = if (subject.kind == SubjectKind.USER) current.kycWorkflowUser else current.kycWorkflowMerchant
        val provider = providers.named(current.kycProvider)
        // Live approvals need the identity key, so a server that can't compute one doesn't start a live check.
        val canKeyIdentities = environment != KycEnvironment.LIVE || identities.isAvailable
        if (provider == null || workflow.isBlank() || !provider.isAvailable(environment) || !canKeyIdentities) {
            return Resource.error(RampError.KycNotConfigured)
        }
        val request = KycSessionRequest(environment, subject.reference, workflow, callbackUrl, current.kycPendingFor)
        return when (val opened = provider.startSession(request)) {
            is Resource.Success -> {
                opened
            }

            is Resource.Error -> {
                val notConfigured = opened.error is IntegrationError.NotConfigured
                if (opened.error is IntegrationError.Rejected) alerts.stuck("kyc.start", "refused_by_provider")
                Resource.error(
                    if (notConfigured) RampError.KycNotConfigured else RampError.KycUnavailable,
                    opened.cause,
                )
            }
        }
    }

    /** The row once [session] has been started: pending, with the link, and one more attempt counted today. */
    private fun started(
        row: Verification?,
        subject: Subject,
        current: RampSettings,
        session: KycSession,
        now: Instant,
    ): Verification {
        val today = Periods.merchantDay(now)
        // The provider keeps one unfinished session per subject: getting the same one back is not another attempt.
        val isNew = row?.sessionId != session.sessionId
        val count = if (isNew) 1 else 0
        return Verification(
            subject = subject,
            environment = current.kycEnvironment,
            status = VerificationStatus.PENDING,
            reason = null,
            provider = current.kycProvider,
            sessionId = session.sessionId,
            sessionToken = session.token,
            sessionUrl = session.url,
            sessionExpiresAt = session.expiresAt ?: now.plus(current.kycPendingFor),
            identityKey = row?.identityKey,
            attempts = (row?.attempts ?: 0) + count,
            attemptDay = today,
            attemptsOnDay = (row?.attemptsOn(today) ?: 0) + count,
            startedAt = if (isNew) now else row?.startedAt ?: now,
            checkedAt = now,
            verifiedAt = null,
            createdAt = row?.createdAt ?: now,
            updatedAt = now,
        )
    }

    /**
     * The provider didn't answer with a decision. [refused] is its "no such session": an unfinished check whose
     * session is gone can never finish, so it ends as expired and the person may start again.
     */
    private suspend fun unanswered(
        row: Verification,
        refused: Boolean,
        now: Instant,
    ): Refreshed {
        if (refused && row.status.isOpen) return Refreshed(expire(row), Asked.ANSWERED)
        rows.touch(row.subject, row.environment, now)
        return Refreshed(row, if (refused) Asked.ANSWERED else Asked.DOWN)
    }

    private suspend fun apply(
        row: Verification,
        sessionId: String,
        decision: KycDecision,
        now: Instant,
    ): Verification {
        val change =
            when (decision.status) {
                KycStatus.APPROVED -> {
                    return approve(row, sessionId, decision, now)
                }

                KycStatus.PENDING -> {
                    pendingAgain(row)
                }

                KycStatus.RESUBMIT -> {
                    pending(VerificationReason.RESUBMISSION_REQUESTED)
                }

                KycStatus.IN_REVIEW -> {
                    VerificationChange(VerificationStatus.IN_REVIEW)
                }

                KycStatus.DECLINED -> {
                    declined(reasonOf(decision.declineReason))
                }

                KycStatus.EXPIRED -> {
                    VerificationChange(VerificationStatus.EXPIRED, VerificationReason.SESSION_EXPIRED)
                }

                KycStatus.DOCUMENT_EXPIRED -> {
                    VerificationChange(VerificationStatus.EXPIRED, VerificationReason.DOCUMENT_EXPIRED)
                }
            }
        if (change == null) {
            rows.touch(row.subject, row.environment, now)
            return row
        }
        return moved(row, sessionId, change, now)
    }

    /**
     * The provider says the person is (still, or again) going through the check. A check already pending stays as it
     * is, and so does one we ended for running out of time: the provider ends it too a moment later.
     */
    private fun pendingAgain(row: Verification): VerificationChange? {
        val endedForTime =
            row.status == VerificationStatus.EXPIRED && row.reason == VerificationReason.SESSION_EXPIRED
        return if (row.status == VerificationStatus.PENDING || endedForTime) null else pending(null)
    }

    private suspend fun approve(
        row: Verification,
        sessionId: String,
        decision: KycDecision,
        now: Instant,
    ): Verification {
        if (row.status == VerificationStatus.VERIFIED) {
            rows.touch(row.subject, row.environment, now)
            return row
        }
        val identityKey =
            if (row.environment == KycEnvironment.LIVE) {
                val document =
                    decision.document
                        ?: return moved(row, sessionId, declined(VerificationReason.DOCUMENT_UNREADABLE), now)
                identities.of(document) ?: return row.also { alerts.stuck("kyc.identity_key", "no_master_key") }
            } else {
                null
            }
        if (identityKey != null && blocks.hasIdentity(identityKey)) return refuseBarred(row, sessionId, now)
        return when (val outcome = rows.verify(row.subject, row.environment, sessionId, identityKey, now)) {
            is VerifiedOutcome.Moved -> outcome.verification.also { tell(it) }
            VerifiedOutcome.AlreadyThere -> current(row)
            VerifiedOutcome.IdentityInUse -> moved(row, sessionId, declined(VerificationReason.IDENTITY_IN_USE), now)
        }
    }

    /** A barred identity came back on another account: that account is refused, and a user is barred too. */
    private suspend fun refuseBarred(
        row: Verification,
        sessionId: String,
        now: Instant,
    ): Verification {
        val refused =
            transactor.run("kyc.refuse-barred") {
                val moved =
                    rows.move(
                        row.subject,
                        row.environment,
                        sessionId,
                        declined(VerificationReason.BLOCKED),
                        now,
                    )
                val userId = row.subject.userId
                if (moved != null && userId != null) {
                    blocks.add(Block(Uuid7.generate(), userId, null, BlockReason.IDENTITY, "", null, SYSTEM, now))
                }
                moved
            } ?: return current(row)
        tell(refused)
        return refused
    }

    private suspend fun moved(
        row: Verification,
        sessionId: String,
        change: VerificationChange,
        now: Instant,
    ): Verification {
        val moved = rows.move(row.subject, row.environment, sessionId, change, now) ?: return current(row)
        tell(moved)
        return moved
    }

    private suspend fun current(row: Verification): Verification = rows.find(row.subject, row.environment) ?: row

    /**
     * Tells a user their verification moved: one frame for each network that verifies in the row's environment, with
     * the state as that network's settings see it. Merchants get no frames: the portal reads RM2.
     */
    private suspend fun tell(row: Verification) {
        val userId = row.subject.userId ?: return
        Network.entries.forEach { network ->
            val current = settings.of(network)
            if (current.kycEnvironment == row.environment) frames.kycUpdated(userId, network, view(row, current))
        }
    }

    private fun pending(reason: VerificationReason?) = VerificationChange(VerificationStatus.PENDING, reason)

    private fun declined(reason: VerificationReason) = VerificationChange(VerificationStatus.DECLINED, reason)

    /** Our code for the provider's reason. A screening hit is never named to the person. */
    private fun reasonOf(reason: KycDeclineReason?): VerificationReason =
        when (reason) {
            KycDeclineReason.DOCUMENT -> VerificationReason.DOCUMENT
            KycDeclineReason.DOCUMENT_EXPIRED -> VerificationReason.DOCUMENT_EXPIRED
            KycDeclineReason.LIVENESS -> VerificationReason.LIVENESS
            KycDeclineReason.FACE_MATCH -> VerificationReason.FACE_MATCH
            KycDeclineReason.SCREENING -> VerificationReason.NOT_APPROVED
            KycDeclineReason.OTHER, null -> VerificationReason.DECLINED
        }

    private fun secondsUntil(at: Instant?, now: Instant): Long =
        at?.let { Duration.between(now, it).seconds.coerceAtLeast(1) } ?: SECONDS_A_DAY

    companion object {
        /** Who made a block the ramp made by itself. */
        const val SYSTEM = "system"

        /** How often the app's own status read may ask the provider about an unfinished check. */
        val STATUS_RECHECK: Duration = Duration.ofSeconds(5)
        private const val SECONDS_A_DAY = 86_400L
    }
}
