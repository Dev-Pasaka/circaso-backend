package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

/** I12: removes a sign-in account, only if the other one stays. */
class UnlinkProvider(private val providers: ProviderRepository, private val store: IdentityStore) {
    suspend operator fun invoke(principal: Principal, provider: SocialProvider): Resource<Unit> {
        val links = providers.links(principal.userId)
        val link = links.firstOrNull { it.provider == provider } ?: return Resource.error(SharedError.NotFound)
        if (links.size < 2) return Resource.error(IdentityError.ProviderLast)
        store.commit(
            Changes(
                removedProviderLinks = listOf(link),
                securityEvents = listOf(SecurityEvent(principal.userId, null, SecurityEventType.PROVIDER_UNLINKED, null, provider.name)),
            ),
        )
        store.releaseProvider(link.provider, link.subject)
        return Resource.success(Unit)
    }
}
