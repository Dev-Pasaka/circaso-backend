package com.circaso.ops.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.integrations.api.EmailTemplate
import com.circaso.ops.data.tables.EmailTemplatesTable
import com.circaso.ops.domain.models.EmailTemplateId
import com.circaso.ops.domain.ports.EmailTemplateWarnings
import com.circaso.ops.domain.repositories.EmailTemplateRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * `ops.email_templates`. A first row is `INSERT … ON CONFLICT DO NOTHING`, so two processes importing the same seed at
 * once write one row; a change is `SET version = version + 1 WHERE version = ?`, so a stale save finds no row.
 */
class EmailTemplateRepositoryImpl(private val database: Database) : EmailTemplateRepository {
    override suspend fun load(): Map<EmailTemplate, EmailTemplateId> =
        database.tx("email-templates.load") {
            EmailTemplatesTable
                .selectAll()
                .map { it.toId() }
                .toList()
                .filterNotNull()
                .associateBy { it.template }
        }

    override suspend fun insert(
        template: EmailTemplate,
        providerTemplateId: String,
        by: String?,
        at: Instant,
    ): Boolean =
        database.tx("email-templates.insert") {
            EmailTemplatesTable
                .insertIgnore {
                    it[EmailTemplatesTable.template] = template.name
                    it[EmailTemplatesTable.providerTemplateId] = providerTemplateId
                    it[updatedBy] = by
                    it[updatedAt] = at
                    it[version] = 1
                }.insertedCount > 0
        }

    override suspend fun update(
        template: EmailTemplate,
        providerTemplateId: String,
        expectedVersion: Long?,
        by: String,
        at: Instant,
    ): EmailTemplateId? =
        database.tx("email-templates.update") {
            EmailTemplatesTable
                .updateReturning(where = {
                    val row = EmailTemplatesTable.template eq template.name
                    if (expectedVersion == null) row else row and (EmailTemplatesTable.version eq expectedVersion)
                }) {
                    it[EmailTemplatesTable.providerTemplateId] = providerTemplateId
                    it[updatedBy] = by
                    it[updatedAt] = at
                    it[version] = version + 1
                }.map { it.toId() }
                .singleOrNull()
        }

    private fun ResultRow.toId(): EmailTemplateId? {
        val name = this[EmailTemplatesTable.template]
        val template = EmailTemplate.entries.firstOrNull { it.name == name } ?: return null
        return EmailTemplateId(
            template = template,
            providerTemplateId = this[EmailTemplatesTable.providerTemplateId],
            updatedBy = this[EmailTemplatesTable.updatedBy],
            updatedAt = this[EmailTemplatesTable.updatedAt],
            version = this[EmailTemplatesTable.version],
        )
    }
}

/** A WARN line when the template ids can't be read: the error's class, nothing else. */
class LoggedEmailTemplateWarnings : EmailTemplateWarnings {
    private val log = LoggerFactory.getLogger(LoggedEmailTemplateWarnings::class.java)

    override fun unread(error: String) {
        log.warn("email template ids not read, the last read stays in force: error={}", error)
    }
}
