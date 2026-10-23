package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

/** I9: stores the FCM or APNs token of this phone. */
class UpdatePushToken(private val devices: DeviceRepository, private val store: IdentityStore) {
    suspend operator fun invoke(principal: Principal, pushToken: String): Resource<Unit> {
        val device = devices.find(principal.userId, principal.deviceId) ?: return Resource.error(SharedError.NotFound)
        store.commit(Changes(devices = listOf(device.copy(pushToken = pushToken))))
        return Resource.success(Unit)
    }
}
