package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.AuditFilter
import com.circaso.ops.domain.models.AuditPage
import com.circaso.ops.domain.usecases.ListAudit
import com.circaso.shared.Resource
import java.util.UUID

class AuditService(private val listAudit: ListAudit) {
    suspend fun list(
        filter: AuditFilter,
        before: UUID?,
        limit: Int,
    ): Resource<AuditPage> = listAudit(filter, before, limit)
}
