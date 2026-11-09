package com.circaso.messaging.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * One of my open invites to a group (2026-11-09). `sealedMeta`, `metaVersion` and `avatarMediaId` are the group's as
 * they now are; `sealedKey` is the group's metadata key sealed 1:1 for me by the inviter's phone (open it as a
 * `SENDER_KEY` copy from `invitedBy`), null when their phone sent none. `memberCount` counts members only.
 */
@Serializable
data class InviteDto(
    val inviteId: String,
    val chatId: String,
    val invitedBy: ChatProfileDto,
    val sealedMeta: String? = null,
    val metaVersion: Int,
    val avatarMediaId: String? = null,
    val memberCount: Int,
    val sealedKey: String? = null,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
)

@Serializable
data class InvitesResponse(val invites: List<InviteDto>)

/** An open invite as group info lists it, under "Invited". */
@Serializable
data class PendingInviteDto(
    val inviteId: String,
    val user: ChatProfileDto,
    val invitedBy: ChatProfileDto,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
)

@Serializable
data class ChatInvitesResponse(val invites: List<PendingInviteDto>)

@Serializable
data class InviteDeclinedResponse(val declined: Boolean)

@Serializable
data class InviteCancelledResponse(val cancelled: Boolean)
