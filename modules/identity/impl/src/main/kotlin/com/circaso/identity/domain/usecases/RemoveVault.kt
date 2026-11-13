package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.VaultRepository
import com.circaso.shared.Resource

/**
 * V4 `DELETE /vault`: deletes the account's PIN Vault and logs `VAULT_REMOVED`. With no vault there is nothing to
 * delete or log, and the answer is the same: the phone only needs to know none is left.
 */
class RemoveVault(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val vaults: VaultRepository,
    private val store: IdentityStore,
) {
    suspend operator fun invoke(principal: Principal): Resource<Unit> {
        val userId = principal.userId
        transactor.run("vaults.remove") {
            accounts.lock(userId) ?: return@run
            vaults.find(userId) ?: return@run
            val model = devices.find(userId, principal.deviceId)?.model
            val removed = SecurityEvent(userId, null, SecurityEventType.VAULT_REMOVED, model, null)
            store.write(Changes(removedVaults = listOf(userId), securityEvents = listOf(removed)))
        }
        return Resource.success(Unit)
    }
}
