package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.AddOneTimeKeysCommand
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.messaging.domain.support.KeyProofs
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource

/**
 * M1 `POST /keys/one-time`: tops up the one-time prekeys of this phone's bundle, device-signed. At most 100 are kept
 * at once; an upload that would pass that is refused whole
 * with `messaging.too_many_prekeys`. A bundle published by another phone counts as none:
 * this phone's prekeys would not match that identity key.
 */
class AddOneTimeKeys(
    private val keys: KeyRepository,
    private val proofs: KeyProofs,
    private val config: MessagingConfig,
) {
    suspend operator fun invoke(command: AddOneTimeKeysCommand): Resource<Int> {
        val proof = proofs.check(command.userId, command.deviceId, command.proof)
        if (proof is Resource.Error) return proof

        val bundle = keys.bundle(command.userId)
        if (bundle == null || bundle.deviceId != command.deviceId) return Resource.error(MessagingError.KeysNotFound)

        val total = (keys.oneTimeKeyIds(command.userId) + command.oneTimePreKeys.map { it.keyId }).size
        if (total > config.maxOneTimePreKeys) return Resource.error(MessagingError.TooManyPrekeys)

        keys.addOneTimeKeys(command.userId, command.oneTimePreKeys)
        return Resource.success(total)
    }
}
