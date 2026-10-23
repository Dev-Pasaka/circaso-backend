package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.CarrierPhoneVerification
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource

/** Treats the carrier token as the E.164 number itself (`+254712345678`), so one-tap can be driven locally. */
class FakeCarrierPhoneVerification : CarrierPhoneVerification {
    override suspend fun verify(carrierToken: String): Resource<PhoneNumber> =
        PhoneNumber.parse(carrierToken)?.let { Resource.success(it) }
            ?: Resource.error(IntegrationError.Rejected("The carrier token was not accepted."))
}
