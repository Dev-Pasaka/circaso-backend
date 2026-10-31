package com.circaso.identity.domain.models

import com.circaso.identity.api.Principal
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import java.time.Instant
import java.util.UUID

enum class NextStep { VERIFY_PHONE, REVERIFY_PHONE, REGISTER_DEVICE, DONE }

data class DeviceInfo(
    val deviceId: DeviceId,
    val platform: String,
    val model: String,
    val osVersion: String?,
    val appVersion: String,
)

/** A signature over a nonce and timestamp (I2) or a refresh token and timestamp (I7), from the phone's key. */
data class DeviceProof(val timestamp: Instant, val signature: ByteArray)

data class SessionTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
    val scope: String,
)

data class SignInCommand(
    val provider: SocialProvider,
    val idToken: String,
    val nonce: String,
    val appleName: String?,
    val device: DeviceInfo,
    val proof: DeviceProof?,
)

data class SignInResult(
    val next: NextStep,
    val maskedPhone: String?,
    val session: SessionTokens,
    val agentSession: SessionTokens?,
)

data class StartPhoneCommand(val principal: Principal, val phone: PhoneNumber)

data class StartPhoneResult(
    val verificationId: String,
    val codeLength: Int,
    val expiresInSeconds: Long,
    val resendAfterSeconds: Long,
)

data class VerifyCodeCommand(val principal: Principal, val verificationId: String, val code: String)

data class VerifyCarrierCommand(val principal: Principal, val carrierToken: String)

data class PhoneVerifiedResult(val maskedPhone: String, val next: NextStep)

data class RegisterDeviceCommand(
    val principal: Principal,
    val deviceId: DeviceId,
    val publicKey: String,
    val keyAlgorithm: String,
    val pushToken: String?,
    val attestation: String?,
    val platform: String,
    val model: String,
    val appVersion: String,
)

data class ReplacedDevice(val model: String, val lastSeenAt: Instant)

data class RegisteredDevice(
    val account: Account,
    val session: SessionTokens,
    val agentSession: SessionTokens,
    val replacedDevice: ReplacedDevice?,
)

data class RefreshCommand(val refreshToken: String, val deviceId: DeviceId, val proof: DeviceProof)

data class RefreshedSession(val session: SessionTokens, val agentSession: SessionTokens)

data class LinkProviderCommand(
    val principal: Principal,
    val provider: SocialProvider,
    val idToken: String,
    val nonce: String,
)

data class SecurityOverview(
    val maskedPhone: String?,
    val providers: List<ProviderLink>,
    val device: Device?,
    val verification: Verification,
)

data class SecurityPage(val events: List<SecurityEvent>, val nextCursor: String?)

data class RecoveryCommand(
    val principal: Principal,
    val phone: PhoneNumber,
    val contactEmail: String,
    val note: String?,
)

data class ReleaseCommand(val principal: Principal, val verificationId: String, val confirmAbandon: Boolean)

/**
 * The account and security overview Asha reads (decision 2026-10-31): I11's facts, the account's status and age,
 * the phone the account is on now and the latest lines of the security log.
 */
data class SecuritySummary(
    val account: Account,
    val maskedPhone: String?,
    val providers: List<ProviderLink>,
    val device: Device?,
    val recent: List<SecurityEvent>,
)
