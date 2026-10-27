package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.AddOneTimeKeysCommand
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.messaging.domain.support.KeyProofs
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import java.time.Clock

/**
 * M1 `POST /keys/one-time`: tops up the one-time prekeys of this phone's bundle, device-signed. At most 100 are kept
 * at once; an upload that would pass that is refused whole with `messaging.too_many_prekeys`. Resending a stored id
 * is refused (`messaging.prekey_id_exists`): replacing it could let a concurrent fetch hand out the old public key.
 * A bundle published by another phone counts as none: this phone's prekeys would not match that identity key.
 *
 * The checks and the insert run in one transaction under the bundle's row lock, so concurrent top-ups of one account
 * run one after the other and the cap is exact; a refused top-up rolls back and leaves nothing of its own, so the
 * phone can drop that request's private keys at once.
 */
class AddOneTimeKeys(
    private val keys: KeyRepository,
    private val transactor: Transactor,
    private val proofs: KeyProofs,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: AddOneTimeKeysCommand): Resource<Int> {
        val proof = proofs.check(command.userId, command.deviceId, command.proof)
        if (proof is Resource.Error) return proof

        return transactor.attempt("keys.top-up") {
            val bundle = keys.lockBundle(command.userId)
            if (bundle == null || bundle.deviceId != command.deviceId) {
                return@attempt Resource.error(MessagingError.KeysNotFound)
            }
            val stored = keys.oneTimeKeyIds(command.userId, command.deviceId)
            if (command.oneTimePreKeys.any { it.keyId in stored }) {
                return@attempt Resource.error(MessagingError.PrekeyIdExists)
            }
            val total = stored.size + command.oneTimePreKeys.size
            if (total > config.maxOneTimePreKeys) return@attempt Resource.error(MessagingError.TooManyPrekeys)

            keys.addOneTimeKeys(command.userId, command.deviceId, command.oneTimePreKeys, clock.instant())
            Resource.success(total)
        }
    }
}
