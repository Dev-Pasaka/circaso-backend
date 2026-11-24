package com.circaso.ramp.domain.models

import com.circaso.ramp.api.MerchantId
import com.circaso.shared.Network
import java.time.Instant
import java.util.UUID

// Merchant security (2026-11-24): passkeys, the step-up they give every sensitive action, alerts by email, and the
// "This wasn't me" lock.

/** One of a merchant's passkeys. The private key never leaves the merchant's device; [publicKeyCose] checks it. */
class Passkey(
    val passkeyId: UUID,
    val merchantId: MerchantId,
    val credentialId: ByteArray,
    val publicKeyCose: ByteArray,
    val signCount: Long,
    val label: String,
    val transports: List<String>,
    val backupEligible: Boolean,
    val backedUp: Boolean,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
) {
    override fun toString(): String = "Passkey($passkeyId, $merchantId)"
}

/** The actions a passkey must confirm, on top of the PIN, once a merchant has one. */
enum class StepUpAction {
    ADD_ADDRESS,
    REMOVE_ADDRESS,
    WITHDRAW,
    CHANGE_PIN,
    PAYMENT_ACCOUNT,
    PAYOUT_ACCOUNT,
    REMOVE_PASSKEY,
}

/**
 * What a passkey confirmation is bound to: the merchant, the action and the exact request (its fields, never the PIN
 * or a password). The challenge the passkey signs is derived from [canonical]'s hash, so an assertion made for one
 * request can't confirm another: a different amount, address or action gives a different challenge.
 */
data class StepUpBinding(
    val merchantId: MerchantId,
    val action: StepUpAction,
    val network: Network?,
    val fields: List<String>,
) {
    /** One line per part, so no field can run into the next. */
    fun canonical(): String =
        (listOf("circaso-ramp-step-up/v1", merchantId.toString(), action.name, network?.name.orEmpty()) + fields)
            .joinToString("\n") { it.replace("\n", " ") }

    companion object {
        fun addAddress(
            merchantId: MerchantId,
            network: Network,
            address: String,
            label: String,
        ) = StepUpBinding(
            merchantId,
            StepUpAction.ADD_ADDRESS,
            network,
            listOf(address.trim().lowercase(), label.trim()),
        )

        fun removeAddress(
            merchantId: MerchantId,
            network: Network,
            destinationId: UUID,
        ) = StepUpBinding(merchantId, StepUpAction.REMOVE_ADDRESS, network, listOf(destinationId.toString()))

        fun withdraw(
            merchantId: MerchantId,
            network: Network,
            destinationId: UUID,
            amountMinor: Long,
        ) = StepUpBinding(merchantId, StepUpAction.WITHDRAW, network, listOf(destinationId.toString(), "$amountMinor"))

        fun changePin(merchantId: MerchantId) = StepUpBinding(merchantId, StepUpAction.CHANGE_PIN, null, emptyList())

        fun paymentAccount(
            merchantId: MerchantId,
            username: String,
            channelId: String,
            accountId: String?,
        ) = StepUpBinding(
            merchantId,
            StepUpAction.PAYMENT_ACCOUNT,
            null,
            listOf(username.trim(), channelId.trim(), accountId?.trim().orEmpty()),
        )

        fun payoutAccount(merchantId: MerchantId, accountId: String) =
            StepUpBinding(merchantId, StepUpAction.PAYOUT_ACCOUNT, null, listOf(accountId.trim()))

        fun removePasskey(merchantId: MerchantId, passkeyId: UUID) =
            StepUpBinding(merchantId, StepUpAction.REMOVE_PASSKEY, null, listOf(passkeyId.toString()))
    }
}

/** A passkey confirmation sent with a request: the challenge it answers and the browser's credential (JSON). */
class PasskeyAssertion(val challengeId: String, val credentialJson: String) {
    override fun toString(): String = "PasskeyAssertion($challengeId, ***)"
}

/** A credential the browser may use, by id (base64url) and how it can be reached. */
data class CredentialRef(val id: String, val transports: List<String>)

/** What the browser's `navigator.credentials.get` needs. */
data class AssertionOptions(
    val challenge: String,
    val rpId: String,
    val allowCredentials: List<CredentialRef>,
    val userVerification: String,
    val timeoutMs: Long,
)

/** What the browser's `navigator.credentials.create` needs. */
data class CreationOptions(
    val challenge: String,
    val rpId: String,
    val rpName: String,
    val userId: String,
    val userName: String,
    val userDisplayName: String,
    val algorithms: List<Long>,
    val excludeCredentials: List<CredentialRef>,
    val residentKey: String,
    val userVerification: String,
    val attestation: String,
    val timeoutMs: Long,
)

/** A challenge handed out: its id (sent back with the answer) and the options for the browser. */
data class AssertionChallenge(val challengeId: String, val options: AssertionOptions)

data class RegistrationChallenge(val registrationId: String, val options: CreationOptions)

/** RM-P1's list. */
data class PasskeyBook(val passkeys: List<Passkey>, val required: Boolean, val max: Int)

/** What a passkey's removal is confirmed with: another passkey, or a fresh email code and the PIN. */
sealed interface RemovalProof {
    class Passkey(val assertion: PasskeyAssertion) : RemovalProof

    class CodeAndPin(val code: String, val pin: String) : RemovalProof {
        override fun toString(): String = "CodeAndPin(***)"
    }
}

/** What a security code confirms; each has its own code, so one can't be used for the other. */
enum class SecurityCodePurpose(val words: String) {
    ADD_PASSKEY("add a passkey"),
    REMOVE_PASSKEY("remove a passkey"),
}

/** A merchant's security state and email choices. */
data class MerchantSecurity(
    val merchantId: MerchantId,
    val orderEmails: Boolean = true,
    val dailySummary: Boolean = false,
    val lockedAt: Instant? = null,
    val lockedAlertId: UUID? = null,
    val lockedCancelled: Int = 0,
    val unlockedAt: Instant? = null,
    val unlockedBy: String? = null,
    val unlockReason: String? = null,
) {
    val locked: Boolean get() = lockedAt != null
}

/** RM2's `security` card. */
data class SecurityView(
    val passkeys: Int,
    val passkeyRequired: Boolean,
    val lockedAt: Instant?,
    val orderEmails: Boolean,
    val dailySummary: Boolean,
)

/** Staff's security settings (one row). */
data class SecuritySettings(
    val merchantPasskeyRequired: Boolean = false,
    val version: Long = 1,
    val updatedBy: String? = null,
    val reason: String? = null,
    val updatedAt: Instant? = null,
)

/** The email preferences a merchant changes; one left null stays as it is. */
data class NotificationChoices(val orderEmails: Boolean?, val dailySummary: Boolean?)

/** Where a sign-in came from, as far as the request already says (no new tracking). */
data class SignInContext(val device: String, val location: String) {
    companion object {
        val UNKNOWN = SignInContext("an unknown browser", "unknown")
    }
}

// ---- alerts ----

/** Every email a merchant gets from an event. [security] ones are always sent and carry the lock link. */
enum class AlertKind(val security: Boolean) {
    NEW_SIGN_IN(true),
    ADDRESS_ADDED(true),
    ADDRESS_REMOVED(true),
    WITHDRAWAL_REQUESTED(true),
    WITHDRAWAL_SENT(true),
    WITHDRAWAL_COMPLETED(true),
    WITHDRAWAL_FAILED(true),
    PIN_CHANGED(true),
    PAYMENT_ACCOUNT_CHANGED(true),
    PASSKEY_ADDED(true),
    PASSKEY_REMOVED(true),
    WITHDRAWALS_LOCKED(false),
    BUY_COMPLETED(false),
    SELL_COMPLETED(false),
    TOPUP_RESULT(false),
    DAILY_SUMMARY(false),
    ;

    /** An activity email the merchant can turn off (the lock's confirmation is neither: always sent, no link). */
    val activity: Boolean get() = !security && this != WITHDRAWALS_LOCKED
}

/**
 * What an alert says, in the merchant's words, already formatted (amounts with their unit, times in Nairobi time):
 * each kind fills its own fields. Kept with the alert, so the email says what was true when it happened.
 */
data class AlertFacts(
    val time: String,
    val label: String? = null,
    val address: String? = null,
    val network: String? = null,
    val usableFrom: String? = null,
    val amount: String? = null,
    val txUrl: String? = null,
    val reason: String? = null,
    val what: String? = null,
    val method: String? = null,
    val device: String? = null,
    val location: String? = null,
    val cancelled: String? = null,
    val order: String? = null,
    val received: String? = null,
    val paid: String? = null,
    val earned: String? = null,
    val wallet: String? = null,
    val result: String? = null,
    val day: String? = null,
    val orders: String? = null,
    val bought: String? = null,
    val sold: String? = null,
)

enum class AlertState { PENDING, SENT, SKIPPED, FAILED }

data class MerchantAlert(
    val alertId: UUID,
    val merchantId: MerchantId,
    val kind: AlertKind,
    val dedupeKey: String,
    val facts: AlertFacts,
    val state: AlertState,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val createdAt: Instant,
    val linkExpiresAt: Instant?,
    val linkUsedAt: Instant? = null,
    val failure: String? = null,
    val sentAt: Instant? = null,
)

/** What following a lock link did. */
data class LockOutcome(val merchantId: MerchantId, val alreadyLocked: Boolean, val cancelled: Int)

/** Staff's read of a merchant's security. */
data class StaffSecurity(
    val security: MerchantSecurity,
    val passkeys: List<Passkey>,
    val recentAlerts: List<MerchantAlert>,
)
