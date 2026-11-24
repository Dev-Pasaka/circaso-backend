package com.circaso.identity.domain.usecases

import com.circaso.identity.api.IdentityVerificationChanged
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import java.time.Instant

/**
 * `ramp.UserVerificationChanged` (the verified badge, 2026-11-24): keeps where the person's live identity verification
 * stands on the account. `verification` becomes `VERIFIED` with [verifiedAt], or `NONE` when it was taken back,
 * expired or reset; that is also what makes the account eligible for support recovery (I15).
 *
 * Order-tolerant and idempotent: a report whose [changedAt] is no newer than the last one applied changes nothing, so
 * redeliveries and ramp's periodic re-announcements are no-ops. Only a change of the answer writes a security log line
 * and publishes `identity.IdentityVerificationChanged`, which users turns into the badge. An unknown account (deleted
 * since) is ignored.
 */
class ApplyIdentityVerification(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val store: IdentityStore,
    private val bus: EventBus,
) {
    suspend operator fun invoke(
        userId: UserId,
        verified: Boolean,
        verifiedAt: Instant?,
        changedAt: Instant,
    ): Resource<Unit> {
        val stamp = if (verified) verifiedAt ?: changedAt else null
        val event = bus.envelope(IdentityVerificationChanged(userId, verified, stamp))
        val changes =
            transactor.run("accounts.identity-verification") {
                val account = accounts.lock(userId) ?: return@run null
                val last = account.verificationChangedAt
                if (last != null && !changedAt.isAfter(last)) return@run null
                val wasVerified = account.verification == Verification.VERIFIED
                val updated =
                    account.copy(
                        verification = if (verified) Verification.VERIFIED else Verification.NONE,
                        verifiedAt = stamp,
                        verificationChangedAt = changedAt,
                    )
                val answerChanged = wasVerified != verified || account.verifiedAt != stamp
                val type = if (verified) SecurityEventType.IDENTITY_VERIFIED else SecurityEventType.IDENTITY_UNVERIFIED
                val changes =
                    Changes(
                        accounts = listOf(updated),
                        securityEvents =
                            if (wasVerified !=
                                verified
                            ) {
                                listOf(SecurityEvent(userId, null, type, null, null))
                            } else {
                                emptyList()
                            },
                        events = if (answerChanged) listOf(event) else emptyList(),
                    )
                store.write(changes)
                changes
            } ?: return Resource.success(Unit)
        store.publish(changes)
        return Resource.success(Unit)
    }
}
