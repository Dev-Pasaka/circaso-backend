package com.circaso.identity.api

import kotlinx.serialization.Serializable

// What identity's read-only agent route (`/v1/agent/identity/security`) answers. It lives here, not in identity's
// presentation, because the assistant reads it over HTTP with the agent token, as it reads users' (decision
// 2026-10-27; this route decision 2026-10-31). Nothing in it can sign in or act: no ids that are credentials, no
// keys, no push tokens, the phone and emails masked. Times are ISO-8601 UTC.

/** A linked Google or Apple sign-in: `provider` is `google` or `apple`; the email masked (`am•••@gmail.com`). */
@Serializable
data class AgentSignInDto(val provider: String, val maskedEmail: String? = null, val linkedAt: String)

/** The phone the account is on now: what it is, since when, and when it was last seen. */
@Serializable
data class AgentDeviceDto(
    val platform: String,
    val model: String,
    val appVersion: String,
    val registeredAt: String,
    val lastSeenAt: String,
)

/** One security log line: `type` as I13 (`SIGN_IN`, `NEW_PHONE`, `PROVIDER_LINKED`, …). */
@Serializable
data class AgentSecurityEventDto(val type: String, val at: String, val deviceModel: String? = null)

/**
 * The user's account and security overview for the assistant (I11 and the latest 10 lines of I13). `status` is the
 * account status (`ACTIVE`, …); `verification` is `NONE`, `PENDING` or `VERIFIED`.
 */
@Serializable
data class AgentSecurityResponse(
    val maskedPhone: String? = null,
    val status: String,
    val verification: String,
    val memberSince: String,
    val signIns: List<AgentSignInDto>,
    val device: AgentDeviceDto? = null,
    val recentEvents: List<AgentSecurityEventDto>,
)
