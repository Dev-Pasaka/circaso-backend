package com.circaso.ops.presentation.mappers

import com.circaso.integrations.api.EmailTemplate
import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.EmailTemplateId
import com.circaso.ops.domain.models.EmailTemplateStatus
import com.circaso.ops.presentation.dto.EmailTemplateDto
import com.circaso.ops.presentation.dto.EmailTemplatesResponse
import com.circaso.shared.Resource

/** The template a path names (`WELCOME`, any case); anything else is `ops.email_template_not_found`. */
fun parseEmailTemplate(raw: String?): Resource<EmailTemplate> =
    EmailTemplate.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }?.let { Resource.success(it) }
        ?: Resource.error(OpsError.EmailTemplateNotFound)

private fun dto(template: EmailTemplate, id: EmailTemplateId?) =
    EmailTemplateDto(
        template = template.name,
        providerTemplateId = id?.providerTemplateId,
        set = id != null,
        version = id?.version ?: 0,
        updatedBy = id?.updatedBy,
        updatedAt = id?.updatedAt?.toString(),
    )

fun EmailTemplateId.toDto() = dto(template, this)

fun List<EmailTemplateStatus>.toResponse() = EmailTemplatesResponse(map { dto(it.template, it.id) })
