package com.circaso.ops.presentation.services

import com.circaso.integrations.api.EmailTemplate
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.EmailTemplateChange
import com.circaso.ops.domain.models.EmailTemplateStatus
import com.circaso.ops.domain.usecases.GetEmailTemplateId
import com.circaso.ops.domain.usecases.ListEmailTemplates
import com.circaso.ops.domain.usecases.SetEmailTemplate
import com.circaso.shared.Resource

/** Email template ids (decision 2026-11-22): what the vendor asks on each send, and what staff list and set. */
class EmailTemplateService(
    private val getEmailTemplateId: GetEmailTemplateId,
    private val listEmailTemplates: ListEmailTemplates,
    private val setEmailTemplate: SetEmailTemplate,
) {
    suspend fun idOf(template: EmailTemplate): Resource<String?> = getEmailTemplateId(template)

    suspend fun templates(): Resource<List<EmailTemplateStatus>> = listEmailTemplates()

    suspend fun set(
        actor: Actor,
        template: EmailTemplate,
        providerTemplateId: String,
        expectedVersion: Long?,
        reason: String,
    ): Resource<EmailTemplateChange> = setEmailTemplate(actor, template, providerTemplateId, expectedVersion, reason)
}
