package com.circaso.identity.presentation.services

import com.circaso.identity.domain.models.PhoneVerifiedResult
import com.circaso.identity.domain.models.ReleaseCommand
import com.circaso.identity.domain.models.StartPhoneCommand
import com.circaso.identity.domain.models.StartPhoneResult
import com.circaso.identity.domain.models.VerifyCarrierCommand
import com.circaso.identity.domain.models.VerifyCodeCommand
import com.circaso.identity.domain.usecases.RequestPhoneRelease
import com.circaso.identity.domain.usecases.StartPhoneVerification
import com.circaso.identity.domain.usecases.VerifyCarrierToken
import com.circaso.identity.domain.usecases.VerifyPhoneCode
import com.circaso.shared.Resource
import java.time.Instant

/** Phone verification (SMS and carrier) and number release. */
class PhoneService(
    private val startPhoneVerification: StartPhoneVerification,
    private val verifyPhoneCode: VerifyPhoneCode,
    private val verifyCarrierToken: VerifyCarrierToken,
    private val requestPhoneRelease: RequestPhoneRelease,
) {
    suspend fun start(command: StartPhoneCommand): Resource<StartPhoneResult> = startPhoneVerification(command)

    suspend fun verify(command: VerifyCodeCommand): Resource<PhoneVerifiedResult> = verifyPhoneCode(command)

    suspend fun carrier(command: VerifyCarrierCommand): Resource<PhoneVerifiedResult> = verifyCarrierToken(command)

    suspend fun release(command: ReleaseCommand): Resource<Instant> = requestPhoneRelease(command)
}
