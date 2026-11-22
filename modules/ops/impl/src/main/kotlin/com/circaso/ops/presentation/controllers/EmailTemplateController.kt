package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.presentation.dto.EmailTemplateDto
import com.circaso.ops.presentation.dto.EmailTemplatesResponse
import com.circaso.ops.presentation.dto.SetEmailTemplateRequest
import com.circaso.ops.presentation.mappers.FORBIDDEN
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.TAG_EMAIL_TEMPLATES
import com.circaso.ops.presentation.mappers.actor
import com.circaso.ops.presentation.mappers.parseEmailTemplate
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toDto
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.services.EmailTemplateService
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * The email template ids (decision 2026-11-22): the environment holds keys and secrets only, so the id each email is
 * sent with at the provider is set here. FINANCE and ADMIN read (`email_templates.read`); ADMIN sets
 * (`email_templates.manage`). Every change is audited.
 */
fun Route.emailTemplateController(templates: EmailTemplateService) {
    route("/ops/email-templates") {
        staffOnly(Permission.EMAIL_TEMPLATES_READ.roles) {
            get {
                val result = templates.templates()
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, EmailTemplatesResponse>(
                "OPS · The emails Circaso sends and their template ids",
                TAG_EMAIL_TEMPLATES,
                listOf(FORBIDDEN),
                notes =
                    "${roles("FINANCE", "ADMIN")} Every email, with the id of its template at the provider " +
                        "(Resend), or `set: false` while it has none: that email is then not sent, and nothing " +
                        "else is affected. An id that was in the old `INTEGRATIONS_EMAIL_TEMPLATES_<NAME>` " +
                        "variable is imported once and shows with no `updatedBy`.",
            )
        }
        staffOnly(Permission.EMAIL_TEMPLATES_MANAGE.roles) {
            put("{template}") {
                val request = call.receive<SetEmailTemplateRequest>()
                val result =
                    parseEmailTemplate(call.parameters["template"]).flatMap {
                        templates.set(call.actor(), it, request.providerTemplateId, request.version, request.reason)
                    }
                call.respondDefault(result.map { it.after }.toDefaultResponse { it.toDto() })
            }.docs<SetEmailTemplateRequest, EmailTemplateDto>(
                "OPS · Set an email's template id",
                TAG_EMAIL_TEMPLATES,
                listOf(
                    "ops.reason_required",
                    "ops.email_template_not_found",
                    "ops.email_template_invalid",
                    "ops.email_template_changed",
                    MALFORMED,
                    FORBIDDEN,
                ),
                notes =
                    "${roles("ADMIN")} `{template}` is the email's name (`WELCOME`). Audited with the reason and " +
                        "the id before and after. Send back the `version` that was read (0 when none was set): a " +
                        "change made meanwhile answers `ops.email_template_changed`. Publish the template at the " +
                        "provider first. Both servers send with the new id within about ten seconds.",
            )
        }
    }
}
