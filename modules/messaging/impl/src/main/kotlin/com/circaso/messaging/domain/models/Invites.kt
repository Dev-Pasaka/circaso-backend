package com.circaso.messaging.domain.models

import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import com.circaso.users.api.PublicProfile
import java.time.Instant
import java.util.UUID

/** Where an invite stands. Only a `PENDING` one can be answered or cancelled; the others are kept for retries. */
enum class InviteState { PENDING, ACCEPTED, DECLINED, CANCELLED }

/**
 * An invite to a group (decision 2026-11-09): [userId] joins [chatId] only by accepting it. [sealedKey] is the group's
 * metadata key sealed 1:1 by the inviter's phone for this person, so their phone can show the group's name and photo
 * before they decide; the server never reads it, and it can be null (an older app, or a phone that couldn't seal).
 */
data class Invite(
    val inviteId: UUID,
    val chatId: ChatId,
    val userId: UserId,
    val invitedBy: UserId,
    val state: InviteState,
    val sealedKey: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    fun isOpenAt(now: Instant): Boolean = state == InviteState.PENDING && expiresAt.isAfter(now)
}

/** The inviter's sealed metadata key for one person they invite, as the phone sent it. */
data class InviteKey(val userId: UserId, val sealedKey: String)

/**
 * An invite as the invitee sees it: the group's sealed name and photo (the chat as it now is), how many are in it and
 * who invited them. Never the group's members or messages.
 */
data class InviteView(val invite: Invite, val chat: Chat, val invitedBy: PublicProfile)

/** An open invite as group info lists it: the person invited and who invited them. */
data class PendingInviteView(val invite: Invite, val user: PublicProfile, val invitedBy: PublicProfile)

/** M5 and M9's people sent an invite: the chat as it is, and who was invited (or still is, from an earlier add). */
data class InvitesSent(val chat: Chat, val invites: List<Invite>, val alreadyInvited: Set<UserId>)
