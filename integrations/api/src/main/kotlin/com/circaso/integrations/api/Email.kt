package com.circaso.integrations.api

import com.circaso.shared.Resource

interface EmailGateway {
    /**
     * Sends [email]: its template, with that template's own variables filled in. The wording and layout live in the
     * provider's template (Resend), which the vendor implementation looks up from config and fills from the typed
     * fields; the caller decides which email and with what values. [idempotencyKey], when given, makes a repeat of
     * the same send (a retry) deliver once; the vendor remembers it for 24 hours.
     */
    suspend fun send(
        to: String,
        email: Email,
        idempotencyKey: String? = null,
    ): Resource<EmailReceipt>
}

/** The emails identity sends: security notices, and one welcome when an account first becomes active. Each maps to a provider template id in config (`integrations.email.templates.<name>`). */
enum class EmailTemplate { NEW_PHONE_SIGN_IN, RECOVERY_STARTED, RECOVERY_COMPLETED, WELCOME, PIN_VAULT_OPENED }

/**
 * One email to send, a class per [EmailTemplate] carrying exactly that template's variables (decision 2026-10-27:
 * typed, never a map of names to strings). The vendor maps the fields to its own variable names.
 */
sealed interface Email {
    val template: EmailTemplate

    /** Someone signed in on a new phone; [deviceModel] names it, so the owner knows which. */
    data class NewPhoneSignIn(val deviceModel: String) : Email {
        override val template = EmailTemplate.NEW_PHONE_SIGN_IN
    }

    data object RecoveryStarted : Email {
        override val template = EmailTemplate.RECOVERY_STARTED
    }

    data object RecoveryCompleted : Email {
        override val template = EmailTemplate.RECOVERY_COMPLETED
    }

    data object Welcome : Email {
        override val template = EmailTemplate.WELCOME
    }

    /**
     * The PIN Vault was opened (2026-11-11): the backed-up wallet PIN was fetched to be decrypted on [deviceModel], and
     * payments from the wallet are paused for 24 hours.
     */
    data class PinVaultOpened(val deviceModel: String) : Email {
        override val template = EmailTemplate.PIN_VAULT_OPENED
    }
}

data class EmailReceipt(val messageId: String, val provider: String)
