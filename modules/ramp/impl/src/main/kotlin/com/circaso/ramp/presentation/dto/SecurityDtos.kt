package com.circaso.ramp.presentation.dto

import kotlinx.serialization.Serializable

// Passkeys, the step-up, alerts and the lock on the merchant portal (2026-11-24). The WebAuthn shapes are the browser's
// own JSON (`PublicKeyCredential.toJSON()` and the `publicKey` options), all binary values base64url.

/** The browser's answer: `credential.toJSON()` of a `PublicKeyCredential`, registration or assertion. */
@Serializable
data class PasskeyCredentialDto(
    val id: String = "",
    val rawId: String = "",
    val type: String = "public-key",
    val response: PasskeyResponseDto = PasskeyResponseDto(),
    val authenticatorAttachment: String? = null,
    val clientExtensionResults: PasskeyExtensionsDto = PasskeyExtensionsDto(),
) {
    override fun toString(): String = "PasskeyCredential(***)"
}

/** `response`: an attestation (`attestationObject`, `transports`) or an assertion (`authenticatorData`, …). */
@Serializable
data class PasskeyResponseDto(
    val clientDataJSON: String = "",
    val attestationObject: String? = null,
    val transports: List<String>? = null,
    val authenticatorData: String? = null,
    val signature: String? = null,
    val userHandle: String? = null,
)

/** `clientExtensionResults`: none are asked for; whatever comes is ignored. */
@Serializable
class PasskeyExtensionsDto

/** A passkey confirmation sent inside a sensitive request: the challenge it answers and the credential. */
@Serializable
data class PasskeyProofDto(
    val challengeId: String = "",
    val credential: PasskeyCredentialDto = PasskeyCredentialDto(),
) {
    override fun toString(): String = "PasskeyProof(***)"
}

@Serializable
data class CredentialRefDto(val id: String, val type: String = "public-key", val transports: List<String>)

/** `navigator.credentials.get({ publicKey })`. */
@Serializable
data class AssertionOptionsDto(
    val challenge: String,
    val rpId: String,
    val allowCredentials: List<CredentialRefDto>,
    val userVerification: String,
    val timeout: Long,
)

@Serializable
data class RelyingPartyDto(val id: String, val name: String)

@Serializable
data class WebAuthnUserDto(val id: String, val name: String, val displayName: String)

@Serializable
data class CredentialParamDto(val type: String = "public-key", val alg: Long)

@Serializable
data class AuthenticatorSelectionDto(
    val residentKey: String,
    val requireResidentKey: Boolean,
    val userVerification: String,
)

/** `navigator.credentials.create({ publicKey })`. */
@Serializable
data class CreationOptionsDto(
    val challenge: String,
    val rp: RelyingPartyDto,
    val user: WebAuthnUserDto,
    val pubKeyCredParams: List<CredentialParamDto>,
    val excludeCredentials: List<CredentialRefDto>,
    val authenticatorSelection: AuthenticatorSelectionDto,
    val attestation: String,
    val timeout: Long,
)

/** A challenge to answer with a passkey: send `challengeId` back with the credential. */
@Serializable
data class AssertionChallengeResponse(val challengeId: String, val publicKey: AssertionOptionsDto)

@Serializable
data class RegistrationChallengeResponse(val registrationId: String, val publicKey: CreationOptionsDto)

/** One passkey (never its key). */
@Serializable
data class PasskeyResponse(
    val passkeyId: String,
    val label: String,
    val createdAt: String,
    val lastUsedAt: String?,
    val backedUp: Boolean,
    val transports: List<String>,
)

@Serializable
data class PasskeysResponse(val passkeys: List<PasskeyResponse>, val required: Boolean, val max: Int)

/** RM-P2 request: what the code is for, `ADD_PASSKEY` or `REMOVE_PASSKEY`. */
@Serializable
data class SecurityCodeRequest(val purpose: String = "")

/** RM-P3 request: the new passkey's label (1 to 40 characters), the emailed code and the PIN. */
@Serializable
data class PasskeyOptionsRequest(val label: String = "", val code: String = "", val pin: String = "") {
    override fun toString(): String = "PasskeyOptionsRequest($label, ***)"
}

/** RM-P4 request. */
@Serializable
data class PasskeyRegistrationRequest(
    val registrationId: String = "",
    val credential: PasskeyCredentialDto = PasskeyCredentialDto(),
)

/** RM-P5 request: another passkey's confirmation, or the emailed code and the PIN. */
@Serializable
data class RemovePasskeyRequest(
    val passkey: PasskeyProofDto? = null,
    val code: String? = null,
    val pin: String? = null,
) {
    override fun toString(): String = "RemovePasskeyRequest(***)"
}

/**
 * RM-P6 request: the action and the exact fields of the request it will confirm (never the PIN or a password).
 * `ADD_ADDRESS`: `address`, `label`. `REMOVE_ADDRESS`: `destinationId`. `WITHDRAW`: `destinationId`, `amountMinor`.
 * `CHANGE_PIN`: nothing. `PAYMENT_ACCOUNT`: `username`, `channelId`, `accountId` (as RM5 will send them).
 * `PAYOUT_ACCOUNT`: `accountId`. `REMOVE_PASSKEY`: `passkeyId`. Address and withdrawal actions are on the request's
 * network (`X-Wallet-Network`).
 */
@Serializable
data class StepUpRequest(
    val action: String = "",
    val address: String? = null,
    val label: String? = null,
    val destinationId: String? = null,
    val amountMinor: String? = null,
    val username: String? = null,
    val channelId: String? = null,
    val accountId: String? = null,
    val passkeyId: String? = null,
)

/** MA5 request. */
@Serializable
data class PasskeySignInRequest(
    val challengeId: String = "",
    val credential: PasskeyCredentialDto = PasskeyCredentialDto(),
)

/** RM-N1 / RM-N2. `securityAlways` is always true: security emails can't be turned off. */
@Serializable
data class NotificationsResponse(
    val orderEmails: Boolean,
    val dailySummary: Boolean,
    val securityAlways: Boolean = true,
)

@Serializable
data class NotificationsRequest(val orderEmails: Boolean? = null, val dailySummary: Boolean? = null)

/** MA6 request: the token from the link's `#t=`. */
@Serializable
data class LockRequest(val token: String = "") {
    override fun toString(): String = "LockRequest(***)"
}

/** MA6: `alreadyUsed` when this link was followed before (nothing more happened). */
@Serializable
data class LockResponse(val locked: Boolean, val alreadyUsed: Boolean, val cancelledWithdrawals: Int)

/** RM2's `security`. */
@Serializable
data class MerchantSecurityDto(
    val passkeys: Int,
    val passkeyRequired: Boolean,
    val withdrawalsLocked: Boolean,
    val lockedAt: String?,
    val orderEmails: Boolean,
    val dailySummary: Boolean,
)
