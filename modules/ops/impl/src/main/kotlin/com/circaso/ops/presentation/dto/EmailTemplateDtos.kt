package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

/**
 * One email Circaso sends. `providerTemplateId` is the template's id at the provider (Resend), null while none is set:
 * that email is then not sent. `version` is 0 while none is set and rises on each change; send it back with a change.
 * `updatedBy` is null for an id imported once from the old environment variable.
 */
@Serializable
data class EmailTemplateDto(
    val template: String,
    val providerTemplateId: String? = null,
    val set: Boolean,
    val version: Long,
    val updatedBy: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class EmailTemplatesResponse(val templates: List<EmailTemplateDto>)

/** `PUT /ops/email-templates/{template}`: the id, the version read (0 when none was set), and why. */
@Serializable
data class SetEmailTemplateRequest(val providerTemplateId: String, val version: Long? = null, val reason: String = "")
