package com.circaso.messaging.presentation.services

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupLinkPage
import com.circaso.messaging.api.GroupLinkPhoto
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.GroupLink
import com.circaso.messaging.domain.models.LinkLookup
import com.circaso.messaging.domain.models.PublishLinkCommand
import com.circaso.messaging.domain.usecases.GetGroupLink
import com.circaso.messaging.domain.usecases.JoinByGroupLink
import com.circaso.messaging.domain.usecases.LookUpGroupLink
import com.circaso.messaging.domain.usecases.PublishGroupLink
import com.circaso.messaging.domain.usecases.ReadGroupLinkPage
import com.circaso.messaging.domain.usecases.ReadGroupLinkPhoto
import com.circaso.messaging.domain.usecases.TurnOffGroupLink
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** Group join links, M28 to M32 (decision 2026-11-14), and their public page for share. */
class GroupLinkService(
    private val getGroupLink: GetGroupLink,
    private val publishGroupLink: PublishGroupLink,
    private val turnOffGroupLink: TurnOffGroupLink,
    private val lookUpGroupLink: LookUpGroupLink,
    private val joinByGroupLink: JoinByGroupLink,
    private val readGroupLinkPage: ReadGroupLinkPage,
    private val readGroupLinkPhoto: ReadGroupLinkPhoto,
) {
    suspend fun get(caller: UserId, chatId: ChatId): Resource<GroupLink?> = getGroupLink(caller, chatId)

    suspend fun publish(command: PublishLinkCommand): Resource<GroupLink> = publishGroupLink(command)

    suspend fun turnOff(caller: UserId, chatId: ChatId): Resource<Unit> = turnOffGroupLink(caller, chatId)

    suspend fun lookUp(caller: UserId, token: GroupLinkToken): Resource<LinkLookup> = lookUpGroupLink(caller, token)

    suspend fun join(caller: UserId, token: GroupLinkToken): Resource<ChatView> = joinByGroupLink(caller, token)

    suspend fun page(token: GroupLinkToken): Resource<GroupLinkPage?> = readGroupLinkPage(token)

    suspend fun photo(token: GroupLinkToken): Resource<GroupLinkPhoto?> = readGroupLinkPhoto(token)
}
