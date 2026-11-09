package com.circaso.messaging.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/** The only shape of a person messaging returns, as users gives it; never a phone number. */
@Serializable
data class ChatProfileDto(
    val userId: String,
    val displayName: String,
    val handle: String? = null,
    val avatarUrl: String? = null,
    val about: String? = null,
    /** True only for Circaso's own support account: the app shows the chat as verified support (2026-10-31). */
    val support: Boolean = false,
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
    /** A private chat with a space's owner (Spaces phase 3): the space it came from. Label it "Private · via …". */
    val viaSpaceId: String? = null,
    /**
     * The group's photo: an attachment id to fetch with `GET /attachments/{attachmentId}` (members only), whose key
     * and digest are in `sealedMeta`. Kept while it is the photo; null when the group has none.
     */
    val avatarMediaId: String? = null,
    /**
     * Set by `POST /chats/groups` and `POST /chats/{chatId}/members` (2026-11-09): the people who now have an open
     * invite. They aren't in `members` until they accept.
     */
    val invited: List<String>? = null,
)

@Serializable
data class ChatsResponse(val chats: List<ChatDto>)

@Serializable
data class OpenDirectChatRequest(
    val userId: String,
    /** Open the private chat with a space's owner instead (Spaces phase 3), separate from your other chat with them. */
    val viaSpaceId: String? = null,
)

/**
 * The group's metadata key sealed 1:1 (as a `SENDER_KEY` copy is) by the inviter's phone for one person invited, so
 * their invite can show the group's name and photo before they decide (2026-11-09). Base64, at most 8 KB decoded.
 */
@Serializable
data class InviteKeyDto(val userId: String, val sealedKey: String)

@Serializable
data class CreateGroupRequest(
    val sealedMeta: String,
    val memberIds: List<String> = emptyList(),
    val inviteKeys: List<InviteKeyDto> = emptyList(),
)

/**
 * Every field but `version` is optional; one left out stays as it is. `avatarMediaId` sets the group photo,
 * `"avatarMediaId": null` clears it and no `avatarMediaId` leaves it, so the field is an [AvatarField] that tells the
 * three apart (as ST5's `reaction`).
 */
@Serializable
data class UpdateGroupRequest(
    val sealedMeta: String? = null,
    val settings: ChatSettingsDto? = null,
    val version: Long,
    /**
     * The group photo: an `image` attachment of this group, completed (AT2), at most 2 MB of ciphertext. It is kept
     * until replaced or cleared, and the photo it replaces is deleted 24 hours later.
     */
    val avatarMediaId: AvatarField = AvatarField.Absent,
)

/** M8's `avatarMediaId` as sent: an attachment id, an explicit `null`, or not there at all. */
@Serializable(with = AvatarFieldSerializer::class)
sealed interface AvatarField {
    data object Absent : AvatarField

    data object Null : AvatarField

    data class Value(val attachmentId: String) : AvatarField
}

/** On the wire a string or null; an absent key never reaches it, so the property's default stands for that. */
@OptIn(ExperimentalSerializationApi::class)
object AvatarFieldSerializer : KSerializer<AvatarField> {
    override val descriptor: SerialDescriptor = String.serializer().nullable.descriptor

    override fun deserialize(decoder: Decoder): AvatarField =
        if (decoder.decodeNotNullMark()) {
            AvatarField.Value(decoder.decodeString())
        } else {
            decoder.decodeNull()
            AvatarField.Null
        }

    override fun serialize(encoder: Encoder, value: AvatarField) {
        when (value) {
            is AvatarField.Value -> encoder.encodeString(value.attachmentId)
            else -> encoder.encodeNull()
        }
    }
}

@Serializable
data class AddMembersRequest(val memberIds: List<String>, val inviteKeys: List<InviteKeyDto> = emptyList())

@Serializable
data class SetRoleRequest(
    /** `OWNER`, `ADMIN` or `MEMBER`. `OWNER` hands over ownership, and the old owner becomes an admin. */
    val role: String,
)

@Serializable
data class MemberRemovedResponse(val removed: Boolean)
