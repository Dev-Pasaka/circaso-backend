package com.circaso.identity.domain.support

import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.DeviceProof
import com.circaso.identity.domain.models.DeviceStatus
import com.circaso.identity.domain.ports.DeviceKeyVerifier
import java.time.Clock
import java.time.Duration

/**
 * "This request comes from my registered phone": the signature is checked against the phone's stored public key,
 * and the timestamp must be recent, so a captured proof can't be replayed later.
 */
class DeviceProofs(private val keys: DeviceKeyVerifier, private val clock: Clock, private val window: Duration) {

    fun valid(device: Device?, payload: String, proof: DeviceProof?): Boolean {
        if (device == null || proof == null || device.status != DeviceStatus.ACTIVE) return false
        if (Duration.between(proof.timestamp, clock.instant()).abs() > window) return false
        return keys.verify(device.publicKey, device.keyAlgorithm, payload.toByteArray(), proof.signature)
    }

    companion object {
        /** What the phone signs at sign-in (I2). */
        fun signInPayload(nonce: String, proof: DeviceProof) = nonce + proof.timestamp

        /** What the phone signs at refresh (I7). */
        fun refreshPayload(refreshToken: String, proof: DeviceProof) = refreshToken + "|" + proof.timestamp
    }
}
