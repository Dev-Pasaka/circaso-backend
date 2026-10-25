package com.circaso.messaging.domain.support

import com.circaso.identity.api.DeviceSignatures
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.KeyUploadProof
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock
import java.time.Duration

/**
 * "This key upload comes from my registered phone" (Messaging → Encryption): the signature is checked by identity
 * against the phone's hardware-backed device key, so a stolen access token alone can't swap someone's keys. The
 * timestamp is part of the signed payload, so a captured upload can't be replayed with a fresh one, and it must be
 * within the window identity uses for its own device proofs.
 */
class KeyProofs(private val signatures: DeviceSignatures, private val clock: Clock, private val window: Duration) {
    suspend fun check(
        userId: UserId,
        deviceId: DeviceId,
        proof: KeyUploadProof,
    ): Resource<Unit> {
        if (Duration.between(proof.timestamp, clock.instant()).abs() > window) {
            return Resource.error(MessagingError.KeysProofInvalid)
        }
        return when (signatures.verify(userId, deviceId, proof.signedPayload, proof.signature)) {
            is Resource.Success -> Resource.success(Unit)
            is Resource.Error -> Resource.error(MessagingError.KeysProofInvalid)
        }
    }
}
