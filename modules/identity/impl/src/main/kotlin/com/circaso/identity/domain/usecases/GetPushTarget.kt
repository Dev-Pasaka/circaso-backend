package com.circaso.identity.domain.usecases

import com.circaso.identity.api.PushRegistration
import com.circaso.identity.domain.models.DeviceStatus
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** Where to push a notification for a user: the active phone's token, if it has one. */
class GetPushTarget(private val accounts: AccountRepository, private val devices: DeviceRepository) {
    suspend operator fun invoke(userId: UserId): Resource<PushRegistration?> {
        val deviceId = accounts.find(userId)?.activeDeviceId ?: return Resource.success(null)
        val device = devices.find(userId, deviceId)?.takeIf { it.status == DeviceStatus.ACTIVE }
        return Resource.success(device?.pushToken?.let { PushRegistration(device.platform, it) })
    }
}
