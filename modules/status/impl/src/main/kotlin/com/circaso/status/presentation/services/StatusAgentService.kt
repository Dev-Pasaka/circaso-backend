package com.circaso.status.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.domain.models.OwnMoment
import com.circaso.status.domain.usecases.ListOwnMoments

/** What Asha's read-only agent route reads (decision 2026-11-05): the caller's own moments' counts, reads only. */
class StatusAgentService(private val listOwnMoments: ListOwnMoments) {
    suspend fun listOwnMoments(caller: UserId): Resource<List<OwnMoment>> = listOwnMoments.invoke(caller)
}
