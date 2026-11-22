package com.circaso.ops.domain.models

import com.circaso.integrations.api.EmailTemplate
import java.time.Instant

/**
 * The id one email is sent with at the provider, as staff set it. [updatedBy] is null for an id imported once from
 * the old environment variable. [version] rises on each change.
 */
data class EmailTemplateId(
    val template: EmailTemplate,
    val providerTemplateId: String,
    val updatedBy: String?,
    val updatedAt: Instant,
    val version: Long,
)

/** One email as the admin lists it: [id] is null while no template id is set, and that email isn't sent. */
data class EmailTemplateStatus(val template: EmailTemplate, val id: EmailTemplateId?)

/** What a change did: the row before (null when there was none) and after. */
data class EmailTemplateChange(val before: EmailTemplateId?, val after: EmailTemplateId)
