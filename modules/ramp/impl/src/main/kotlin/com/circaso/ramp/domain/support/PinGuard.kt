package com.circaso.ramp.domain.support

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Rewrapped
import com.circaso.ramp.domain.models.SealedCredentials
import com.circaso.ramp.domain.models.Unsealed
import com.circaso.ramp.domain.ports.CredentialVault
import com.circaso.ramp.domain.ports.PinAttempts
import com.circaso.shared.Resource

/**
 * Every use of a merchant's PIN goes through here, so wrong tries are counted the same whoever asks: five wrong PINs
 * lock PIN entry for thirty minutes (`ramp.pin_locked`, with how long), and a right one clears the count.
 */
class PinGuard(private val vault: CredentialVault, private val attempts: PinAttempts) {
    suspend fun open(
        merchantId: MerchantId,
        sealed: SealedCredentials,
        pin: String,
    ): Resource<CollectionCredentials> {
        locked(merchantId)?.let { return it }
        return when (val opened = vault.open(merchantId, sealed, pin)) {
            is Unsealed.Opened -> Resource.success(opened.credentials).also { attempts.clear(merchantId) }
            Unsealed.WrongPin -> wrong(merchantId)
            Unsealed.Unavailable -> Resource.error(RampError.VaultUnavailable)
        }
    }

    suspend fun rewrap(
        merchantId: MerchantId,
        sealed: SealedCredentials,
        currentPin: String,
        newPin: String,
    ): Resource<SealedCredentials> {
        locked(merchantId)?.let { return it }
        return when (val done = vault.rewrap(merchantId, sealed, currentPin, newPin)) {
            is Rewrapped.Done -> Resource.success(done.sealed).also { attempts.clear(merchantId) }
            Rewrapped.WrongPin -> wrong(merchantId)
            Rewrapped.Unavailable -> Resource.error(RampError.VaultUnavailable)
        }
    }

    private suspend fun locked(merchantId: MerchantId): Resource.Error? =
        attempts.lockedFor(merchantId)?.let { Resource.Error(RampError.PinLocked(it.seconds.coerceAtLeast(1))) }

    private suspend fun wrong(merchantId: MerchantId): Resource.Error =
        if (attempts.failed(merchantId) >= MAX_TRIES) {
            Resource.Error(RampError.PinLocked(LOCK_SECONDS))
        } else {
            Resource.Error(RampError.PinInvalid)
        }

    companion object {
        const val MAX_TRIES = 5
        const val LOCK_SECONDS = 1_800L
    }
}
