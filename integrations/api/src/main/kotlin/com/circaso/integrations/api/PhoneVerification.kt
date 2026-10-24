package com.circaso.integrations.api

import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource

/**
 * A service that makes, sends and checks the one-time code itself (Twilio Verify), so the code never passes through us.
 * Used instead of [SmsGateway] when [managesCodes] is true: identity then only asks to start a verification and asks whether a
 * given code is right. When it is false (the default) identity makes the code and delivers it through [SmsGateway] as before.
 */
interface PhoneVerificationGateway {
    val managesCodes: Boolean

    /** Sends a code to [to]. [appHash] is the Android SMS Retriever hash, when the app wants the text to be readable without a permission. */
    suspend fun start(to: PhoneNumber, appHash: String? = null): Resource<Unit>

    suspend fun check(to: PhoneNumber, code: String): Resource<CodeCheck>
}

enum class CodeCheck {
    /** The code was right. */
    APPROVED,

    /** The code was wrong; the person may try again. */
    DENIED,

    /** There is no pending code for this number: it expired, was already used, or too many wrong tries used it up. */
    EXPIRED,
}

/** The default: identity makes and checks codes itself. */
object NoVendorVerification : PhoneVerificationGateway {
    override val managesCodes: Boolean = false
    override suspend fun start(to: PhoneNumber, appHash: String?): Resource<Unit> = Resource.error(IntegrationError.NotImplemented("PhoneVerificationGateway", "none"))
    override suspend fun check(to: PhoneNumber, code: String): Resource<CodeCheck> = Resource.error(IntegrationError.NotImplemented("PhoneVerificationGateway", "none"))
}
