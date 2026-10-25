package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.PublishKeysCommand
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.messaging.domain.support.KeyProofs
import com.circaso.shared.Resource
import java.time.Clock

/**
 * M1 `PUT /keys`: the phone's whole key set, device-signed. It replaces the identity key, the signed prekey and the
 * one-time prekeys: one-time prekeys left from an earlier set are deleted, because they may belong to keys the
 * phone no longer holds (a reinstall). Returns how many one-time prekeys are now waiting.
 */
class PublishKeys(private val keys: KeyRepository, private val proofs: KeyProofs, private val clock: Clock) {
    suspend operator fun invoke(command: PublishKeysCommand): Resource<Int> {
        val proof = proofs.check(command.userId, command.deviceId, command.proof)
        if (proof is Resource.Error) return proof

        val bundle =
            KeyBundle(
                userId = command.userId,
                deviceId = command.deviceId,
                identityKey = command.identityKey,
                signedPreKey = command.signedPreKey,
                signedPreKeyAt = clock.instant(),
            )
        val newIds = command.oneTimePreKeys.map { it.keyId }.toSet()
        val staleIds = keys.oneTimeKeyIds(command.userId) - newIds
        keys.publish(bundle, command.oneTimePreKeys, staleIds)
        return Resource.success(command.oneTimePreKeys.size)
    }
}
