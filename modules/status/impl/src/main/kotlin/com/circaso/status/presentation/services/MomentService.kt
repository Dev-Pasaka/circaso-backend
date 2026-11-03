package com.circaso.status.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.api.MomentId
import com.circaso.status.domain.models.MomentKeyCopy
import com.circaso.status.domain.models.MomentPage
import com.circaso.status.domain.models.PostMomentCommand
import com.circaso.status.domain.models.PostedMoment
import com.circaso.status.domain.models.ReactionChange
import com.circaso.status.domain.models.ViewOutcome
import com.circaso.status.domain.models.ViewRecord
import com.circaso.status.domain.usecases.DeleteMoment
import com.circaso.status.domain.usecases.ListMoments
import com.circaso.status.domain.usecases.ListViews
import com.circaso.status.domain.usecases.PostMoment
import com.circaso.status.domain.usecases.RequestMomentKeys
import com.circaso.status.domain.usecases.ShareMomentKeyCopies
import com.circaso.status.domain.usecases.ViewMoment

/** Moments themselves, ST3 to ST7, and the key request that lets a new phone open them. */
class MomentService(
    private val postMoment: PostMoment,
    private val listMoments: ListMoments,
    private val viewMoment: ViewMoment,
    private val listViews: ListViews,
    private val deleteMoment: DeleteMoment,
    private val requestMomentKeys: RequestMomentKeys,
    private val shareMomentKeyCopies: ShareMomentKeyCopies,
) {
    suspend fun post(command: PostMomentCommand): Resource<PostedMoment> = postMoment(command)

    suspend fun list(
        caller: UserId,
        after: MomentId?,
        limit: Int,
    ): Resource<MomentPage> = listMoments(caller, after, limit)

    suspend fun view(
        caller: UserId,
        momentId: MomentId,
        reaction: ReactionChange,
    ): Resource<ViewOutcome> = viewMoment(caller, momentId, reaction)

    suspend fun views(caller: UserId, momentId: MomentId): Resource<List<ViewRecord>> = listViews(caller, momentId)

    suspend fun delete(caller: UserId, momentId: MomentId): Resource<Unit> = deleteMoment(caller, momentId)

    suspend fun requestKeys(caller: UserId, posters: List<UserId>): Resource<List<UserId>> =
        requestMomentKeys(caller, posters)

    suspend fun shareKeyCopies(caller: UserId, copies: List<MomentKeyCopy>): Resource<Int> =
        shareMomentKeyCopies(caller, copies)
}
