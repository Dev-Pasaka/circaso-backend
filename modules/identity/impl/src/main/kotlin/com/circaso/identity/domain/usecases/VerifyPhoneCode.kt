package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.PhoneVerifiedResult
import com.circaso.identity.domain.models.VerifyCodeCommand
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.PhoneProtection
import com.circaso.identity.domain.ports.VENDOR_CHECKED_CODE
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.support.IdentityConfig
import com.circaso.identity.domain.support.PhoneOwnership
import com.circaso.identity.domain.support.sha256Hex
import com.circaso.integrations.api.CodeCheck
import com.circaso.integrations.api.NoVendorVerification
import com.circaso.integrations.api.PhoneVerificationGateway
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import java.security.MessageDigest

/**
 * I4: checks the SMS code. Five wrong codes lock the account for 10 minutes. A right code on a number another
 * account holds still counts as proof of the SIM, which `RequestPhoneRelease` (I16) builds on; the answer is
 * `identity.phone_taken` until that release has run its notice.
 */
class VerifyPhoneCode(
    private val cache: IdentityCache,
    private val accounts: AccountRepository,
    private val phones: PhoneProtection,
    private val ownership: PhoneOwnership,
    private val config: IdentityConfig,
    private val verifier: PhoneVerificationGateway = NoVendorVerification,
) {
    suspend operator fun invoke(command: VerifyCodeCommand): Resource<PhoneVerifiedResult> {
        val userId = command.principal.userId
        cache.codeLockRemaining(userId)?.let { return Resource.error(IdentityError.CodeLocked(it.seconds.coerceAtLeast(1))) }

        val otp = cache.otp(command.verificationId)
        if (otp == null || otp.userId != userId || otp.deviceId != command.principal.deviceId) {
            return Resource.error(IdentityError.CodeExpired)
        }

        val sealedPhone = phones.unseal(otp.sealedPhone) ?: return Resource.error(IdentityError.CodeExpired)
        val right = if (otp.codeHash == VENDOR_CHECKED_CODE) {
            // The vendor holds the code: ask it. Nothing pending there means it expired or ran out of tries.
            when (val checked = verifier.check(sealedPhone, command.code)) {
                is Resource.Error -> return checked
                is Resource.Success -> when (checked.data) {
                    CodeCheck.APPROVED -> true
                    CodeCheck.DENIED -> false
                    CodeCheck.EXPIRED -> {
                        cache.deleteOtp(command.verificationId)
                        return Resource.error(IdentityError.CodeExpired)
                    }
                }
            }
        } else {
            val given = sha256Hex("${command.verificationId}:${command.code}")
            MessageDigest.isEqual(given.toByteArray(), otp.codeHash.toByteArray())
        }
        if (!right) {
            val attempts = cache.recordWrongAttempt(command.verificationId) ?: return Resource.error(IdentityError.CodeExpired)
            if (attempts >= config.maxCodeAttempts) {
                cache.deleteOtp(command.verificationId)
                cache.lockAccountCodes(userId)
                return Resource.error(IdentityError.CodeLocked(LOCK_SECONDS))
            }
            return Resource.error(IdentityError.CodeInvalid((config.maxCodeAttempts - attempts).toInt()))
        }

        val phone = sealedPhone
        val account = accounts.find(userId) ?: return Resource.error(SharedError.TokenInvalid)

        val result = ownership.confirm(account, phone, command.principal.deviceId)
        if (result is Resource.Error && result.error == IdentityError.PhoneTaken) {
            cache.markOtpProven(command.verificationId) // the SIM is proven; the number is still another account's
        } else {
            cache.deleteOtp(command.verificationId)
        }
        return result
    }

    private companion object {
        const val LOCK_SECONDS = 600L
    }
}
