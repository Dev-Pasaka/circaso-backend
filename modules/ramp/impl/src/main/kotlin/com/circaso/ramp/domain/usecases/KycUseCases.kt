package com.circaso.ramp.domain.usecases

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountStatus
import com.circaso.integrations.api.KycEnvironment
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Block
import com.circaso.ramp.domain.models.BlockReason
import com.circaso.ramp.domain.models.KycView
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.models.UserVerifications
import com.circaso.ramp.domain.models.Verification
import com.circaso.ramp.domain.models.VerificationStatus
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.ports.Transactor
import com.circaso.ramp.domain.repositories.BlockRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.VerificationRepository
import com.circaso.ramp.domain.support.Gates
import com.circaso.ramp.domain.support.SettingsBook
import com.circaso.ramp.domain.support.Verifications
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import java.time.Clock
import java.time.Duration

/**
 * R0: starts the user's identity check on a network, or hands back the one still open (a tap never makes a second
 * session while the first one's link works). The answer is the whole state and, while a check is open, its link: the
 * token the provider's native SDK starts with, and the hosted page as the fallback. Someone already verified, in
 * review, or declined for good gets their state with no link.
 */
class StartUserKyc(
    private val gates: Gates,
    private val accounts: AccountDirectory,
    private val verifications: Verifications,
) {
    suspend operator fun invoke(userId: UserId, network: Network): Resource<KycView> {
        val settings =
            when (val open = gates.open(userId, network)) {
                is Resource.Error -> return open
                is Resource.Success -> open.data
            }
        if (accounts.status(userId) != AccountStatus.ACTIVE) return Resource.error(RampError.AccountRestricted)
        if (gates.isBlocked(userId, settings)) return Resource.error(RampError.Blocked)
        return verifications.start(Subject.user(userId), settings)
    }
}

/**
 * R0's read: the user's verification on a network. While a check is unfinished the provider itself is asked (at most
 * every few seconds), so the app sees the outcome the moment the SDK closes, webhook or no webhook.
 */
class GetUserKyc(
    private val gates: Gates,
    private val rows: VerificationRepository,
    private val verifications: Verifications,
) {
    suspend operator fun invoke(userId: UserId, network: Network): Resource<KycView> {
        val settings =
            when (val open = gates.open(userId, network)) {
                is Resource.Error -> return open
                is Resource.Success -> open.data
            }
        val row = verifications.refreshIfDue(rows.find(Subject.user(userId), settings.kycEnvironment))
        return Resource.success(verifications.view(row, settings))
    }
}

/**
 * RM3: starts the merchant's identity check (ID, liveness, face match and screening), or hands back the open one. The
 * link is the provider's hosted page, which returns to the merchant portal. Needs a profile first.
 */
class StartMerchantKyc(
    private val merchants: MerchantRepository,
    private val settings: SettingsBook,
    private val verifications: Verifications,
) {
    suspend operator fun invoke(merchantId: MerchantId, network: Network): Resource<KycView> {
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        when (merchant.state) {
            MerchantState.NEW -> return Resource.error(RampError.MerchantSetupIncomplete)
            MerchantState.SUSPENDED -> return Resource.error(RampError.MerchantNotApproved)
            MerchantState.APPLIED, MerchantState.APPROVED -> Unit
        }
        val current = settings.of(network)
        return verifications.start(Subject.merchant(merchantId), current, current.merchantKycCallbackUrl)
    }
}

/**
 * The provider's webhook rang for [sessionId] in [environment]. It carries no decision we trust: a session the ramp
 * never started is ignored, and so is one whose row belongs to the other environment (a delivery signed with one
 * environment's secret can't speak for the other). For one that is ours the provider itself is asked. When it can't
 * be reached the answer is an error, so the provider delivers again.
 */
class HandleKycNotification(
    private val rows: VerificationRepository,
    private val verifications: Verifications,
    private val alerts: RampAlerts,
) {
    suspend operator fun invoke(environment: KycEnvironment, sessionId: String): Resource<Unit> {
        val row = rows.bySession(sessionId) ?: return Resource.success(Unit)
        if (row.environment != environment) {
            alerts.stuck("kyc.webhook", "wrong_environment")
            return Resource.success(Unit)
        }
        val refreshed = verifications.refresh(row)
        val isDown = refreshed.asked == Verifications.Asked.DOWN
        return if (isDown) Resource.error(RampError.KycUnavailable) else Resource.success(Unit)
    }
}

/**
 * `ramp.sweep-verifications`: nobody stays `PENDING` forever. Each unfinished check the provider wasn't asked about
 * lately is read again (a lost webhook, a session the person abandoned). One the provider still calls unfinished
 * after its time is up is marked expired, and so is one whose provider can't be asked any more; while the provider is
 * only out of reach nothing changes. Safe on two nodes and safe to run twice: every move is guarded.
 */
class SweepVerifications(
    private val rows: VerificationRepository,
    private val verifications: Verifications,
    private val settings: SettingsBook,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Int {
        val now = clock.instant()
        var handled = 0
        rows.stale(setOf(VerificationStatus.PENDING), now.minus(PENDING_RECHECK), LIMIT).forEach { row ->
            val refreshed = verifications.refresh(row)
            val after = refreshed.verification
            // Never on a guess: while the provider is only out of reach, the check keeps waiting.
            val mayExpire = refreshed.asked != Verifications.Asked.DOWN
            if (after.status == VerificationStatus.PENDING && mayExpire && isPastItsTime(after)) {
                verifications.expire(after)
            }
            handled++
        }
        rows.stale(setOf(VerificationStatus.IN_REVIEW), now.minus(REVIEW_RECHECK), LIMIT).forEach { row ->
            verifications.refresh(row)
            handled++
        }
        return handled
    }

    /** Past the link's own expiry, or, when the provider gave none, past the longest age any network allows. */
    private suspend fun isPastItsTime(row: Verification): Boolean {
        val now = clock.instant()
        val deadline = row.sessionExpiresAt ?: (row.startedAt ?: row.createdAt).plus(longestPending(row.environment))
        return deadline.isBefore(now)
    }

    private suspend fun longestPending(environment: KycEnvironment): Duration =
        Network.entries
            .map { settings.of(it) }
            .filter { it.kycEnvironment == environment }
            .maxOfOrNull { it.kycPendingFor }
            ?: DEFAULT_PENDING

    private companion object {
        const val LIMIT = 100
        val PENDING_RECHECK: Duration = Duration.ofMinutes(5)
        val REVIEW_RECHECK: Duration = Duration.ofMinutes(30)
        val DEFAULT_PENDING: Duration = Duration.ofHours(24)
    }
}

// ---- staff ------------------------------------------------------------------------------------------------

/** A user's ramp verification in each provider environment, and their block if they are barred. */
class GetUserVerifications(private val rows: VerificationRepository, private val blocks: BlockRepository) {
    suspend operator fun invoke(userId: UserId): Resource<UserVerifications> =
        Resource.success(UserVerifications(userId, rows.ofSubject(Subject.user(userId)), blocks.ofUser(userId)))
}

/** RO3: the users barred from the ramp, newest first. */
class ListBlocks(private val blocks: BlockRepository) {
    suspend operator fun invoke(after: PageCursor?, limit: Int): Resource<List<Block>> =
        Resource.success(blocks.users(after, limit.coerceIn(1, MAX_LIMIT)))

    private companion object {
        const val MAX_LIMIT = 200
    }
}

/**
 * RO3: bars a user from the ramp. The identity they verified with is barred with them (in every environment they
 * have one), so the same person can't come back on a new account. Barring someone already barred changes nothing
 * and answers the block there is.
 */
class BlockUser(
    private val rows: VerificationRepository,
    private val blocks: BlockRepository,
    private val transactor: Transactor,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        userId: UserId,
        reason: BlockReason,
        note: String,
        by: String,
    ): Resource<Block> {
        val now = clock.instant()
        val block = Block(Uuid7.generate(now), userId, null, reason, note.trim().take(MAX_NOTE), null, by, now)
        transactor.run("blocks.block-user") {
            blocks.add(block)
            rows.ofSubject(Subject.user(userId)).mapNotNull { it.identityKey }.distinct().forEach { identityKey ->
                blocks.add(Block(Uuid7.generate(now), null, identityKey, reason, "", userId, by, now))
            }
        }
        return Resource.success(blocks.ofUser(userId) ?: block)
    }

    private companion object {
        const val MAX_NOTE = 500
    }
}

/** RO3: lifts a user's block, and the identity blocks that came from it; whether anything was lifted. */
class UnblockUser(private val blocks: BlockRepository) {
    suspend operator fun invoke(userId: UserId): Resource<Boolean> = Resource.success(blocks.removeUser(userId) > 0)
}

/**
 * Staff's way out of a dead end: removes a subject's verification in one environment, which frees the identity it
 * holds, so the person can verify again (on this account or another). Whether there was one to remove.
 */
class ResetVerification(private val rows: VerificationRepository) {
    suspend operator fun invoke(subject: Subject, environment: KycEnvironment): Resource<Boolean> =
        Resource.success(rows.delete(subject, environment) != null)
}

/** A merchant's verification in the environment [Network]'s settings trust; null when they never started. */
class GetMerchantVerification(private val rows: VerificationRepository, private val settings: SettingsBook) {
    suspend operator fun invoke(merchantId: MerchantId, network: Network): Resource<Verification?> =
        Resource.success(rows.find(Subject.merchant(merchantId), settings.of(network).kycEnvironment))
}

/** Staff resetting a merchant's verification: the merchant must exist. */
class ResetMerchantVerification(private val merchants: MerchantRepository, private val rows: VerificationRepository) {
    suspend operator fun invoke(merchantId: MerchantId, environment: KycEnvironment): Resource<Boolean> {
        merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        return Resource.success(rows.delete(Subject.merchant(merchantId), environment) != null)
    }
}
