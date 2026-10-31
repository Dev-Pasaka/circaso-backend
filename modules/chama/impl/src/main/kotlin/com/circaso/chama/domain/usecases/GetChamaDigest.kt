package com.circaso.chama.domain.usecases

import com.circaso.chama.domain.models.ChamaDigest
import com.circaso.chama.domain.support.ChamaNames
import com.circaso.chama.domain.support.ChamaViews
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * One of the user's chamas for Asha (decision 2026-10-31): the member's view, who put in what by name, the open
 * requests and the latest [RECENT] ledger lines. Members only, as the app's route.
 */
class GetChamaDigest(private val views: ChamaViews, private val names: ChamaNames) {
    suspend operator fun invoke(userId: UserId, chatId: ChatId): Resource<ChamaDigest> {
        val view =
            when (val r = views.view(userId, chatId)) {
                is Resource.Error -> return r
                is Resource.Success -> r.data
            }
        val recent = views.ledger(chatId, RECENT, null)
        val named = names.of(ChamaNames.people(view, recent, withContributors = true))
        return Resource.success(ChamaDigest(view, named, recent, isDetailed = true))
    }

    private companion object {
        const val RECENT = 10
    }
}
