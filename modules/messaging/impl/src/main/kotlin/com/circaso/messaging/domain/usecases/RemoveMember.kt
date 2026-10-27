package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.support.MemberRemoval
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * M10 `DELETE /chats/{chatId}/members/{userId}` by an admin, for someone else. Admins remove admins and members;
 * nobody removes the owner. A direct chat has no members to remove (`messaging.not_a_group`). The rights are decided
 * inside the removal's transaction, with the chat's row locked. The admin's phone then makes a new group metadata
 * key and re-seals the name (Messaging → Group metadata key).
 */
class RemoveMember(private val removal: MemberRemoval) {
    suspend operator fun invoke(
        caller: UserId,
        chatId: ChatId,
        target: UserId,
    ): Resource<Unit> = removal.remove(chatId, target, by = caller)
}
