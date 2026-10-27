package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DomainError
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

/**
 * I12: removes a sign-in account, only if the other one stays. The count is read under the account's lock, so two
 * unlinks at once can't remove both logins. Deleting the link also frees its login for another account.
 */
class UnlinkProvider(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val store: IdentityStore,
) {
    suspend operator fun invoke(principal: Principal, provider: SocialProvider): Resource<Unit> {
        val refused: DomainError? =
            transactor.run("providers.unlink") {
                accounts.lock(principal.userId) ?: return@run SharedError.NotFound
                val links = providers.links(principal.userId)
                val link = links.firstOrNull { it.provider == provider } ?: return@run SharedError.NotFound
                if (links.size < 2) return@run IdentityError.ProviderLast
                store.write(
                    Changes(
                        removedProviderLinks = listOf(link),
                        securityEvents =
                            listOf(
                                SecurityEvent(
                                    principal.userId,
                                    null,
                                    SecurityEventType.PROVIDER_UNLINKED,
                                    null,
                                    provider.name,
                                ),
                            ),
                    ),
                )
                null
            }
        return if (refused == null) Resource.success(Unit) else Resource.error(refused)
    }
}
