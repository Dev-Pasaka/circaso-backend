package com.circaso.integrations.api

import com.circaso.shared.Resource

interface EmailGateway {
    /**
     * Sends [template] with [variables] filled in. The wording and layout live in the provider's template (Resend),
     * which the vendor implementation looks up from config; the caller decides which one and with what values.
     * [idempotencyKey], when given, makes a repeat of the same send (a retry) deliver once; the vendor remembers it for 24 hours.
     */
    suspend fun send(
        to: String,
        template: EmailTemplate,
        variables: Map<String, String> = emptyMap(),
        idempotencyKey: String? = null,
    ): Resource<EmailReceipt>
}

/** The emails identity sends: security notices, and one welcome when an account first becomes active. Each maps to a provider template id in config (`integrations.email.templates.<name>`). */
enum class EmailTemplate { NEW_PHONE_SIGN_IN, RECOVERY_STARTED, RECOVERY_COMPLETED, WELCOME }

data class EmailReceipt(val messageId: String, val provider: String)
