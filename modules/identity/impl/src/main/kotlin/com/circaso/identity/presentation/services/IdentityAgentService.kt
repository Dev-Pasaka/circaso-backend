package com.circaso.identity.presentation.services

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.SecuritySummary
import com.circaso.identity.domain.usecases.GetSecuritySummary
import com.circaso.shared.Resource

/** What Asha's read-only agent route reads (decision 2026-10-31): the caller's own account, reads only. */
class IdentityAgentService(private val getSecuritySummary: GetSecuritySummary) {
    suspend fun getSecurity(principal: Principal): Resource<SecuritySummary> = getSecuritySummary(principal)
}
