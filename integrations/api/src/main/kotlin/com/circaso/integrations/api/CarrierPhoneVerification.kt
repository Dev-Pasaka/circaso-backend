package com.circaso.integrations.api

import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource

interface CarrierPhoneVerification {
    /** Turns a carrier token (one-tap number check) into the verified number, with no SMS. */
    suspend fun verify(carrierToken: String): Resource<PhoneNumber>
}
