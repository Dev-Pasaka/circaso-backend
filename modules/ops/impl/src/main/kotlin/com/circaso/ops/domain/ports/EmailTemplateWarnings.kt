package com.circaso.ops.domain.ports

/** Tells the people on call that the email template ids couldn't be read. Never an address or a template's content. */
interface EmailTemplateWarnings {
    fun unread(error: String)
}
