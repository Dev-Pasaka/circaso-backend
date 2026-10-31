package com.circaso.chama.presentation.services

import com.circaso.chama.domain.models.ChamaDigest
import com.circaso.chama.domain.usecases.GetChamaDigest
import com.circaso.chama.domain.usecases.ListMyChamas
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** What Asha's read-only agent routes read (decision 2026-10-31): the caller's own chamas, reads only. */
class ChamaAgentService(private val listMyChamas: ListMyChamas, private val getChamaDigest: GetChamaDigest) {
    suspend fun listChamas(userId: UserId): Resource<List<ChamaDigest>> = listMyChamas(userId)

    suspend fun getChama(userId: UserId, chatId: ChatId): Resource<ChamaDigest> = getChamaDigest(userId, chatId)
}
