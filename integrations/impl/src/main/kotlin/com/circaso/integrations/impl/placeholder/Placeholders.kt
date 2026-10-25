package com.circaso.integrations.impl.placeholder

import com.circaso.integrations.api.CarrierPhoneVerification
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource

// The real vendors, until their credentials and SDKs arrive. Each returns `integrations.not_implemented`, so
// selecting one by config fails loudly instead of pretending to deliver. Replace a class with the vendor's own
// package (`carrier/firebase`, `push`) and delete it here.

// TODO(vendor: Firebase project and service account for phone number verification)
class FirebaseCarrierPhoneVerification : CarrierPhoneVerification {
    override suspend fun verify(carrierToken: String): Resource<PhoneNumber> =
        Resource.error(IntegrationError.NotImplemented("CarrierPhoneVerification", "firebase"))
}
