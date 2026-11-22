package com.circaso.ops.domain.repositories

import com.circaso.integrations.api.EmailTemplate
import com.circaso.ops.domain.models.EmailTemplateId
import java.time.Instant

/** `ops.email_templates`: the provider's template id per email. A row for a template the code no longer has is skipped. */
interface EmailTemplateRepository {
    suspend fun load(): Map<EmailTemplate, EmailTemplateId>

    /** Writes a template's first row; true when it was written, false when it already had one (which stays). */
    suspend fun insert(
        template: EmailTemplate,
        providerTemplateId: String,
        by: String?,
        at: Instant,
    ): Boolean

    /**
     * Changes a template's id. With [expectedVersion], only when the row is still at it; the row afterwards, or null
     * when someone changed it meanwhile or there is no row.
     */
    suspend fun update(
        template: EmailTemplate,
        providerTemplateId: String,
        expectedVersion: Long?,
        by: String,
        at: Instant,
    ): EmailTemplateId?
}
