package com.circaso.messaging.presentation.mappers

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.AvatarChange
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.CreateGroupCommand
import com.circaso.messaging.domain.models.InviteKey
import com.circaso.messaging.domain.models.InviteView
import com.circaso.messaging.domain.models.MemberView
import com.circaso.messaging.domain.models.PendingInviteView
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.models.UpdateGroupCommand
import com.circaso.messaging.presentation.dto.AvatarField
import com.circaso.messaging.presentation.dto.ChatDto
import com.circaso.messaging.presentation.dto.ChatMemberDto
import com.circaso.messaging.presentation.dto.ChatProfileDto
import com.circaso.messaging.presentation.dto.ChatSettingsDto
import com.circaso.messaging.presentation.dto.CreateGroupRequest
import com.circaso.messaging.presentation.dto.InviteDto
import com.circaso.messaging.presentation.dto.InviteKeyDto
import com.circaso.messaging.presentation.dto.PendingInviteDto
import com.circaso.messaging.presentation.dto.UpdateGroupRequest
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.users.api.PublicProfile

// ---- request → command: shape checks answer `request.malformed`; the sealed name is the use case's to judge ----

private fun malformed(field: String, why: String = "is not valid") = Resource.error(SharedError.Malformed(field, why))

fun parseChatId(raw: String?): Resource<ChatId> =
    raw?.let(ChatId::parse)?.let { Resource.success(it) } ?: malformed("chatId")

/** Every id must be a UUID; repeats are folded into one. */
fun parseUserIds(raw: List<String>, field: String = "memberIds"): Resource<Set<UserId>> {
    val ids = raw.map { UserId.parse(it) ?: return malformed(field, "each must be a user id") }
    return Resource.success(ids.toSet())
}

fun parseRole(raw: String): Resource<Role> =
    Role.entries.firstOrNull { it.name == raw }?.let { Resource.success(it) }
        ?: malformed("role", "must be OWNER, ADMIN or MEMBER")

/** [requestKey] is the request's `Idempotency-Key`: the group's id is made from it, so a retry resumes the group. */
fun CreateGroupRequest.toCommand(creator: UserId, requestKey: String?): Resource<CreateGroupCommand> {
    val key = requestKey?.takeIf { it.isNotBlank() } ?: return malformed("Idempotency-Key", "required")
    val ids =
        when (val parsed = parseUserIds(memberIds)) {
            is Resource.Error -> return parsed
            is Resource.Success -> parsed.data
        }
    val keys =
        when (val parsed = parseInviteKeys(inviteKeys)) {
            is Resource.Error -> return parsed
            is Resource.Success -> parsed.data
        }
    val command =
        CreateGroupCommand(
            creator,
            sealedMeta,
            ids,
            key,
            keys,
        )
    return Resource.success(command)
}

/** Each sealed key names a user id; whether the key itself is fit is the use case's to judge. */
fun parseInviteKeys(raw: List<InviteKeyDto>): Resource<List<InviteKey>> {
    val keys =
        raw.map {
            val userId = UserId.parse(it.userId) ?: return malformed("inviteKeys", "each must name a user id")
            InviteKey(userId, it.sealedKey)
        }
    return Resource.success(keys)
}

fun UpdateGroupRequest.toCommand(caller: UserId, chatId: ChatId): Resource<UpdateGroupCommand> {
    val avatar =
        when (val parsed = avatarChange()) {
            is Resource.Error -> return parsed
            is Resource.Success -> parsed.data
        }
    val command =
        UpdateGroupCommand(
            caller,
            chatId,
            sealedMeta,
            settings?.membersCanAdd,
            version,
            avatar,
        )
    return Resource.success(command)
}

/** An id sets the photo, an explicit `null` clears it, no `avatarMediaId` leaves it. */
private fun UpdateGroupRequest.avatarChange(): Resource<AvatarChange?> =
    when (val field = avatarMediaId) {
        AvatarField.Absent -> {
            Resource.success(null)
        }

        AvatarField.Null -> {
            Resource.success(AvatarChange.Remove)
        }

        is AvatarField.Value -> {
            Uuid7.parse(field.attachmentId)?.let { Resource.success(AvatarChange.Set(it)) }
                ?: malformed("avatarMediaId")
        }
    }

// ---- model → response ----

fun ChatView.toDto() =
    ChatDto(
        chatId = chat.chatId.toString(),
        kind = chat.kind.name,
        sealedMeta = chat.sealedMeta,
        metaVersion = chat.metaVersion,
        members = members.map { it.toDto() },
        settings = ChatSettingsDto(chat.settings.membersCanAdd),
        lastMessageId = lastMessageId?.toString(),
        version = chat.version,
        created = created,
        notAdded = notAdded?.map { it.toString() },
        viaSpaceId = chat.viaSpaceId?.toString(),
        avatarMediaId = chat.avatarMediaId?.toString(),
        invited = invited?.map { it.toString() },
    )

private fun MemberView.toDto() =
    ChatMemberDto(
        user = profile.toDto(),
        role = role.name,
        joinedAt = joinedAt,
    )

fun PublicProfile.toDto() =
    ChatProfileDto(
        userId = userId.toString(),
        displayName = displayName,
        handle = handle,
        avatarUrl = avatarUrl,
        about = about,
        support = support,
    )

fun InviteView.toDto() =
    InviteDto(
        inviteId = invite.inviteId.toString(),
        chatId = chat.chatId.toString(),
        invitedBy = invitedBy.toDto(),
        sealedMeta = chat.sealedMeta,
        metaVersion = chat.metaVersion,
        avatarMediaId = chat.avatarMediaId?.toString(),
        memberCount = chat.memberCount,
        sealedKey = invite.sealedKey,
        createdAt = invite.createdAt,
        expiresAt = invite.expiresAt,
    )

fun PendingInviteView.toDto() =
    PendingInviteDto(
        inviteId = invite.inviteId.toString(),
        user = user.toDto(),
        invitedBy = invitedBy.toDto(),
        createdAt = invite.createdAt,
        expiresAt = invite.expiresAt,
    )
