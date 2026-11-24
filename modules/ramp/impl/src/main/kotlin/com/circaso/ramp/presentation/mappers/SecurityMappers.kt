package com.circaso.ramp.presentation.mappers

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.AssertionChallenge
import com.circaso.ramp.domain.models.AssertionOptions
import com.circaso.ramp.domain.models.CreationOptions
import com.circaso.ramp.domain.models.CredentialRef
import com.circaso.ramp.domain.models.LockOutcome
import com.circaso.ramp.domain.models.MerchantSecurity
import com.circaso.ramp.domain.models.NotificationChoices
import com.circaso.ramp.domain.models.Passkey
import com.circaso.ramp.domain.models.PasskeyAssertion
import com.circaso.ramp.domain.models.PasskeyBook
import com.circaso.ramp.domain.models.RegistrationChallenge
import com.circaso.ramp.domain.models.RemovalProof
import com.circaso.ramp.domain.models.SecurityCodePurpose
import com.circaso.ramp.domain.models.SecurityView
import com.circaso.ramp.domain.models.StepUpAction
import com.circaso.ramp.domain.models.StepUpBinding
import com.circaso.ramp.presentation.dto.AssertionChallengeResponse
import com.circaso.ramp.presentation.dto.AssertionOptionsDto
import com.circaso.ramp.presentation.dto.AuthenticatorSelectionDto
import com.circaso.ramp.presentation.dto.CreationOptionsDto
import com.circaso.ramp.presentation.dto.CredentialParamDto
import com.circaso.ramp.presentation.dto.CredentialRefDto
import com.circaso.ramp.presentation.dto.LockResponse
import com.circaso.ramp.presentation.dto.MerchantSecurityDto
import com.circaso.ramp.presentation.dto.NotificationsRequest
import com.circaso.ramp.presentation.dto.NotificationsResponse
import com.circaso.ramp.presentation.dto.PasskeyCredentialDto
import com.circaso.ramp.presentation.dto.PasskeyOptionsRequest
import com.circaso.ramp.presentation.dto.PasskeyProofDto
import com.circaso.ramp.presentation.dto.PasskeyResponse
import com.circaso.ramp.presentation.dto.PasskeySignInRequest
import com.circaso.ramp.presentation.dto.PasskeysResponse
import com.circaso.ramp.presentation.dto.RegistrationChallengeResponse
import com.circaso.ramp.presentation.dto.RelyingPartyDto
import com.circaso.ramp.presentation.dto.RemovePasskeyRequest
import com.circaso.ramp.presentation.dto.SecurityCodeRequest
import com.circaso.ramp.presentation.dto.StepUpRequest
import com.circaso.ramp.presentation.dto.WebAuthnUserDto
import com.circaso.shared.CircasoJson
import com.circaso.shared.Network
import com.circaso.shared.Resource
import kotlinx.serialization.json.Json

private const val MAX_LABEL = 40
private const val MAX_FIELD = 200
private const val MAX_CHALLENGE_ID = 100

/** The credential as the WebAuthn library reads it: the browser's JSON, without the fields it left out. */
private val CredentialJson = Json(CircasoJson) { explicitNulls = false }

fun PasskeyCredentialDto.toJson(): String = CredentialJson.encodeToString(PasskeyCredentialDto.serializer(), this)

fun PasskeyProofDto.toAssertion(): PasskeyAssertion = PasskeyAssertion(challengeId.trim(), credential.toJson())

fun PasskeySignInRequest.toAssertion(challengeId: String): PasskeyAssertion =
    PasskeyAssertion(challengeId, credential.toJson())

fun NotificationsRequest.toChoices(): NotificationChoices = NotificationChoices(orderEmails, dailySummary)

/** A challenge id the browser sent back, or `request.malformed`. */
fun challengeIdOf(raw: String, field: String): Resource<String> {
    val id = raw.trim()
    return if (id.isNotEmpty() && id.length <= MAX_CHALLENGE_ID) Resource.success(id) else malformed(field, "required")
}

fun SecurityCodeRequest.toPurpose(): Resource<SecurityCodePurpose> =
    SecurityCodePurpose.entries.firstOrNull { it.name == purpose.trim().uppercase() }?.let { Resource.success(it) }
        ?: malformed("purpose", "ADD_PASSKEY or REMOVE_PASSKEY")

/** RM-P3: the label, the 6-digit code and the PIN. */
fun PasskeyOptionsRequest.toParts(): Resource<Triple<String, String, String>> {
    val name = label.trim()
    if (name.isEmpty() || name.length > MAX_LABEL) return malformed("label", "1 to $MAX_LABEL characters")
    val digits = code.trim()
    if (digits.length != CODE_LENGTH || !digits.all { it in '0'..'9' }) return malformed("code", "must be 6 digits")
    return when (val p = pinOf(pin)) {
        is Resource.Error -> p
        is Resource.Success -> Resource.success(Triple(name, digits, p.data))
    }
}

/** RM-P5: another passkey, or the code and the PIN. */
fun RemovePasskeyRequest.toProof(): Resource<RemovalProof> {
    passkey?.let { return Resource.success(RemovalProof.Passkey(it.toAssertion())) }
    val digits = code?.trim().orEmpty()
    if (digits.length != CODE_LENGTH || !digits.all { it in '0'..'9' }) {
        return malformed("code", "a passkey, or the 6-digit code and the PIN")
    }
    return when (val p = pinOf(pin.orEmpty())) {
        is Resource.Error -> p
        is Resource.Success -> Resource.success(RemovalProof.CodeAndPin(digits, p.data))
    }
}

/** RM-P6: the binding of the request the passkey will confirm, built exactly as that route builds it. */
@Suppress("CyclomaticComplexMethod", "ReturnCount")
fun StepUpRequest.toBinding(merchantId: MerchantId, network: Network): Resource<StepUpBinding> {
    val wanted =
        StepUpAction.entries.firstOrNull { it.name == action.trim().uppercase() }
            ?: return malformed("action", "one of ${StepUpAction.entries.joinToString()}")

    fun text(value: String?, field: String): Resource<String> =
        value?.takeIf { it.isNotBlank() && it.length <= MAX_FIELD }?.let { Resource.success(it) }
            ?: malformed(field, "required")
    return when (wanted) {
        StepUpAction.ADD_ADDRESS -> {
            val a = text(address, "address") as? Resource.Success ?: return malformed("address", "required")
            val l = text(label, "label") as? Resource.Success ?: return malformed("label", "required")
            Resource.success(StepUpBinding.addAddress(merchantId, network, a.data.trim(), l.data.trim()))
        }

        StepUpAction.REMOVE_ADDRESS -> {
            idOf(destinationId, "destinationId").let { id ->
                when (id) {
                    is Resource.Error -> id
                    is Resource.Success -> Resource.success(StepUpBinding.removeAddress(merchantId, network, id.data))
                }
            }
        }

        StepUpAction.WITHDRAW -> {
            val id =
                idOf(destinationId, "destinationId") as? Resource.Success
                    ?: return malformed("destinationId", "not an id")
            val amount =
                amountMinor?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                    ?: return malformed("amountMinor", "USDC minor units as digits")
            Resource.success(StepUpBinding.withdraw(merchantId, network, id.data, amount))
        }

        StepUpAction.CHANGE_PIN -> {
            Resource.success(StepUpBinding.changePin(merchantId))
        }

        StepUpAction.PAYMENT_ACCOUNT -> {
            val u = text(username, "username") as? Resource.Success ?: return malformed("username", "required")
            val c = text(channelId, "channelId") as? Resource.Success ?: return malformed("channelId", "required")
            Resource.success(
                StepUpBinding.paymentAccount(merchantId, u.data, c.data, accountId?.trim()?.takeIf { it.isNotEmpty() }),
            )
        }

        StepUpAction.PAYOUT_ACCOUNT -> {
            val a = text(accountId, "accountId") as? Resource.Success ?: return malformed("accountId", "required")
            Resource.success(StepUpBinding.payoutAccount(merchantId, a.data))
        }

        StepUpAction.REMOVE_PASSKEY -> {
            when (val id = idOf(passkeyId, "passkeyId")) {
                is Resource.Error -> id
                is Resource.Success -> Resource.success(StepUpBinding.removePasskey(merchantId, id.data))
            }
        }
    }
}

private const val CODE_LENGTH = 6

private fun CredentialRef.toDto() = CredentialRefDto(id = id, transports = transports)

fun AssertionOptions.toDto() =
    AssertionOptionsDto(challenge, rpId, allowCredentials.map { it.toDto() }, userVerification, timeoutMs)

fun CreationOptions.toDto() =
    CreationOptionsDto(
        challenge = challenge,
        rp = RelyingPartyDto(rpId, rpName),
        user = WebAuthnUserDto(userId, userName, userDisplayName),
        pubKeyCredParams = algorithms.map { CredentialParamDto(alg = it) },
        excludeCredentials = excludeCredentials.map { it.toDto() },
        authenticatorSelection = AuthenticatorSelectionDto(residentKey, residentKey == "required", userVerification),
        attestation = attestation,
        timeout = timeoutMs,
    )

fun AssertionChallenge.toResponse() = AssertionChallengeResponse(challengeId, options.toDto())

fun RegistrationChallenge.toResponse() = RegistrationChallengeResponse(registrationId, options.toDto())

fun Passkey.toResponse() =
    PasskeyResponse(
        passkeyId = passkeyId.toString(),
        label = label,
        createdAt = createdAt.toString(),
        lastUsedAt = lastUsedAt?.toString(),
        backedUp = backedUp,
        transports = transports,
    )

fun PasskeyBook.toResponse() = PasskeysResponse(passkeys.map { it.toResponse() }, required, max)

fun MerchantSecurity.toNotifications() = NotificationsResponse(orderEmails, dailySummary)

fun LockOutcome.toResponse() =
    LockResponse(locked = true, alreadyUsed = alreadyLocked, cancelledWithdrawals = cancelled)

fun SecurityView.toDto() =
    MerchantSecurityDto(
        passkeys = passkeys,
        passkeyRequired = passkeyRequired,
        withdrawalsLocked = lockedAt != null,
        lockedAt = lockedAt?.toString(),
        orderEmails = orderEmails,
        dailySummary = dailySummary,
    )
