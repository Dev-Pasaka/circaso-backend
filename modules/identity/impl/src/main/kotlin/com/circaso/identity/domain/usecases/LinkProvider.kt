package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.errors.ClaimLost
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.LinkProviderCommand
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SocialSignIn
import com.circaso.shared.Resource
import java.time.Clock

/**
 * I12: adds a Google or Apple login to the account. A login that belongs to another account is refused: the unique
 * `(provider, subject)` index settles two accounts claiming it at once.
 */
class LinkProvider(
    private val transactor: Transactor,
    private val cache: IdentityCache,
    private val social: SocialSignIn,
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val store: IdentityStore,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: LinkProviderCommand): Resource<Unit> {
        if (!cache.consumeNonce(command.nonce)) return Resource.error(IdentityError.NonceInvalid)
        val identity =
            when (val verified = social.verify(command.provider, command.idToken, command.nonce)) {
                is Resource.Success -> {
                    verified.data
                }

                is Resource.Error -> {
                    return if (verified.error is IntegrationError.Rejected) {
                        Resource.error(
                            IdentityError.ProviderTokenInvalid,
                        )
                    } else {
                        verified
                    }
                }
            }
        val userId = command.principal.userId
        val link = ProviderLink(userId, command.provider, identity.subject, identity.email, clock.instant())
        val linked =
            try {
                transactor.run("providers.link") {
                    accounts.lock(userId)
                    val claimed = providers.claim(link)
                    if (claimed) {
                        val event =
                            SecurityEvent(userId, null, SecurityEventType.PROVIDER_LINKED, null, command.provider.name)
                        store.write(Changes(securityEvents = listOf(event)))
                    }
                    claimed
                }
            } catch (_: ClaimLost) {
                false
            }
        return if (linked) Resource.success(Unit) else Resource.error(IdentityError.ProviderInUse)
    }
}
