package com.circaso.messaging.domain.models

import com.circaso.messaging.api.ChatId
import com.circaso.shared.EventEnvelope
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
 * [version] goes up on every change to the chat, membership and roles included. [ownerId] is the group's owner,
 * null for a direct chat: it lives on the chat so every change of owner goes through [version] (decision
 * 2026-10-26), and the OWNER member row only mirrors it.
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
    val ownerId: UserId? = null,
    /** The encrypted avatar file (chat media, MS5). Kept here so every rewrite of the chat carries it over. */
    val avatarMediaId: UUID? = null,
    /** A direct chat's two people, in any order; null for a group. Unique per pair: the claim behind M4. */
    val directPair: Pair<UserId, UserId>? = null,
    /**
     * A private chat with a space's owner (Spaces phase 3): the space it came from. Unique per pair and space, and
     * separate from the pair's own direct chat.
     */
    val viaSpaceId: UUID? = null,
) {
    /**
     * [member]'s role as the chat sees it: the owner is whoever [ownerId] names, and a member row still saying OWNER
     * after a handover (its rewrite lags the chat) counts as ADMIN.
     */
    fun roleOf(member: Member): Role =
        when {
            member.userId == ownerId -> Role.OWNER
            member.role == Role.OWNER -> Role.ADMIN
            else -> member.role
        }
}

data class Member(
    val chatId: ChatId,
    val userId: UserId,
    val role: Role,
    val joinedAt: Instant,
)

/** A user's place in one chat: the chat's kind and their read marker (null until they read anything). */
data class UserChat(
    val userId: UserId,
    val chatId: ChatId,
    val kind: ChatKind,
    val readUpTo: UUID?,
    val joinedAt: Instant,
)

/**
 * One line of a user's chat list (M6): the chat and the newest `MESSAGE` the server still holds a record of, null
 * after 30 quiet days.
 */
data class ChatLine(val chat: Chat, val lastMessageId: UUID?)

/** M6 in one read: the user's chat lines and every member of each of those chats. */
data class ChatListing(val lines: List<ChatLine>, val members: Map<ChatId, List<Member>>)

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

/**
 * M5: the sealed name and avatar, and the people to add besides the creator. [requestKey] is the request's
 * `Idempotency-Key`, from which the chat id is made, so a retry resumes the same group.
 */
data class CreateGroupCommand(
    val creator: UserId,
    val sealedMeta: String,
    val memberIds: Set<UserId>,
    val requestKey: String,
)

/** M8: each field left null stays as it is. [version] is the chat version the phone last read. */
data class UpdateGroupCommand(
    val caller: UserId,
    val chatId: ChatId,
    val sealedMeta: String?,
    val membersCanAdd: Boolean?,
    val version: Long,
)

/** M11 decided inside the version check: the chat to write (a new owner, a new version) and the rows that follow. */
data class RoleChange(val chat: Chat, val rows: List<Member>)

/** M9 decided under the chat's lock: the chat as it now is, who joined, and the events written for them. */
data class MembersAdded(val chat: Chat, val joined: Set<UserId>, val events: List<EventEnvelope>)
