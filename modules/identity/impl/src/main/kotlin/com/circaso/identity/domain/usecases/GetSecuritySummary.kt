package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.SecuritySummary
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.identity.domain.repositories.SecurityLogRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

/**
 * Asha's read of the caller's account and security (decision 2026-10-31): the masked phone, linked sign-ins, the
 * phone the account is on now and the latest [RECENT] security log lines. The caller's own account only.
 */
class GetSecuritySummary(
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val devices: DeviceRepository,
    private val log: SecurityLogRepository,
) {
    suspend operator fun invoke(principal: Principal): Resource<SecuritySummary> {
        val account = accounts.find(principal.userId) ?: return Resource.error(SharedError.NotFound)
        val deviceId = account.activeDeviceId ?: principal.deviceId
        return Resource.success(
            SecuritySummary(
                account = account,
                maskedPhone = account.phone?.masked(),
                providers = providers.links(account.userId),
                device = devices.find(account.userId, deviceId),
                recent = log.list(account.userId, null, RECENT),
            ),
        )
    }

    private companion object {
        const val RECENT = 10
    }
}
