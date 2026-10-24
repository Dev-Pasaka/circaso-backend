package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.RecoveryCommand
import com.circaso.identity.domain.models.RecoveryStatus
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.SupportRecovery
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.PhoneProtection
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.EmailTemplate
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.Uuid7

/**
 * I15: opens a support case for a lost Google/Apple login. Only an account that completed identity verification
 * can be restored, so anything else, including a number nobody holds, answers the same way. The caller's new
 * Google/Apple login is kept on the case: that is the login support relinks to the account.
 * Three requests per number per day.
 */
class RequestSupportRecovery(
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val store: IdentityStore,
    private val cache: IdentityCache,
    private val phones: PhoneProtection,
    private val email: EmailGateway,
) {
    suspend operator fun invoke(command: RecoveryCommand): Resource<SupportRecovery> {
        if (cache.countRecoveryRequest(phones.hmac(command.phone)) > MAX_PER_NUMBER_PER_DAY) {
            return Resource.error(SharedError.RateLimited(86_400))
        }
        val target = accounts.findByPhone(command.phone)
        if (target == null ||
            target.verification != Verification.VERIFIED
        ) {
            return Resource.error(IdentityError.RecoveryNotEligible)
        }

        val newLogin = providers.links(command.principal.userId).firstOrNull()
        val recovery =
            SupportRecovery(
                userId = target.userId,
                requestId = Uuid7.generate(),
                status = RecoveryStatus.RECEIVED,
                contactEmail = command.contactEmail,
                note = command.note,
                idCheck = null,
                selfieMatch = null,
                agentId = null,
                noticeSentAt = null,
                relinkAfter = null,
                newProvider = newLogin?.provider,
                newSubject = newLogin?.subject,
            )
        store.commit(
            Changes(
                recoveries = listOf(recovery),
                securityEvents =
                    listOf(
                        SecurityEvent(target.userId, null, SecurityEventType.RECOVERY_REQUESTED, null, null),
                    ),
            ),
        )
        providers
            .links(
                target.userId,
            ).firstNotNullOfOrNull { it.email }
            ?.let { email.send(it, EmailTemplate.RECOVERY_STARTED) }
        return Resource.success(recovery)
    }

    private companion object {
        const val MAX_PER_NUMBER_PER_DAY = 3
    }
}
