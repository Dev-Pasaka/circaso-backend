package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

/** I9: stores the FCM or APNs token of this phone, read and written under the account's lock. */
class UpdatePushToken(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val store: IdentityStore,
) {
    suspend operator fun invoke(principal: Principal, pushToken: String): Resource<Unit> {
        val updated =
            transactor.run("devices.update-push-token") {
                accounts.lock(principal.userId) ?: return@run false
                val device = devices.find(principal.userId, principal.deviceId) ?: return@run false
                store.write(Changes(devices = listOf(device.copy(pushToken = pushToken))))
                true
            }
        return if (updated) Resource.success(Unit) else Resource.error(SharedError.NotFound)
    }
}
