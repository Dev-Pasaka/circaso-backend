package com.circaso.identity.presentation.services

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.LinkProviderCommand
import com.circaso.identity.domain.models.SecurityOverview
import com.circaso.identity.domain.models.SecurityPage
import com.circaso.identity.domain.usecases.GetSecurityOverview
import com.circaso.identity.domain.usecases.LinkProvider
import com.circaso.identity.domain.usecases.ListSecurityEvents
import com.circaso.identity.domain.usecases.RequestAccountDeletion
import com.circaso.identity.domain.usecases.UnlinkProvider
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.Resource

/** Account & security screen. */
class AccountService(
    private val getSecurityOverview: GetSecurityOverview,
    private val linkProvider: LinkProvider,
    private val unlinkProvider: UnlinkProvider,
    private val listSecurityEvents: ListSecurityEvents,
    private val requestAccountDeletion: RequestAccountDeletion,
) {
    suspend fun overview(principal: Principal): Resource<SecurityOverview> = getSecurityOverview(principal)
    suspend fun link(command: LinkProviderCommand): Resource<Unit> = linkProvider(command)
    suspend fun unlink(principal: Principal, provider: SocialProvider): Resource<Unit> = unlinkProvider(principal, provider)
    suspend fun securityLog(principal: Principal, cursor: String?, limit: Int): Resource<SecurityPage> = listSecurityEvents(principal, cursor, limit)
    suspend fun delete(principal: Principal): Resource<Unit> = requestAccountDeletion(principal)
}
