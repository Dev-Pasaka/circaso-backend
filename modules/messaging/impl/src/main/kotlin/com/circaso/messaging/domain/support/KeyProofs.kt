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
 * timestamp must be within the window identity uses for its own device proofs.
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
        // TODO(decision: the proof covers the SHA-256 of the body without `deviceProof`, so its timestamp is not
        //  signed and the window check can't stop a captured upload being replayed with a fresh timestamp; should
        //  the signed payload include the timestamp, as identity's proofs do?)
        return when (signatures.verify(userId, deviceId, proof.digest, proof.signature)) {
            is Resource.Success -> Resource.success(Unit)
            is Resource.Error -> Resource.error(MessagingError.KeysProofInvalid)
        }
    }
}
