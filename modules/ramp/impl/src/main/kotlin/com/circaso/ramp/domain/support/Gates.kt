package com.circaso.ramp.domain.support

import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.KycStanding
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.ports.KycGate
import com.circaso.ramp.domain.repositories.PreviewAccessRepository
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * Who may use the ramp on a network. The state gate first: `OFF` lets nobody in, `PREVIEW` only the people staff put
 * on the list (`ramp.preview_access`), `ON` everybody; anyone else gets `ramp.unavailable`. Then the block list, and
 * KYC, which the settings may switch off on the testnet only. A user is verified on a network when they passed in the
 * provider environment that network's settings name.
 */
class Gates(
    private val settings: SettingsBook,
    private val preview: PreviewAccessRepository,
    private val kyc: KycGate,
) {
    /**
     * [network]'s settings when the ramp is open to [userId] there in [direction]. Buying answers `ramp.unavailable`
     * when it isn't; selling, which has its own switch (decision 2026-11-23), `ramp.offramp_unavailable`. Both use the
     * same preview list.
     */
    suspend fun open(
        userId: UserId,
        network: Network,
        direction: Direction = Direction.ON_RAMP,
    ): Resource<RampSettings> {
        val current = settings.of(network)
        if (isOpen(userId, current, direction)) return Resource.success(current)
        val closed = if (direction == Direction.ON_RAMP) RampError.Unavailable else RampError.OffRampUnavailable
        return Resource.error(closed)
    }

    /** Whether [direction] is open to [userId] under [current]. */
    suspend fun isOpen(
        userId: UserId,
        current: RampSettings,
        direction: Direction,
    ): Boolean =
        when (current.stateOf(direction)) {
            RampState.OFF -> false
            RampState.PREVIEW -> preview.has(userId, current.network)
            RampState.ON -> true
        }

    /**
     * [network]'s settings whatever the ramp's state: for what isn't a trade. Verifying one's identity is open to any
     * signed-in person, with the ramp `OFF` or in `PREVIEW` without them (decision 2026-11-23).
     */
    suspend fun settingsOf(network: Network): RampSettings = settings.of(network)

    suspend fun kyc(userId: UserId, settings: RampSettings): KycStanding =
        when {
            !settings.enforcesKyc -> KycStanding.NOT_REQUIRED
            kyc.isVerified(Subject.user(userId), settings.kycEnvironment) -> KycStanding.VERIFIED
            else -> KycStanding.NONE
        }

    /** Whether [userId] is barred from the ramp: as a user, or as the identity they verified with. */
    suspend fun isBlocked(userId: UserId, settings: RampSettings): Boolean =
        kyc.isBlocked(userId, settings.kycEnvironment)

    /**
     * What stops [userId] trading under [settings], if anything: a block first, then identity verification where
     * the network requires it.
     */
    suspend fun refusal(userId: UserId, settings: RampSettings): RampError? =
        when {
            isBlocked(userId, settings) -> RampError.Blocked
            kyc(userId, settings) == KycStanding.NONE -> RampError.KycRequired
            else -> null
        }
}
