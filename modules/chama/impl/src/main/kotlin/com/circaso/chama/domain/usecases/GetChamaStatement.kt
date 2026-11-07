package com.circaso.chama.domain.usecases

import com.circaso.chama.domain.models.ChamaDigest
import com.circaso.chama.domain.support.ChamaNames
import com.circaso.chama.domain.support.ChamaViews
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * `Chamas.statement`, for a statement share link (decision 2026-11-06): the member's view of the target and the
 * latest ledger lines with the names they mention. Members only, as the app's routes; at most [MAX] lines.
 */
class GetChamaStatement(private val views: ChamaViews, private val names: ChamaNames) {
    suspend operator fun invoke(
        userId: UserId,
        chatId: ChatId,
        lines: Int,
    ): Resource<ChamaDigest> {
        val view =
            when (val r = views.view(userId, chatId)) {
                is Resource.Error -> return r
                is Resource.Success -> r.data
            }
        val recent = views.ledger(chatId, lines.coerceIn(1, MAX), null)
        val named = names.of(ChamaNames.people(view, recent, withContributors = false))
        return Resource.success(ChamaDigest(view, named, recent, isDetailed = true))
    }

    private companion object {
        const val MAX = 200
    }
}
