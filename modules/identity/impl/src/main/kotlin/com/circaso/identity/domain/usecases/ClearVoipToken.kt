package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * Forgets the active phone's PushKit token after APNs refused it (`BadDeviceToken`, `Unregistered`), so the next ring
 * goes through FCM until the phone sends a new one. Only that exact token is cleared: a newer one the phone sent
 * meanwhile is kept.
 */
class ClearVoipToken(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val store: IdentityStore,
) {
    suspend operator fun invoke(userId: UserId, voipToken: String): Resource<Unit> {
        transactor.run("devices.clear-voip-token") {
            val deviceId = accounts.lock(userId)?.activeDeviceId ?: return@run
            val device = devices.find(userId, deviceId)?.takeIf { it.voipToken == voipToken } ?: return@run
            store.write(Changes(devices = listOf(device.copy(voipToken = null))))
        }
        return Resource.success(Unit)
    }
}
