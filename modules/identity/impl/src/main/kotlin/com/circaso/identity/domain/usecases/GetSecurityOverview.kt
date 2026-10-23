package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.SecurityOverview
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

/** I11: the phone, the linked sign-in accounts, this phone and the verification status. */
class GetSecurityOverview(
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val devices: DeviceRepository,
) {
    suspend operator fun invoke(principal: Principal): Resource<SecurityOverview> {
        val account = accounts.find(principal.userId) ?: return Resource.error(SharedError.NotFound)
        return Resource.success(
            SecurityOverview(
                maskedPhone = account.phone?.masked(),
                providers = providers.links(account.userId),
                device = devices.find(account.userId, principal.deviceId),
                verification = account.verification,
            ),
        )
    }
}
