package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.ChatUpdatedFrame
import com.circaso.messaging.api.InviteReceivedFrame
import com.circaso.messaging.api.InviteRemovedFrame
import com.circaso.messaging.api.InvitesChangedFrame
import com.circaso.messaging.api.MemberAddedFrame
import com.circaso.messaging.api.MemberRemovedFrame
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.Invite
import com.circaso.messaging.domain.ports.RealtimeFrames
import com.circaso.shared.UserId

/**
 * The membership frames (Messaging → Socket frames). They go to the members and the people themselves, and tell
 * phones to send or rotate sender keys. `member.added` lists everyone one action added in `userIds`, so a member gets
 * one frame per action (decision 2026-10-26); `member.removed` is one frame per person. `chat.updated` covers the
 * sealed name, the group photo, settings and roles. The invite frames (2026-11-09) tell the person invited, and the
 * group's admins and inviter, that an invite came or went.
 */
class ChatNotifier(private val frames: RealtimeFrames) {
    /** [joined]: the one person added accepted an invite from [by] (phones say "X joined", and nobody is pushed). */
    suspend fun membersAdded(
        recipients: Collection<UserId>,
        chatId: ChatId,
        added: Collection<UserId>,
        by: UserId,
        joined: Boolean = false,
    ) = frames.send(recipients, MemberAddedFrame(chatId, added.sortedBy { it.toString() }, by, joined))

    suspend fun memberRemoved(
        recipients: Collection<UserId>,
        chatId: ChatId,
        removed: UserId,
        by: UserId,
    ) = frames.send(recipients, MemberRemovedFrame(chatId, removed, by))

    suspend fun chatUpdated(recipients: Collection<UserId>, chat: Chat) =
        frames.send(recipients, ChatUpdatedFrame(chat.chatId, chat.metaVersion, chat.version, chat.avatarMediaId))

    /** One frame per person invited, to them only: it is theirs to answer. */
    suspend fun inviteReceived(invite: Invite, byName: String) =
        frames.send(
            listOf(invite.userId),
            InviteReceivedFrame(invite.inviteId, invite.chatId, invite.invitedBy, byName),
        )

    suspend fun inviteRemoved(invite: Invite) =
        frames.send(listOf(invite.userId), InviteRemovedFrame(invite.inviteId, invite.chatId))

    suspend fun invitesChanged(recipients: Collection<UserId>, chatId: ChatId) =
        frames.send(recipients, InvitesChangedFrame(chatId))
}
