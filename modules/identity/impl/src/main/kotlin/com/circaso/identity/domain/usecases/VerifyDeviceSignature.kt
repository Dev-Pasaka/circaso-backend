package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.DeviceStatus
import com.circaso.identity.domain.ports.DeviceKeyVerifier
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** Device-bound actions: was this payload signed by the user's active phone? */
class VerifyDeviceSignature(private val devices: DeviceRepository, private val keys: DeviceKeyVerifier) {
    suspend operator fun invoke(
        userId: UserId,
        deviceId: DeviceId,
        payload: ByteArray,
        signature: ByteArray,
    ): Resource<Unit> {
        val device =
            devices.find(userId, deviceId)?.takeIf { it.status == DeviceStatus.ACTIVE }
                ?: return Resource.error(IdentityError.DeviceProofInvalid)
        return if (keys.verify(device.publicKey, device.keyAlgorithm, payload, signature)) {
            Resource.success(Unit)
        } else {
            Resource.error(IdentityError.DeviceProofInvalid)
        }
    }
}
