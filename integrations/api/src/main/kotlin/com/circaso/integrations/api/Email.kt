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

    /** A ramp merchant's trading session ended because the server restarted (decision 2026-11-23). */
    MERCHANT_TAKEN_OFFLINE,

    // Merchant security alerts (2026-11-24): always sent, never switched off; each carries a "This wasn't me" link.

    /** A one-time code that confirms a security change on the portal (adding or removing a passkey). */
    MERCHANT_SECURITY_CODE,
    MERCHANT_NEW_SIGN_IN,
    MERCHANT_ADDRESS_ADDED,
    MERCHANT_ADDRESS_REMOVED,
    MERCHANT_WITHDRAWAL_REQUESTED,
    MERCHANT_WITHDRAWAL_SENT,
    MERCHANT_WITHDRAWAL_COMPLETED,
    MERCHANT_WITHDRAWAL_FAILED,
    MERCHANT_PIN_CHANGED,
    MERCHANT_PAYMENT_ACCOUNT_CHANGED,
    MERCHANT_PASSKEY_ADDED,
    MERCHANT_PASSKEY_REMOVED,

    /** Confirms that "This wasn't me" locked the merchant's withdrawals. */
    MERCHANT_WITHDRAWALS_LOCKED,

    // Merchant activity emails (2026-11-24): the merchant can switch these off on the portal.
    MERCHANT_BUY_COMPLETED,
    MERCHANT_SELL_COMPLETED,
    MERCHANT_TOPUP_RESULT,
    MERCHANT_DAILY_SUMMARY,
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

    /**
     * A ramp merchant was online (until they stopped, or in a window) when the server restarted: their unlocked keys
     * lived only in its memory, so they are offline and must enter their PIN again to go back online (2026-11-23).
     */
    data object MerchantTakenOffline : Email {
        override val template = EmailTemplate.MERCHANT_TAKEN_OFFLINE
    }

    /**
     * A one-time code confirming a security change on the merchant portal (2026-11-24): [action] says which, in words
     * ("add a passkey"). The string form hides the code.
     */
    class MerchantSecurityCode(val code: String, val minutes: Int, val action: String) : Email {
        override val template = EmailTemplate.MERCHANT_SECURITY_CODE

        override fun toString(): String = "MerchantSecurityCode(***)"
    }

    /**
     * A merchant security alert (2026-11-24). Every one carries [time] (when it happened, in words, Nairobi time) and
     * [lockUrl], the one-time "This wasn't me" link that locks the merchant's withdrawals without signing in (null only
     * where the server can't sign one). The string forms leave the link out.
     */
    sealed interface MerchantSecurityAlert : Email {
        val time: String
        val lockUrl: String?
    }

    /** Someone signed in to the merchant portal: [method] ("email code" or "passkey"), the browser, roughly where. */
    data class MerchantNewSignIn(
        val method: String,
        val device: String,
        val location: String,
        override val time: String,
        override val lockUrl: String?,
    ) : MerchantSecurityAlert {
        override val template = EmailTemplate.MERCHANT_NEW_SIGN_IN

        override fun toString(): String = "MerchantNewSignIn($method)"
    }

    data class MerchantAddressAdded(
        val label: String,
        val address: String,
        val network: String,
        val usableFrom: String,
        override val time: String,
        override val lockUrl: String?,
    ) : MerchantSecurityAlert {
        override val template = EmailTemplate.MERCHANT_ADDRESS_ADDED

        override fun toString(): String = "MerchantAddressAdded"
    }

    data class MerchantAddressRemoved(
        val label: String,
        val address: String,
        val network: String,
        override val time: String,
        override val lockUrl: String?,
    ) : MerchantSecurityAlert {
        override val template = EmailTemplate.MERCHANT_ADDRESS_REMOVED

        override fun toString(): String = "MerchantAddressRemoved"
    }

    data class MerchantWithdrawalRequested(
        val amount: String,
        val label: String,
        val address: String,
        val network: String,
        override val time: String,
        override val lockUrl: String?,
    ) : MerchantSecurityAlert {
        override val template = EmailTemplate.MERCHANT_WITHDRAWAL_REQUESTED

        override fun toString(): String = "MerchantWithdrawalRequested"
    }

    data class MerchantWithdrawalSent(
        val amount: String,
        val label: String,
        val address: String,
        val txUrl: String,
        override val time: String,
        override val lockUrl: String?,
    ) : MerchantSecurityAlert {
        override val template = EmailTemplate.MERCHANT_WITHDRAWAL_SENT

        override fun toString(): String = "MerchantWithdrawalSent"
    }

    data class MerchantWithdrawalCompleted(
        val amount: String,
        val label: String,
        val address: String,
        val txUrl: String,
        override val time: String,
        override val lockUrl: String?,
    ) : MerchantSecurityAlert {
        override val template = EmailTemplate.MERCHANT_WITHDRAWAL_COMPLETED

        override fun toString(): String = "MerchantWithdrawalCompleted"
    }

    data class MerchantWithdrawalFailed(
        val amount: String,
        val label: String,
        val address: String,
        val reason: String,
        override val time: String,
        override val lockUrl: String?,
    ) : MerchantSecurityAlert {
        override val template = EmailTemplate.MERCHANT_WITHDRAWAL_FAILED

        override fun toString(): String = "MerchantWithdrawalFailed"
    }

    data class MerchantPinChanged(override val time: String, override val lockUrl: String?) : MerchantSecurityAlert {
        override val template = EmailTemplate.MERCHANT_PIN_CHANGED

        override fun toString(): String = "MerchantPinChanged"
    }

    /** [what]: which account changed, in words ("PayHero account", "FinoQ payout account"). */
    data class MerchantPaymentAccountChanged(
        val what: String,
        override val time: String,
        override val lockUrl: String?,
    ) : MerchantSecurityAlert {
        override val template = EmailTemplate.MERCHANT_PAYMENT_ACCOUNT_CHANGED

        override fun toString(): String = "MerchantPaymentAccountChanged"
    }

    data class MerchantPasskeyAdded(val label: String, override val time: String, override val lockUrl: String?) :
        MerchantSecurityAlert {
        override val template = EmailTemplate.MERCHANT_PASSKEY_ADDED

        override fun toString(): String = "MerchantPasskeyAdded"
    }

    data class MerchantPasskeyRemoved(val label: String, override val time: String, override val lockUrl: String?) :
        MerchantSecurityAlert {
        override val template = EmailTemplate.MERCHANT_PASSKEY_REMOVED

        override fun toString(): String = "MerchantPasskeyRemoved"
    }

    /** "This wasn't me" locked the withdrawals; [cancelled] withdrawals not yet sent were cancelled. No lock link. */
    data class MerchantWithdrawalsLocked(val cancelled: String, val time: String) : Email {
        override val template = EmailTemplate.MERCHANT_WITHDRAWALS_LOCKED
    }

    /** A buy the merchant filled completed: shillings in, USDC out, what they earned. */
    data class MerchantBuyCompleted(
        val order: String,
        val kesReceived: String,
        val usdcSent: String,
        val earned: String,
        val time: String,
    ) : Email {
        override val template = EmailTemplate.MERCHANT_BUY_COMPLETED
    }

    /** A sale the merchant filled completed: USDC in, shillings paid out, what they earned. */
    data class MerchantSellCompleted(
        val order: String,
        val usdcReceived: String,
        val kesPaid: String,
        val earned: String,
        val time: String,
    ) : Email {
        override val template = EmailTemplate.MERCHANT_SELL_COMPLETED
    }

    /** A top-up of one of the merchant's provider wallets settled: [result] in words. */
    data class MerchantTopUpResult(
        val wallet: String,
        val amount: String,
        val result: String,
        val time: String,
    ) : Email {
        override val template = EmailTemplate.MERCHANT_TOPUP_RESULT
    }

    /** One Nairobi day's trading for a merchant who asked for a daily summary. */
    data class MerchantDailySummary(
        val day: String,
        val orders: String,
        val bought: String,
        val sold: String,
        val earned: String,
    ) : Email {
        override val template = EmailTemplate.MERCHANT_DAILY_SUMMARY
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
