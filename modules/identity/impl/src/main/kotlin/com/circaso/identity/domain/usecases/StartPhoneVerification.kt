package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.StartPhoneCommand
import com.circaso.identity.domain.models.StartPhoneResult
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.PendingOtp
import com.circaso.identity.domain.ports.PhoneProtection
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.support.IdentityConfig
import com.circaso.identity.domain.support.randomToken
import com.circaso.identity.domain.support.sha256Hex
import com.circaso.integrations.api.SmsGateway
import com.circaso.integrations.api.SmsPurpose
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import java.security.SecureRandom

/**
 * I3: sends a 6-digit code through `SmsGateway`. Identity generates, hashes and counts the code and words the
 * text; the gateway only delivers it. Limits: 3 codes per number per 10 minutes, 10 per phone per day, and the
 * account locks for 10 minutes after 5 wrong codes.
 */
class StartPhoneVerification(
    private val cache: IdentityCache,
    private val accounts: AccountRepository,
    private val phones: PhoneProtection,
    private val sms: SmsGateway,
    private val config: IdentityConfig,
) {
    private val random = SecureRandom()

    suspend operator fun invoke(command: StartPhoneCommand): Resource<StartPhoneResult> {
        val userId = command.principal.userId
        val account = accounts.find(userId) ?: return Resource.error(SharedError.TokenInvalid)
        if (account.status == AccountStatus.ACTIVE && account.phone != command.phone) return Resource.error(IdentityError.PhoneMismatch)

        cache.codeLockRemaining(userId)?.let { return Resource.error(IdentityError.CodeLocked(it.seconds.coerceAtLeast(1))) }

        val phoneHmac = phones.hmac(command.phone)
        if (cache.countCodeRequestForPhone(phoneHmac) > MAX_PER_PHONE) {
            return Resource.error(SharedError.RateLimited(cache.codeRequestRetryAfter(phoneHmac)?.seconds ?: 600))
        }
        if (cache.countCodeRequestForDevice(command.principal.deviceId) > MAX_PER_DEVICE) {
            return Resource.error(SharedError.RateLimited(86_400))
        }

        val code = "%0${config.codeLength}d".format(random.nextInt(Math.pow(10.0, config.codeLength.toDouble()).toInt()))
        val verificationId = randomToken(16)
        cache.saveOtp(
            verificationId,
            PendingOtp(phoneHmac, phones.seal(command.phone), sha256Hex("$verificationId:$code"), userId, command.principal.deviceId),
        )

        val text = "Your Circaso code is $code. Don't share it." + (config.smsRetrieverHash?.let { " $it" } ?: "")
        return when (sms.send(command.phone, text, SmsPurpose.VERIFICATION)) {
            is Resource.Success -> Resource.success(
                StartPhoneResult(verificationId, config.codeLength, config.codeTtl.seconds, config.resendAfter.seconds),
            )
            is Resource.Error -> {
                cache.deleteOtp(verificationId)
                Resource.error(IdentityError.SmsUnavailable)
            }
        }
    }

    private companion object {
        const val MAX_PER_PHONE = 3
        const val MAX_PER_DEVICE = 10
    }
}
