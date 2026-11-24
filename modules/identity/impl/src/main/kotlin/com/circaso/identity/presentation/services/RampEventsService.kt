package com.circaso.identity.presentation.services

import com.circaso.identity.domain.usecases.ApplyIdentityVerification
import com.circaso.ramp.api.UserVerificationChanged
import com.circaso.shared.Resource

/** Ramp's events, which identity reacts to: a user's live identity verification (the verified badge, 2026-11-24). */
class RampEventsService(private val applyIdentityVerification: ApplyIdentityVerification) {
    suspend fun userVerificationChanged(event: UserVerificationChanged): Resource<Unit> =
        applyIdentityVerification(event.userId, event.verified, event.verifiedAt, event.changedAt)
}
