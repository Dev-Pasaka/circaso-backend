package com.circaso.ops

import com.circaso.assistant.api.AshaUsageDay
import com.circaso.assistant.api.AshaUsageUser
import com.circaso.assistant.api.AssistantReports
import com.circaso.assistant.api.UsageRates
import com.circaso.integrations.api.AiBill
import com.circaso.integrations.api.AiBilling
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.Resource
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/** The assistant's usage contract over fixed answers; records the ranges and rates it was asked with. */
class FakeAssistantReports : AssistantReports {
    var days: List<AshaUsageDay> = emptyList()
    var active: Long = 0
    var top: List<AshaUsageUser> = emptyList()
    val asked = CopyOnWriteArrayList<Pair<LocalDate, LocalDate>>()
    val rates = CopyOnWriteArrayList<UsageRates>()
    val limits = CopyOnWriteArrayList<Int>()

    override suspend fun days(from: LocalDate, until: LocalDate): List<AshaUsageDay> {
        asked += from to until
        return days.filter { !it.day.isBefore(from) && it.day.isBefore(until) }
    }

    override suspend fun activeUsers(from: LocalDate, until: LocalDate) = active

    override suspend fun topUsers(
        from: LocalDate,
        until: LocalDate,
        rates: UsageRates,
        limit: Int,
    ): List<AshaUsageUser> {
        this.rates += rates
        limits += limit
        return top.take(limit)
    }
}

/** Anthropic's billing: off until [bill] or [failing] says otherwise. */
class FakeAiBilling : AiBilling {
    var bill: AiBill? = null
    var failing = false
    var asked = 0

    override val isEnabled: Boolean get() = bill != null || failing

    override suspend fun billed(from: LocalDate, until: LocalDate): Resource<AiBill> {
        asked++
        if (failing) return Resource.error(IntegrationError.Unavailable("Anthropic billing"))
        return Resource.success(bill!!)
    }
}
