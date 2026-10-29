package com.circaso.messaging.presentation.services

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.CreateGroupCommand
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.models.UpdateGroupCommand
import com.circaso.messaging.domain.usecases.AddMembers
import com.circaso.messaging.domain.usecases.CreateGroup
import com.circaso.messaging.domain.usecases.GetAdmins
import com.circaso.messaging.domain.usecases.GetChat
import com.circaso.messaging.domain.usecases.GetMembers
import com.circaso.messaging.domain.usecases.IsGroup
import com.circaso.messaging.domain.usecases.LeaveGroup
import com.circaso.messaging.domain.usecases.ListChats
import com.circaso.messaging.domain.usecases.OpenDirectChat
import com.circaso.messaging.domain.usecases.RemoveMember
import com.circaso.messaging.domain.usecases.SetRole
import com.circaso.messaging.domain.usecases.UpdateGroup
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** Chats and membership, M4 to M11, and the member lookup behind `ChatDirectory`. */
class ChatService(
    private val openDirectChat: OpenDirectChat,
    private val createGroup: CreateGroup,
    private val listChats: ListChats,
    private val getChat: GetChat,
    private val updateGroup: UpdateGroup,
    private val addMembers: AddMembers,
    private val removeMember: RemoveMember,
    private val leaveGroup: LeaveGroup,
    private val setRole: SetRole,
    private val getMembers: GetMembers,
    private val getAdmins: GetAdmins,
    private val isGroup: IsGroup,
) {
    suspend fun openDirect(caller: UserId, other: UserId): Resource<ChatView> = openDirectChat(caller, other)

    suspend fun createGroup(command: CreateGroupCommand): Resource<ChatView> = createGroup.invoke(command)

    suspend fun list(caller: UserId): Resource<List<ChatView>> = listChats(caller)

    suspend fun get(caller: UserId, chatId: ChatId): Resource<ChatView> = getChat(caller, chatId)

    suspend fun update(command: UpdateGroupCommand): Resource<ChatView> = updateGroup(command)

    suspend fun addMembers(
        caller: UserId,
        chatId: ChatId,
        memberIds: Set<UserId>,
    ): Resource<ChatView> =
        addMembers.invoke(
            caller,
            chatId,
            memberIds,
        )

    /** M10 is one route for two things: removing someone else, or leaving when the target is the caller. */
    suspend fun removeMember(
        caller: UserId,
        chatId: ChatId,
        target: UserId,
    ): Resource<Unit> {
        if (target == caller) return leaveGroup(caller, chatId)
        return removeMember.invoke(
            caller,
            chatId,
            target,
        )
    }

    suspend fun setRole(
        caller: UserId,
        chatId: ChatId,
        target: UserId,
        role: Role,
    ): Resource<ChatView> =
        setRole.invoke(
            caller,
            chatId,
            target,
            role,
        )

    suspend fun members(chatId: ChatId): Resource<Set<UserId>> = getMembers(chatId)

    suspend fun admins(chatId: ChatId): Resource<Set<UserId>> = getAdmins(chatId)

    suspend fun isGroup(chatId: ChatId): Resource<Boolean> = isGroup.invoke(chatId)
}
