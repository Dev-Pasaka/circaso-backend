package com.circaso.ops

import com.circaso.integrations.api.EmailTemplate
import com.circaso.integrations.api.EmailTemplateSeeds
import com.circaso.ops.domain.models.EmailTemplateId
import com.circaso.ops.domain.ports.EmailTemplateWarnings
import com.circaso.ops.domain.repositories.EmailTemplateRepository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** `ops.email_templates` in memory, with the table's two claims: a first row is written once, a change needs its version. */
class MemoryEmailTemplates : EmailTemplateRepository {
    val rows = ConcurrentHashMap<EmailTemplate, EmailTemplateId>()

    @Volatile
    var failure: Exception? = null

    @Volatile
    var loads = 0

    override suspend fun load(): Map<EmailTemplate, EmailTemplateId> {
        failure?.let { throw it }
        loads++
        return rows.toMap()
    }

    override suspend fun insert(
        template: EmailTemplate,
        providerTemplateId: String,
        by: String?,
        at: Instant,
    ): Boolean = rows.putIfAbsent(template, EmailTemplateId(template, providerTemplateId, by, at, 1)) == null

    override suspend fun update(
        template: EmailTemplate,
        providerTemplateId: String,
        expectedVersion: Long?,
        by: String,
        at: Instant,
    ): EmailTemplateId? {
        val current = rows[template] ?: return null
        if (expectedVersion != null && current.version != expectedVersion) return null
        val changed =
            current.copy(
                providerTemplateId = providerTemplateId,
                updatedBy = by,
                updatedAt = at,
                version =
                    current.version + 1,
            )
        rows[template] = changed
        return changed
    }
}

/** The old `INTEGRATIONS_EMAIL_TEMPLATES_<NAME>` variables, as a test sets them; [asked] is every template looked up. */
class EnvSeeds : EmailTemplateSeeds {
    val values = ConcurrentHashMap<EmailTemplate, String>()
    val asked = CopyOnWriteArrayList<EmailTemplate>()

    override fun seed(template: EmailTemplate): String? {
        asked += template
        return values[template]
    }
}

class RecordingTemplateWarnings : EmailTemplateWarnings {
    val errors = CopyOnWriteArrayList<String>()

    override fun unread(error: String) {
        errors += error
    }
}
