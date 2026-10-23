package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.SupportRecovery
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.identity.domain.repositories.RecoveryRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import java.util.UUID

/** I17: the status of the caller's own support case. Anyone else's case looks like one that doesn't exist. */
class GetRecoveryCase(private val recoveries: RecoveryRepository, private val providers: ProviderRepository) {
    suspend operator fun invoke(principal: Principal, requestId: UUID): Resource<SupportRecovery> {
        val recovery = recoveries.find(requestId) ?: return Resource.error(SharedError.NotFound)
        val callerLogins = providers.links(principal.userId)
        val theirs = callerLogins.any { it.provider == recovery.newProvider && it.subject == recovery.newSubject }
        return if (theirs) Resource.success(recovery) else Resource.error(SharedError.NotFound)
    }
}
