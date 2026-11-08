package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.DeviceStatus
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

/** I10: forgets this phone. The next sign-in anywhere, this phone included, verifies the number again. */
class RemoveDevice(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val sessions: SessionRepository,
    private val store: IdentityStore,
    private val cache: IdentityCache,
) {
    suspend operator fun invoke(principal: Principal): Resource<Unit> {
        val removed =
            transactor.run("devices.remove") {
                val account = accounts.lock(principal.userId) ?: return@run false
                val device = devices.find(principal.userId, principal.deviceId) ?: return@run false
                val session = sessions.find(principal.sessionId)?.takeIf { it.status == SessionStatus.ACTIVE }
                val activeDeviceId = account.activeDeviceId.takeIf { it != device.deviceId }
                store.write(
                    Changes(
                        accounts = listOf(account.copy(activeDeviceId = activeDeviceId)),
                        devices = listOf(device.copy(status = DeviceStatus.REMOVED, voipToken = null)),
                        sessions = listOfNotNull(session?.copy(status = SessionStatus.REVOKED)),
                        securityEvents =
                            listOf(
                                SecurityEvent(
                                    principal.userId,
                                    null,
                                    SecurityEventType.DEVICE_REMOVED,
                                    device.model,
                                    null,
                                ),
                            ),
                    ),
                )
                true
            }
        if (!removed) return Resource.error(SharedError.NotFound)
        cache.revoke(principal.sessionId)
        return Resource.success(Unit)
    }
}
