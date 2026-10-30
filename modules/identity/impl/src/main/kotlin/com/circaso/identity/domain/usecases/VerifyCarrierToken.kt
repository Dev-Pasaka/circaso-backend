package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.PhoneVerifiedResult
import com.circaso.identity.domain.models.VerifyCarrierCommand
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.support.PhoneOwnership
import com.circaso.integrations.api.CarrierPhoneVerification
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

/** I5: the same as I4 from a carrier token (one-tap on Android); no SMS. */
class VerifyCarrierToken(
    private val carrier: CarrierPhoneVerification,
    private val accounts: AccountRepository,
    private val ownership: PhoneOwnership,
) {
    suspend operator fun invoke(command: VerifyCarrierCommand): Resource<PhoneVerifiedResult> {
        val phone =
            when (val verified = carrier.verify(command.carrierToken)) {
                is Resource.Success -> {
                    verified.data
                }

                is Resource.Error -> {
                    return if (verified.error is IntegrationError.Rejected) {
                        Resource.error(
                            IdentityError.CarrierUnsupported,
                        )
                    } else {
                        verified
                    }
                }
            }
        val account = accounts.find(command.principal.userId) ?: return Resource.error(SharedError.TokenInvalid)
        if (account.isSupport) return Resource.error(IdentityError.SupportAccountNotOnMobile)
        return ownership.confirm(account, phone, command.principal.deviceId)
    }
}
