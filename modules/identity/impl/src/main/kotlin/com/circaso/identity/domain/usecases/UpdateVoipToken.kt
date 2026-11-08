package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

/**
 * I9b: stores this iPhone's PushKit (VoIP) token, read and written under the account's lock (decision 2026-11-08). An
 * incoming call then rings it through a VoIP push sent straight to APNs, even with the app closed.
 */
class UpdateVoipToken(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val store: IdentityStore,
) {
    suspend operator fun invoke(principal: Principal, voipToken: String): Resource<Unit> {
        val updated =
            transactor.run("devices.update-voip-token") {
                accounts.lock(principal.userId) ?: return@run false
                val device = devices.find(principal.userId, principal.deviceId) ?: return@run false
                if (device.voipToken !=
                    voipToken
                ) {
                    store.write(Changes(devices = listOf(device.copy(voipToken = voipToken))))
                }
                true
            }
        return if (updated) Resource.success(Unit) else Resource.error(SharedError.NotFound)
    }
}
