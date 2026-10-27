package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.KeyStatus
import com.circaso.messaging.domain.repositories.KeyRepository
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * M2 `GET /keys/me`: how many one-time prekeys are left and when the signed prekey was set. A bundle published by
 * another phone answers `messaging.keys_not_found`, so a new phone knows to publish its own set.
 */
class GetKeyStatus(private val keys: KeyRepository) {
    suspend operator fun invoke(userId: UserId, deviceId: DeviceId): Resource<KeyStatus> {
        val bundle = keys.bundle(userId)
        if (bundle == null || bundle.deviceId != deviceId) return Resource.error(MessagingError.KeysNotFound)
        return Resource.success(KeyStatus(keys.oneTimeKeyIds(userId, deviceId).size, bundle.signedPreKeyAt))
    }
}
