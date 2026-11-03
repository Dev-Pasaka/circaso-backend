package com.circaso.status.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.api.MomentId
import com.circaso.status.domain.models.MomentPage
import com.circaso.status.domain.models.PostMomentCommand
import com.circaso.status.domain.models.PostedMoment
import com.circaso.status.domain.models.ViewOutcome
import com.circaso.status.domain.models.ViewRecord
import com.circaso.status.domain.usecases.DeleteMoment
import com.circaso.status.domain.usecases.ListMoments
import com.circaso.status.domain.usecases.ListViews
import com.circaso.status.domain.usecases.PostMoment
import com.circaso.status.domain.usecases.ViewMoment

/** Moments themselves, ST3 to ST7. */
class MomentService(
    private val postMoment: PostMoment,
    private val listMoments: ListMoments,
    private val viewMoment: ViewMoment,
    private val listViews: ListViews,
    private val deleteMoment: DeleteMoment,
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
        reacted: Boolean?,
    ): Resource<ViewOutcome> = viewMoment(caller, momentId, reacted)

    suspend fun views(caller: UserId, momentId: MomentId): Resource<List<ViewRecord>> = listViews(caller, momentId)

    suspend fun delete(caller: UserId, momentId: MomentId): Resource<Unit> = deleteMoment(caller, momentId)
}
