package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.ChatUpdatedFrame
import com.circaso.messaging.api.MemberAddedFrame
import com.circaso.messaging.api.MemberRemovedFrame
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.ports.RealtimeFrames
import com.circaso.shared.UserId

/**
 * The membership frames (Messaging → Socket frames). They go to the members and the people themselves, and tell
 * phones to send or rotate sender keys. `member.added` lists everyone one action added in `userIds`, so a member gets
 * one frame per action (decision 2026-10-26); `member.removed` is one frame per person. `chat.updated` covers the
 * sealed name, the group photo, settings and roles.
 */
class ChatNotifier(private val frames: RealtimeFrames) {
    suspend fun membersAdded(
        recipients: Collection<UserId>,
        chatId: ChatId,
        added: Collection<UserId>,
        by: UserId,
    ) = frames.send(recipients, MemberAddedFrame(chatId, added.sortedBy { it.toString() }, by))

    suspend fun memberRemoved(
        recipients: Collection<UserId>,
        chatId: ChatId,
        removed: UserId,
        by: UserId,
    ) = frames.send(recipients, MemberRemovedFrame(chatId, removed, by))

    suspend fun chatUpdated(recipients: Collection<UserId>, chat: Chat) =
        frames.send(recipients, ChatUpdatedFrame(chat.chatId, chat.metaVersion, chat.version, chat.avatarMediaId))
}
