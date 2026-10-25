package com.circaso.messaging.domain.models

import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import java.time.Instant
import java.util.UUID

enum class ChatKind { DIRECT, GROUP }

/** OWNER and ADMIN are both admins; only the owner can hand over ownership. A direct chat's two people are MEMBERs. */
enum class Role {
    OWNER,
    ADMIN,
    MEMBER,
    ;

    val isAdmin: Boolean get() = this != MEMBER
}

/** Admins add members by default (decision 2026-10-25); a group can let every member add. */
data class ChatSettings(val membersCanAdd: Boolean = false)

/**
 * One chat. [sealedMeta] is the group's name and avatar sealed on the phone under the group metadata key; the
 * server never reads it, and it is null for a direct chat. [metaVersion] goes up each time it is re-sealed.
 * [version] goes up on every change to the chat, membership included.
 */
data class Chat(
    val chatId: ChatId,
    val kind: ChatKind,
    val sealedMeta: String?,
    val metaVersion: Int,
    val settings: ChatSettings,
    val memberCount: Int,
    val createdBy: UserId,
    val createdAt: Instant,
    val version: Long,
    /** The encrypted avatar file (chat media, MS5). Kept here so every rewrite of the chat carries it over. */
    val avatarMediaId: UUID? = null,
)

data class Member(
    val chatId: ChatId,
    val userId: UserId,
    val role: Role,
    val joinedAt: Instant,
)

/** One line of a user's chat list. [lastMessageId] and [readUpTo] are null until messages exist (MS4). */
data class UserChat(
    val userId: UserId,
    val chatId: ChatId,
    val kind: ChatKind,
    val lastMessageId: UUID?,
    val readUpTo: UUID?,
    val joinedAt: Instant,
)

data class MemberView(val profile: PublicProfile, val role: Role, val joinedAt: Instant)

/**
 * A chat as M4 to M11 answer it. [created] is set by M4 only; [notAdded] by M5 and M9, listing the people who
 * couldn't be reached, without a reason.
 */
data class ChatView(
    val chat: Chat,
    val members: List<MemberView>,
    val lastMessageId: UUID?,
    val created: Boolean? = null,
    val notAdded: List<UserId>? = null,
)

/** M5: the sealed name and avatar, and the people to add besides the creator. */
data class CreateGroupCommand(val creator: UserId, val sealedMeta: String, val memberIds: Set<UserId>)

/** M8: each field left null stays as it is. [version] is the chat version the phone last read. */
data class UpdateGroupCommand(
    val caller: UserId,
    val chatId: ChatId,
    val sealedMeta: String?,
    val membersCanAdd: Boolean?,
    val version: Long,
)
