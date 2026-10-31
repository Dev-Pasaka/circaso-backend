package com.circaso.chama.domain.usecases

import com.circaso.chama.domain.models.ChamaDigest
import com.circaso.chama.domain.support.ChamaNames
import com.circaso.chama.domain.support.ChamaViews
import com.circaso.messaging.api.ChatDirectory
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * Asha's list of the user's chamas (decision 2026-10-31): every group the user is in that has a goal, at most
 * [MAX] of them, each as the member sees it. Groups without a goal are not chamas and are left out.
 */
class ListMyChamas(private val chats: ChatDirectory, private val views: ChamaViews, private val names: ChamaNames) {
    suspend operator fun invoke(userId: UserId): Resource<List<ChamaDigest>> {
        val chamas =
            chats
                .groupsOf(userId)
                .mapNotNull { (views.view(userId, it) as? Resource.Success)?.data }
                .take(MAX)
        val named = names.of(chamas.flatMap { ChamaNames.people(it, emptyList(), withContributors = false) }.toSet())
        return Resource.success(chamas.map { ChamaDigest(it, named, emptyList(), isDetailed = false) })
    }

    private companion object {
        const val MAX = 20
    }
}
