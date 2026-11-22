package com.circaso.ramp.domain.support

import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.KycStanding
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.domain.ports.KycGate
import com.circaso.ramp.domain.repositories.PreviewAccessRepository
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * Who may use the ramp on a network. The state gate first: `OFF` lets nobody in, `PREVIEW` only the people staff put
 * on the list (`ramp.preview_access`), `ON` everybody; anyone else gets `ramp.unavailable`. Then KYC, which the
 * settings may switch off on the testnet only, until Didit is built.
 */
class Gates(
    private val settings: SettingsBook,
    private val preview: PreviewAccessRepository,
    private val kyc: KycGate,
) {
    /** [network]'s settings when the ramp is open to [userId] there. */
    suspend fun open(userId: UserId, network: Network): Resource<RampSettings> {
        val current = settings.of(network)
        val allowed =
            when (current.state) {
                RampState.OFF -> false
                RampState.PREVIEW -> preview.has(userId, network)
                RampState.ON -> true
            }
        return if (allowed) Resource.success(current) else Resource.error(RampError.Unavailable)
    }

    suspend fun kyc(userId: UserId, settings: RampSettings): KycStanding =
        when {
            !settings.enforcesKyc -> KycStanding.NOT_REQUIRED
            kyc.isVerified(userId) -> KycStanding.VERIFIED
            else -> KycStanding.NONE
        }
}
