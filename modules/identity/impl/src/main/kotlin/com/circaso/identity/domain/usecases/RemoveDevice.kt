package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.DeviceStatus
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

/** I10: forgets this phone. The next sign-in anywhere, this phone included, verifies the number again. */
class RemoveDevice(
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val sessions: SessionRepository,
    private val store: IdentityStore,
    private val cache: IdentityCache,
) {
    suspend operator fun invoke(principal: Principal): Resource<Unit> {
        val account = accounts.find(principal.userId) ?: return Resource.error(SharedError.NotFound)
        val device = devices.find(principal.userId, principal.deviceId) ?: return Resource.error(SharedError.NotFound)
        val session = sessions.find(principal.sessionId)?.takeIf { it.status == SessionStatus.ACTIVE }

        store.commit(
            Changes(
                accounts =
                    listOf(
                        account.copy(
                            activeDeviceId =
                                if (account.activeDeviceId ==
                                    device.deviceId
                                ) {
                                    null
                                } else {
                                    account.activeDeviceId
                                },
                        ),
                    ),
                devices = listOf(device.copy(status = DeviceStatus.REMOVED)),
                sessions = listOfNotNull(session?.copy(status = SessionStatus.REVOKED)),
                securityEvents =
                    listOf(
                        SecurityEvent(principal.userId, null, SecurityEventType.DEVICE_REMOVED, device.model, null),
                    ),
            ),
        )
        cache.revoke(principal.sessionId)
        return Resource.success(Unit)
    }
}
