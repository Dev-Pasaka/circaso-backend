package com.circaso.chama.domain.usecases

import com.circaso.chama.domain.models.ChamaView
import com.circaso.chama.domain.support.ChamaViews
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** `GET /chama/chats/{chatId}`: the goal, the totals, who put in what, and the requests still open. Members only. */
class GetChama(private val views: ChamaViews) {
    suspend operator fun invoke(userId: UserId, chatId: ChatId): Resource<ChamaView> = views.view(userId, chatId)
}
