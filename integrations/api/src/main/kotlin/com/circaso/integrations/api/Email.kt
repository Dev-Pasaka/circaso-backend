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

/**
 * The emails Circaso sends: security notices, one welcome when an account first becomes active, and a merchant's
 * sign-in code. Each maps to a provider template id, which is a row staff set in the admin ([EmailTemplateIds]), not a
 * line of the environment (decision 2026-11-22).
 */
enum class EmailTemplate {
    NEW_PHONE_SIGN_IN,
    RECOVERY_STARTED,
    RECOVERY_COMPLETED,
    WELCOME,
    PIN_VAULT_OPENED,

    /** The ramp's merchant portal sign-in code (decision 2026-11-22). */
    MERCHANT_SIGN_IN_CODE,
}

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

    /**
     * The one-time code a ramp merchant signs in to the portal with (decision 2026-11-22). [code] is 6 digits and
     * lasts [minutes]; the string form hides it, so it never reaches a log line.
     */
    class MerchantSignInCode(val code: String, val minutes: Int) : Email {
        override val template = EmailTemplate.MERCHANT_SIGN_IN_CODE

        override fun toString(): String = "MerchantSignInCode(***)"
    }
}

data class EmailReceipt(val messageId: String, val provider: String)

/**
 * The provider's template id for each [EmailTemplate], as it stands now (decision 2026-11-22: the environment holds
 * keys and secrets only; template ids live in the database and are edited in the admin). Implemented by the ops
 * module, which owns the table, and looked up by the vendor on each send, so integrations never depends on a business
 * module (as custody reads the wallet's network settings through `CustodySetups`).
 */
interface EmailTemplateIds {
    /** The id to send [template] with; null when none is set yet: then that one email isn't sent. */
    suspend fun idOf(template: EmailTemplate): String?
}

/**
 * Where a template's id starts: the old `INTEGRATIONS_EMAIL_TEMPLATES_<NAME>` variable, when it is still set. Read
 * once, the first time a template has no row; after that the database wins and the variable is never read again.
 */
fun interface EmailTemplateSeeds {
    fun seed(template: EmailTemplate): String?
}
