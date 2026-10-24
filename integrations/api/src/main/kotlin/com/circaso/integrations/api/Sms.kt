package com.circaso.integrations.api

import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource

interface SmsGateway {
    /** Delivers [text] as written; the caller words it and owns the code, expiry and attempt limits. */
    suspend fun send(
        to: PhoneNumber,
        text: String,
        purpose: SmsPurpose,
    ): Resource<SmsReceipt>
}

enum class SmsPurpose { VERIFICATION, SECURITY_ALERT, NOTIFICATION }

data class SmsReceipt(val messageId: String, val provider: String)
