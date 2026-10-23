package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.ReleaseCommand
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.PhoneProtection
import com.circaso.identity.domain.ports.PhoneRelease
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.identity.domain.support.IdentityConfig
import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.EmailTemplate
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import java.time.Clock
import java.time.Instant

/**
 * I16: frees a number held by a lost, unverified account, 7 days after the SIM was proven and the old account
 * declared abandoned. Verified accounts are recovered by support instead. The number changes hands the next time
 * the same person verifies it after the notice has run out (see `PhoneOwnership`). One request per number per 7 days.
 *
 * TODO(decision: how the old phone cancels the release) there is no route for it yet.
 */
class RequestPhoneRelease(
    private val cache: IdentityCache,
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val phones: PhoneProtection,
    private val store: IdentityStore,
    private val email: EmailGateway,
    private val config: IdentityConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: ReleaseCommand): Resource<Instant> {
        if (!command.confirmAbandon) return Resource.error(SharedError.Malformed(mapOf("confirmAbandon" to "must be true")))
        val userId = command.principal.userId
        val otp = cache.otp(command.verificationId)
        if (otp == null || !otp.proven || otp.userId != userId) return Resource.error(IdentityError.CodeExpired)

        val phone = phones.unseal(otp.sealedPhone) ?: return Resource.error(IdentityError.CodeExpired)
        val owner = accounts.findByPhone(phone) ?: return Resource.error(IdentityError.ReleaseNotAllowed)
        if (owner.userId == userId || owner.verification == Verification.VERIFIED) return Resource.error(IdentityError.ReleaseNotAllowed)
        if (!cache.countPhoneRelease(otp.phoneHmac)) return Resource.error(SharedError.RateLimited(config.phoneReleaseNotice.seconds))

        val releaseAt = clock.instant().plus(config.phoneReleaseNotice)
        cache.savePhoneRelease(otp.phoneHmac, PhoneRelease(owner.userId, userId, releaseAt.toEpochMilli()))
        cache.deleteOtp(command.verificationId)
        store.commit(
            Changes(securityEvents = listOf(SecurityEvent(owner.userId, null, SecurityEventType.PHONE_RELEASE_REQUESTED, null, null))),
        )
        providers.links(owner.userId).firstNotNullOfOrNull { it.email }?.let { email.send(it, EmailTemplate.RECOVERY_STARTED) }
        return Resource.success(releaseAt)
    }
}
