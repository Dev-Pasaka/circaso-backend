package com.circaso.users.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class PublicProfileDto(
    val userId: String,
    val displayName: String,
    val handle: String? = null,
    val avatarUrl: String? = null,
    val about: String? = null,
)

@Serializable
data class PrivacyDto(val avatar: String, val about: String, val handleLookup: Boolean)

@Serializable
data class MyProfileResponse(
    val userId: String,
    val displayName: String,
    val about: String? = null,
    val handle: String? = null,
    @Serializable(with = InstantSerializer::class) val handleChangeableAt: Instant? = null,
    val avatarUrl: String? = null,
    val privacy: PrivacyDto,
    val version: Long,
)

@Serializable
data class PrivacyChangeDto(val avatar: String? = null, val about: String? = null, val handleLookup: Boolean? = null)

/** Only the fields being changed, plus the `version` read last. An `about` of "" clears it. */
@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val about: String? = null,
    val privacy: PrivacyChangeDto? = null,
    val version: Long,
)

@Serializable
data class UploadUrlRequest(val contentType: String, val sizeBytes: Long)

@Serializable
data class UploadUrlResponse(val uploadUrl: String, val objectKey: String, val expiresInSeconds: Long)

@Serializable
data class SetAvatarRequest(val objectKey: String)

@Serializable
data class HandleAvailabilityResponse(
    val handle: String,
    val available: Boolean,
    val status: String,
    val suggestion: String? = null,
)

@Serializable
data class ClaimHandleRequest(val handle: String)

@Serializable
data class SyncRequest(
    val syncId: String,
    val batch: Int = 1,
    val final: Boolean = false,
    val phones: List<String>,
)

@Serializable
data class SyncMatchDto(val phone: String, val user: PublicProfileDto)

@Serializable
data class SyncResponse(val matches: List<SyncMatchDto>)

@Serializable
data class ContactDto(
    val user: PublicProfileDto,
    val source: String,
    @Serializable(with = InstantSerializer::class) val addedAt: Instant,
)

@Serializable
data class ContactsResponse(val contacts: List<ContactDto>, val nextCursor: String? = null)

@Serializable
data class ConnectRequest(val via: String, val value: String)

@Serializable
data class ConnectResponse(val user: PublicProfileDto, val created: Boolean)

@Serializable
data class InviteResponse(
    val token: String,
    val url: String,
    val qrPayload: String,
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
)

@Serializable
data class BlocksResponse(val blocks: List<PublicProfileDto>)

@Serializable
data class ReachResponse(val reachable: Boolean)

@Serializable
data class RemovedResponse(val removed: Boolean)
