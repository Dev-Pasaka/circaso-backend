package com.circaso.spaces.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.models.InviteResult
import com.circaso.spaces.domain.models.InvitesView
import com.circaso.spaces.domain.models.MeView
import com.circaso.spaces.domain.models.ReceivedInviteView
import com.circaso.spaces.domain.models.RequestState
import com.circaso.spaces.domain.models.SpaceView
import com.circaso.spaces.domain.usecases.AcceptInvite
import com.circaso.spaces.domain.usecases.AnswerRequest
import com.circaso.spaces.domain.usecases.CancelInvite
import com.circaso.spaces.domain.usecases.CancelRequest
import com.circaso.spaces.domain.usecases.DeclineInvite
import com.circaso.spaces.domain.usecases.InvitePeople
import com.circaso.spaces.domain.usecases.ListInvitesAndRequests
import com.circaso.spaces.domain.usecases.ListMyInvites
import com.circaso.spaces.domain.usecases.RequestToJoin
import java.util.UUID

/** Requests and invites: SP14-SP22. */
class InviteService(
    private val requestToJoin: RequestToJoin,
    private val cancelRequest: CancelRequest,
    private val listInvitesAndRequests: ListInvitesAndRequests,
    private val answerRequest: AnswerRequest,
    private val invitePeople: InvitePeople,
    private val cancelInvite: CancelInvite,
    private val listMyInvites: ListMyInvites,
    private val acceptInvite: AcceptInvite,
    private val declineInvite: DeclineInvite,
) {
    suspend fun request(caller: UserId, spaceId: SpaceId): Resource<MeView> = requestToJoin(caller, spaceId)

    suspend fun cancelRequest(caller: UserId, spaceId: SpaceId): Resource<MeView> = cancelRequest.invoke(caller, spaceId)

    suspend fun list(caller: UserId, spaceId: SpaceId): Resource<InvitesView> = listInvitesAndRequests(caller, spaceId)

    suspend fun answer(
        caller: UserId,
        spaceId: SpaceId,
        userId: UserId,
        accept: Boolean,
    ): Resource<RequestState> = answerRequest(caller, spaceId, userId, accept)

    suspend fun invite(caller: UserId, spaceId: SpaceId, userIds: List<UserId>): Resource<List<InviteResult>> =
        invitePeople(caller, spaceId, userIds)

    suspend fun cancelInvite(caller: UserId, spaceId: SpaceId, inviteId: UUID): Resource<Unit> =
        cancelInvite.invoke(caller, spaceId, inviteId)

    suspend fun mine(caller: UserId): Resource<List<ReceivedInviteView>> = listMyInvites(caller)

    suspend fun accept(caller: UserId, inviteId: UUID): Resource<SpaceView> = acceptInvite(caller, inviteId)

    suspend fun decline(caller: UserId, inviteId: UUID): Resource<Unit> = declineInvite(caller, inviteId)
}
