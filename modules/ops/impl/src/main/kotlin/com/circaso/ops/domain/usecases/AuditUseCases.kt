package com.circaso.ops.domain.usecases

import com.circaso.ops.domain.models.AuditFilter
import com.circaso.ops.domain.models.AuditPage
import com.circaso.ops.domain.repositories.AuditRepository
import com.circaso.shared.Resource
import java.util.UUID

/** The audit log, newest first (ADMIN). Paged by the last entry id, which sorts by time. */
class ListAudit(private val audit: AuditRepository) {
    suspend operator fun invoke(
        filter: AuditFilter,
        before: UUID?,
        limit: Int,
    ): Resource<AuditPage> {
        val rows = audit.list(filter, before, limit + 1)
        val page = rows.take(limit)
        return Resource.success(
            AuditPage(
                page,
                page
                    .lastOrNull()
                    ?.id
                    ?.toString()
                    ?.takeIf { rows.size > limit },
            ),
        )
    }
}
