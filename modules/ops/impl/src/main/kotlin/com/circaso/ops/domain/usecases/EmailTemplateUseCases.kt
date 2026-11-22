package com.circaso.ops.domain.usecases

import com.circaso.integrations.api.EmailTemplate
import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.models.EmailTemplateChange
import com.circaso.ops.domain.models.EmailTemplateStatus
import com.circaso.ops.domain.repositories.EmailTemplateRepository
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.ops.domain.support.EmailTemplateBook
import com.circaso.ops.domain.support.Reasons
import com.circaso.shared.Resource
import java.time.Clock

/**
 * `EmailTemplateIds.idOf`, for the email vendor on each send: the template's id as the ten-second copy has it, or
 * null when none is set (that one email then isn't sent).
 */
class GetEmailTemplateId(private val book: EmailTemplateBook) {
    suspend operator fun invoke(template: EmailTemplate): Resource<String?> = Resource.success(book.idOf(template))
}

/** Every email the code sends, with the provider template id it is sent with, or none: read from the table now. */
class ListEmailTemplates(private val book: EmailTemplateBook) {
    suspend operator fun invoke(): Resource<List<EmailTemplateStatus>> {
        val ids = book.current()
        return Resource.success(EmailTemplate.entries.map { EmailTemplateStatus(it, ids[it]) })
    }
}

/**
 * Sets the provider template id one email is sent with (decision 2026-11-22). A reason is required and audited with
 * the id before and after (a template id is not a secret). [expectedVersion] is the version the admin read: 0 for a
 * template with no id yet, null to save over whatever is there; a change made meanwhile is refused.
 */
class SetEmailTemplate(
    private val repository: EmailTemplateRepository,
    private val book: EmailTemplateBook,
    private val trail: AuditTrail,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        actor: Actor,
        template: EmailTemplate,
        providerTemplateId: String,
        expectedVersion: Long?,
        reason: String,
    ): Resource<EmailTemplateChange> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        val id = providerTemplateId.trim()
        if (id.isEmpty() || id.length > MAX_LENGTH || id.any { it.isWhitespace() }) {
            return Resource.error(OpsError.EmailTemplateInvalid)
        }
        return trail.record<EmailTemplateChange>(
            actor,
            AuditAction.EMAIL_TEMPLATE_SET,
            template.name,
            reason = why,
            outcome = { "id ${it.before?.providerTemplateId ?: "(not set)"}→${it.after.providerTemplateId}" },
        ) { save(actor, template, id, expectedVersion) }
    }

    private suspend fun save(
        actor: Actor,
        template: EmailTemplate,
        id: String,
        expectedVersion: Long?,
    ): Resource<EmailTemplateChange> {
        val before = book.current()[template]
        val now = clock.instant()
        val after =
            if (before == null) {
                // No row yet: the first save writes it, unless the admin read a row that has since gone (it can't).
                val fresh = expectedVersion == null || expectedVersion == NOT_SET
                if (fresh && repository.insert(template, id, actor.email, now)) book.current()[template] else null
            } else {
                repository.update(template, id, expectedVersion, actor.email, now)
            } ?: return Resource.error(OpsError.EmailTemplateChanged)
        book.invalidate()
        return Resource.success(EmailTemplateChange(before, after))
    }

    private companion object {
        const val MAX_LENGTH = 200
        const val NOT_SET = 0L
    }
}
