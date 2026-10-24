package com.circaso.identity.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class NonceResponse(val nonce: String, val expiresInSeconds: Long)

@Serializable
data class AppleNameDto(val given: String? = null, val family: String? = null)

@Serializable
data class DeviceDto(
    val deviceId: String,
    val platform: String,
    val model: String,
    val osVersion: String? = null,
    val appVersion: String,
)

@Serializable
data class DeviceProofDto(
    @Serializable(with = InstantSerializer::class) val timestamp: Instant,
    val signature: String,
)

@Serializable
data class SocialSignInRequest(
    val provider: String,
    val idToken: String,
    val nonce: String,
    val appleName: AppleNameDto? = null,
    val device: DeviceDto,
    val deviceProof: DeviceProofDto? = null,
)

@Serializable
data class SessionDto(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresInSeconds: Long,
    val scope: String,
)

@Serializable
data class SignInResponse(
    val next: String,
    val maskedPhone: String? = null,
    val session: SessionDto,
    val agentSession: SessionDto? = null,
)

@Serializable
data class StartPhoneRequest(val phone: String)

@Serializable
data class StartPhoneResponse(
    val verificationId: String,
    val codeLength: Int,
    val expiresInSeconds: Long,
    val resendAfterSeconds: Long,
)

@Serializable
data class VerifyCodeRequest(val verificationId: String, val code: String)

@Serializable
data class CarrierRequest(val carrierToken: String)

@Serializable
data class PhoneVerifiedResponse(val phoneVerified: Boolean, val maskedPhone: String, val next: String)

@Serializable
data class RegisterDeviceRequest(
    val deviceId: String,
    val publicKey: String,
    val keyAlgorithm: String,
    val pushToken: String? = null,
    val attestation: String? = null,
    /** Not in the original contract; the phone model for the security list. `platform` and `appVersion` come from `X-Client-Version`. */
    val model: String? = null,
)

@Serializable
data class UserStateDto(val userId: String, val status: String)

@Serializable
data class ReplacedDeviceDto(
    val model: String,
    @Serializable(with = InstantSerializer::class) val lastSeenAt: Instant,
)

@Serializable
data class RegisterDeviceResponse(
    val user: UserStateDto,
    val session: SessionDto,
    val agentSession: SessionDto,
    val replacedDevice: ReplacedDeviceDto? = null,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
    val deviceId: String,
    @Serializable(with = InstantSerializer::class) val timestamp: Instant,
    val signature: String,
)

@Serializable
data class RefreshResponse(val session: SessionDto, val agentSession: SessionDto)

@Serializable
data class PushTokenRequest(val pushToken: String)

@Serializable
data class ProviderDto(
    val provider: String,
    val maskedEmail: String? = null,
    @Serializable(with = InstantSerializer::class) val linkedAt: Instant,
)

@Serializable
data class ActiveDeviceDto(
    val model: String,
    @Serializable(with = InstantSerializer::class) val activeSince: Instant,
)

@Serializable
data class SecurityOverviewResponse(
    val maskedPhone: String? = null,
    val providers: List<ProviderDto>,
    val device: ActiveDeviceDto? = null,
    val verification: String,
)

@Serializable
data class LinkProviderRequest(val provider: String, val idToken: String, val nonce: String)

@Serializable
data class SecurityEventDto(
    val eventId: String,
    val type: String,
    val deviceModel: String? = null,
    val detail: String? = null,
    @Serializable(with = InstantSerializer::class) val at: Instant,
)

@Serializable
data class SecurityLogResponse(val events: List<SecurityEventDto>, val nextCursor: String? = null)

@Serializable
data class RecoveryRequest(val phone: String, val contactEmail: String, val note: String? = null)

@Serializable
data class RecoveryResponse(
    val requestId: String,
    val status: String,
    @Serializable(with = InstantSerializer::class) val noticeSentAt: Instant? = null,
    @Serializable(with = InstantSerializer::class) val relinkAfter: Instant? = null,
)

@Serializable
data class ReleaseRequest(val verificationId: String, val confirmAbandon: Boolean)

@Serializable
data class ReleaseResponse(
    @Serializable(with = InstantSerializer::class) val releaseAt: Instant,
)

@Serializable
data class JwkDto(
    val kty: String = "OKP",
    val crv: String = "Ed25519",
    val use: String = "sig",
    val alg: String = "EdDSA",
    val kid: String,
    val x: String,
)

@Serializable
data class JwksResponse(val keys: List<JwkDto>)
