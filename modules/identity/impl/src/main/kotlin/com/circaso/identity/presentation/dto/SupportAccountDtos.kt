package com.circaso.identity.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/** OPS1 `POST /ops/support-accounts`. */
@Serializable
data class CreateSupportAccountRequest(val displayName: String, val handle: String)

/** A support account; [displayName] and [handle] are null until users has made its profile. */
@Serializable
data class SupportAccountDto(
    val userId: String,
    val displayName: String? = null,
    val handle: String? = null,
    /** Always `SUPPORT`; there so a dashboard can show it next to people later without guessing. */
    val kind: String,
    val status: String,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
)

/** OPS2 `GET /ops/support-accounts`. */
@Serializable
data class SupportAccountsResponse(val accounts: List<SupportAccountDto>, val nextCursor: String? = null)
