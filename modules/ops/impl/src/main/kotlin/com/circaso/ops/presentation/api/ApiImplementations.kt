package com.circaso.ops.presentation.api

import com.circaso.integrations.api.EmailTemplate
import com.circaso.integrations.api.EmailTemplateIds
import com.circaso.ops.api.StaffDirectory
import com.circaso.ops.api.StaffMember
import com.circaso.ops.domain.models.Staff
import com.circaso.ops.presentation.services.EmailTemplateService
import com.circaso.ops.presentation.services.StaffDirectoryService
import com.circaso.shared.getOrNull

/**
 * `integrations:api`'s [EmailTemplateIds], which the email vendor asks on each send (decision 2026-11-22): integrations
 * declares it and ops, which owns the table, answers it, so integrations never depends on a module.
 */
class StoredEmailTemplateIds(private val templates: EmailTemplateService) : EmailTemplateIds {
    override suspend fun idOf(template: EmailTemplate): String? = templates.idOf(template).getOrNull()
}

/** What the ops gateway asks on every staff request: is this verified email an active staff member, and with which role. */
class StaffDirectoryImpl(private val directory: StaffDirectoryService) : StaffDirectory {
    override suspend fun signIn(email: String): StaffMember? = directory.signIn(email).getOrNull()?.toMember()

    override suspend fun recordDevLogin(staff: StaffMember, requestId: String?) {
        val row = directory.signIn(staff.email).getOrNull() ?: return
        directory.devLogin(row, requestId)
    }

    private fun Staff.toMember() = StaffMember(id, email, role, active, fromConfig)
}
