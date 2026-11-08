package com.circaso.chama.presentation.warmup

import com.circaso.chama.api.RequestId
import com.circaso.chama.domain.repositories.ContributionRepository
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.chama.domain.repositories.SettingsRepository
import com.circaso.infrastructure.api.WarmUp
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Uuid7
import com.circaso.wallet.api.TransferId

private const val PAGE = 20

/**
 * Chama's reads behind a group's money card and ledger, read-only, for a random group that has none of it: the goal,
 * the settings, the contributions and their totals, the open and paid payouts and what was paid out, and a payout
 * looked up by id and by transfer.
 */
class ChamaWarmUp(
    private val goals: GoalRepository,
    private val settings: SettingsRepository,
    private val contributions: ContributionRepository,
    private val requests: RequestRepository,
) : WarmUp {
    override val name = "chama"

    override suspend fun run() {
        val chatId = ChatId.new()
        goals.find(chatId)
        settings.find(chatId)
        contributions.list(chatId, PAGE, null)
        contributions.totals(chatId)
        requests.open(chatId)
        requests.paid(chatId, PAGE, null)
        requests.paidOutTotal(chatId)
        requests.find(RequestId(Uuid7.generate()))
        requests.byTransfer(TransferId(Uuid7.generate()))
    }
}
