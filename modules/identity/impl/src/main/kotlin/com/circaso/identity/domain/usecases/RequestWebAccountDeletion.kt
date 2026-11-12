package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.DeletionFeedback
import com.circaso.identity.domain.models.WebDeletionCommand
import com.circaso.identity.domain.models.WebDeletionOutcome
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.identity.domain.support.DeletionScheduler
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SocialSignIn
import com.circaso.shared.DomainError
import com.circaso.shared.Resource
import java.time.Clock

/**
 * I17 (2026-11-12): the website's account deletion page, which Google Play requires. The person signs in there with
 * Google or Apple; the ID token is checked exactly as at sign-in (I2: signature, issuer, audience, expiry and a nonce
 * from I1, used once), and the account is the one that owns that login through the unique `(provider, subject)`
 * index. Its deletion is then scheduled by [DeletionScheduler], as in the app, except that every session ends, since
 * no session asked. The reason they gave is stored apart from the account ([DeletionFeedback]).
 *
 * The answer never names the account. A login no account has, and a support account, are both `NoAccount`. Only an
 * active account, or one still finishing sign-up, can be deleted here; a suspended, blocked or recovering one is
 * refused with its own code, because staff are dealing with it.
 */
class RequestWebAccountDeletion(
    private val cache: IdentityCache,
    private val social: SocialSignIn,
    private val providers: ProviderRepository,
    private val accounts: AccountRepository,
    private val deletions: DeletionScheduler,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: WebDeletionCommand): Resource<WebDeletionOutcome> {
        if (!cache.consumeNonce(command.nonce)) return Resource.error(IdentityError.NonceInvalid)
        val identity =
            when (val verified = social.verify(command.provider, command.idToken, command.nonce)) {
                is Resource.Success -> {
                    verified.data
                }

                is Resource.Error -> {
                    return if (verified.error is IntegrationError.Rejected) {
                        Resource.error(IdentityError.ProviderTokenInvalid)
                    } else {
                        verified
                    }
                }
            }

        val owner = providers.ownerOf(command.provider, identity.subject) ?: return noAccount()
        val account = accounts.find(owner) ?: return noAccount()
        if (account.isSupport) return noAccount()
        when (account.status) {
            AccountStatus.SUSPENDED -> return refused(IdentityError.AccountSuspended)
            AccountStatus.BLOCKED -> return refused(IdentityError.AccountBlocked)
            AccountStatus.RECOVERY_PENDING -> return refused(IdentityError.RecoveryPending)
            AccountStatus.DELETED -> return Resource.success(WebDeletionOutcome.AlreadyScheduled(account.deleteAfter))
            AccountStatus.ACTIVE, AccountStatus.PENDING_PHONE -> Unit
        }

        val feedback = DeletionFeedback(null, command.reason, command.details, SOURCE, clock.instant())
        val outcome =
            when (val scheduled = deletions.schedule(account.userId, null, feedback, SOURCE)) {
                is DeletionScheduler.Outcome.Scheduled -> {
                    WebDeletionOutcome.Scheduled(scheduled.deleteAfter)
                }

                is DeletionScheduler.Outcome.AlreadyScheduled -> {
                    WebDeletionOutcome.AlreadyScheduled(scheduled.deleteAfter)
                }

                is DeletionScheduler.Outcome.Refused -> {
                    WebDeletionOutcome.Refused(scheduled.error.error.code, scheduled.error.error.message)
                }

                DeletionScheduler.Outcome.NotFound -> {
                    WebDeletionOutcome.NoAccount
                }
            }
        return Resource.success(outcome)
    }

    private fun noAccount(): Resource<WebDeletionOutcome> = Resource.success(WebDeletionOutcome.NoAccount)

    private fun refused(error: DomainError): Resource<WebDeletionOutcome> =
        Resource.success(WebDeletionOutcome.Refused(error.code, error.message))

    private companion object {
        const val SOURCE = "web"
    }
}
