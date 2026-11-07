package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.models.DeviceRecord
import com.circaso.identity.domain.repositories.AccountAdminRepository
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** Staff: every phone the account registered, newest first, the current one marked (decision 2026-11-08). */
class ListAccountDevices(private val accounts: AccountRepository, private val admin: AccountAdminRepository) {
    suspend operator fun invoke(userId: UserId): Resource<List<DeviceRecord>> {
        val account = accounts.find(userId) ?: return Resource.success(emptyList())
        return Resource.success(
            admin.devices(userId).map { DeviceRecord(it, it.deviceId == account.activeDeviceId) },
        )
    }
}
