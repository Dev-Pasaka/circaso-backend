package com.circaso.identity.presentation.mappers

import com.circaso.identity.api.Principal
import com.circaso.identity.api.SessionId
import com.circaso.identity.domain.models.DeviceInfo
import com.circaso.identity.domain.models.DeviceProof
import com.circaso.identity.domain.models.LinkProviderCommand
import com.circaso.identity.domain.models.PhoneVerifiedResult
import com.circaso.identity.domain.models.RecoveryCommand
import com.circaso.identity.domain.models.RefreshCommand
import com.circaso.identity.domain.models.RefreshedSession
import com.circaso.identity.domain.models.RegisterDeviceCommand
import com.circaso.identity.domain.models.RegisteredDevice
import com.circaso.identity.domain.models.ReleaseCommand
import com.circaso.identity.domain.models.SecurityOverview
import com.circaso.identity.domain.models.SecurityPage
import com.circaso.identity.domain.models.SessionTokens
import com.circaso.identity.domain.models.SignInCommand
import com.circaso.identity.domain.models.SignInResult
import com.circaso.identity.domain.models.StartPhoneCommand
import com.circaso.identity.domain.models.StartPhoneResult
import com.circaso.identity.domain.models.SupportRecovery
import com.circaso.identity.domain.models.VerifyCarrierCommand
import com.circaso.identity.domain.models.VerifyCodeCommand
import com.circaso.identity.domain.ports.PublicKey
import com.circaso.identity.presentation.dto.ActiveDeviceDto
import com.circaso.identity.presentation.dto.CarrierRequest
import com.circaso.identity.presentation.dto.DeviceDto
import com.circaso.identity.presentation.dto.DeviceProofDto
import com.circaso.identity.presentation.dto.JwkDto
import com.circaso.identity.presentation.dto.JwksResponse
import com.circaso.identity.presentation.dto.LinkProviderRequest
import com.circaso.identity.presentation.dto.PhoneVerifiedResponse
import com.circaso.identity.presentation.dto.ProviderDto
import com.circaso.identity.presentation.dto.RecoveryRequest
import com.circaso.identity.presentation.dto.RecoveryResponse
import com.circaso.identity.presentation.dto.RefreshRequest
import com.circaso.identity.presentation.dto.RefreshResponse
import com.circaso.identity.presentation.dto.RegisterDeviceRequest
import com.circaso.identity.presentation.dto.RegisterDeviceResponse
import com.circaso.identity.presentation.dto.ReleaseRequest
import com.circaso.identity.presentation.dto.ReplacedDeviceDto
import com.circaso.identity.presentation.dto.SecurityEventDto
import com.circaso.identity.presentation.dto.SecurityLogResponse
import com.circaso.identity.presentation.dto.SecurityOverviewResponse
import com.circaso.identity.presentation.dto.SessionDto
import com.circaso.identity.presentation.dto.SignInResponse
import com.circaso.identity.presentation.dto.SocialSignInRequest
import com.circaso.identity.presentation.dto.StartPhoneRequest
import com.circaso.identity.presentation.dto.StartPhoneResponse
import com.circaso.identity.presentation.dto.UserStateDto
import com.circaso.identity.presentation.dto.VerifyCodeRequest
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.Uuid7
import com.circaso.web.CallPrincipal
import java.time.Instant
import java.util.Base64
import java.util.UUID

// ---- request → command: shape checks live here and answer `request.malformed` ----

private fun malformed(field: String, why: String = "is not valid") =
    Resource.error(
        SharedError.Malformed(
            mapOf(field to why),
        ),
    )

fun CallPrincipal.toPrincipal() = Principal(userId, deviceId, SessionId(sessionId), scope)

private fun provider(raw: String) = SocialProvider.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }

private fun signature(raw: String): ByteArray? = runCatching { Base64.getDecoder().decode(raw) }.getOrNull()

private fun DeviceDto.toInfo(): DeviceInfo? =
    DeviceId.parse(deviceId)?.let { DeviceInfo(it, platform.lowercase(), model, osVersion, appVersion) }

private fun DeviceProofDto.toProof(): DeviceProof? = signature(signature)?.let { DeviceProof(timestamp, it) }

fun SocialSignInRequest.toCommand(): Resource<SignInCommand> {
    val provider = provider(provider) ?: return malformed("provider")
    val device = device.toInfo() ?: return malformed("device.deviceId")
    if (idToken.isBlank()) return malformed("idToken", "is required")
    if (nonce.isBlank()) return malformed("nonce", "is required")
    val proof = deviceProof?.let { it.toProof() ?: return malformed("deviceProof.signature") }
    val name = appleName?.let { listOfNotNull(it.given, it.family).joinToString(" ").takeIf(String::isNotBlank) }
    return Resource.success(SignInCommand(provider, idToken, nonce, name, device, proof))
}

fun StartPhoneRequest.toCommand(caller: CallPrincipal): Resource<StartPhoneCommand> {
    val number = PhoneNumber.parse(phone) ?: return malformed("phone", "must be an E.164 number")
    return Resource.success(StartPhoneCommand(caller.toPrincipal(), number))
}

fun VerifyCodeRequest.toCommand(caller: CallPrincipal): Resource<VerifyCodeCommand> {
    if (verificationId.isBlank() || !code.matches(Regex("[0-9]{4,8}"))) return malformed("code")
    return Resource.success(VerifyCodeCommand(caller.toPrincipal(), verificationId, code))
}

fun CarrierRequest.toCommand(caller: CallPrincipal): Resource<VerifyCarrierCommand> =
    if (carrierToken.isBlank()) {
        malformed(
            "carrierToken",
            "is required",
        )
    } else {
        Resource.success(VerifyCarrierCommand(caller.toPrincipal(), carrierToken))
    }

/** `X-Client-Version` (`android/1.4.0`) tells the platform and app version I6 does not carry in its body. */
fun RegisterDeviceRequest.toCommand(caller: CallPrincipal, clientVersion: String?): Resource<RegisterDeviceCommand> {
    val id = DeviceId.parse(deviceId) ?: return malformed("deviceId")
    if (publicKey.isBlank()) return malformed("publicKey", "is required")
    val (platform, version) =
        clientVersion?.split('/', limit = 2)?.takeIf { it.size == 2 }
            ?: listOf("unknown", "unknown")
    return Resource.success(
        RegisterDeviceCommand(
            caller.toPrincipal(),
            id,
            publicKey,
            keyAlgorithm,
            pushToken,
            attestation,
            platform.lowercase(),
            model ?: "Unknown",
            version,
        ),
    )
}

fun RefreshRequest.toCommand(): Resource<RefreshCommand> {
    val id = DeviceId.parse(deviceId) ?: return malformed("deviceId")
    val bytes = signature(signature) ?: return malformed("signature")
    return Resource.success(RefreshCommand(refreshToken, id, DeviceProof(timestamp, bytes)))
}

fun LinkProviderRequest.toCommand(caller: CallPrincipal): Resource<LinkProviderCommand> {
    val provider = provider(provider) ?: return malformed("provider")
    return Resource.success(LinkProviderCommand(caller.toPrincipal(), provider, idToken, nonce))
}

fun parseProvider(raw: String?): SocialProvider? = raw?.let(::provider)

fun RecoveryRequest.toCommand(caller: CallPrincipal): Resource<RecoveryCommand> {
    val number = PhoneNumber.parse(phone) ?: return malformed("phone", "must be an E.164 number")
    if (!contactEmail.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) return malformed("contactEmail")
    return Resource.success(RecoveryCommand(caller.toPrincipal(), number, contactEmail, note?.take(500)))
}

fun ReleaseRequest.toCommand(caller: CallPrincipal) =
    ReleaseCommand(
        caller.toPrincipal(),
        verificationId,
        confirmAbandon,
    )

fun parseUuid(raw: String?): UUID? = raw?.let(Uuid7::parse)

// ---- result → response ----

fun SessionTokens.toDto() = SessionDto(accessToken, refreshToken, expiresInSeconds, scope)

fun SignInResult.toResponse() = SignInResponse(next.name, maskedPhone, session.toDto(), agentSession?.toDto())

fun StartPhoneResult.toResponse() = StartPhoneResponse(verificationId, codeLength, expiresInSeconds, resendAfterSeconds)

fun PhoneVerifiedResult.toResponse() = PhoneVerifiedResponse(true, maskedPhone, next.name)

fun RegisteredDevice.toResponse() =
    RegisterDeviceResponse(
        UserStateDto(account.userId.toString(), account.status.name),
        session.toDto(),
        agentSession.toDto(),
        replacedDevice?.let { ReplacedDeviceDto(it.model, it.lastSeenAt) },
    )

fun RefreshedSession.toResponse() = RefreshResponse(session.toDto(), agentSession.toDto())

/** `am•••@gmail.com`: the first two characters of the name and the whole domain. */
fun maskEmail(email: String): String {
    val name = email.substringBefore('@')
    return name.take(2) + "•••@" + email.substringAfter('@', "")
}

fun SecurityOverview.toResponse() =
    SecurityOverviewResponse(
        maskedPhone,
        providers.map { ProviderDto(it.provider.name.lowercase(), it.email?.let(::maskEmail), it.linkedAt) },
        device?.let { ActiveDeviceDto(it.model, it.registeredAt) },
        verification.name,
    )

fun SecurityPage.toResponse() =
    SecurityLogResponse(
        events.map {
            SecurityEventDto(
                it.eventId.toString(),
                it.type.name,
                it.deviceModel,
                it.detail,
                it.eventId?.let(Uuid7::timestampOf) ?: Instant.EPOCH,
            )
        },
        nextCursor,
    )

fun SupportRecovery.toResponse() = RecoveryResponse(requestId.toString(), status.name, noticeSentAt, relinkAfter)

fun List<PublicKey>.toResponse() = JwksResponse(map { JwkDto(kid = it.keyId, x = it.x) })
