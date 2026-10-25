package com.circaso.messaging.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/** The only shape of a person messaging returns, as users gives it; never a phone number. */
@Serializable
data class ChatProfileDto(
    val userId: String,
    val displayName: String,
    val handle: String? = null,
    val avatarUrl: String? = null,
    val about: String? = null,
)

@Serializable
data class ChatMemberDto(
    val user: ChatProfileDto,
    /** `OWNER`, `ADMIN` or `MEMBER`. */
    val role: String,
    @Serializable(with = InstantSerializer::class) val joinedAt: Instant,
)

@Serializable
data class ChatSettingsDto(val membersCanAdd: Boolean = false)

/**
 * A chat. `sealedMeta` is the group's name and avatar sealed under the group metadata key (null for a direct chat);
 * `metaVersion` goes up each time it is re-sealed; `version` is what `PATCH` sends back. `created` is set by
 * `POST /chats/direct` only, `notAdded` by `POST /chats/groups` and `POST /chats/{chatId}/members`.
 */
@Serializable
data class ChatDto(
    val chatId: String,
    /** `DIRECT` or `GROUP`. */
    val kind: String,
    val sealedMeta: String? = null,
    val metaVersion: Int,
    val members: List<ChatMemberDto>,
    val settings: ChatSettingsDto,
    val lastMessageId: String? = null,
    val version: Long,
    val created: Boolean? = null,
    val notAdded: List<String>? = null,
)

@Serializable
data class ChatsResponse(val chats: List<ChatDto>)

@Serializable
data class OpenDirectChatRequest(val userId: String)

@Serializable
data class CreateGroupRequest(val sealedMeta: String, val memberIds: List<String> = emptyList())

/** Every field but `version` is optional; one left out stays as it is. */
@Serializable
data class UpdateGroupRequest(val sealedMeta: String? = null, val settings: ChatSettingsDto? = null, val version: Long)

@Serializable
data class AddMembersRequest(val memberIds: List<String>)

@Serializable
data class SetRoleRequest(
    /** `OWNER`, `ADMIN` or `MEMBER`. `OWNER` hands over ownership, and the old owner becomes an admin. */
    val role: String,
)

@Serializable
data class MemberRemovedResponse(val removed: Boolean)
