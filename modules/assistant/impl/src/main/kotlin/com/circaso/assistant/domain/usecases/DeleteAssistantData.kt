package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.ports.MascotScenes
import com.circaso.assistant.domain.ports.PendingTurns
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.ports.Transactor
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.identity.api.AccountDeleted
import com.circaso.shared.Resource
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `identity.AccountDeleted`: the chat and the entitlement go with the account, with the audit record that says so, in
 * one transaction. After the commit, the user's Redis keys go too: the turns parked for the phone (which hold the
 * conversation), the day's counters and the mascot's scenes. Audit records (what was done, never what was said) and usage totals remain.
 */
class DeleteAssistantData(
    private val transactor: Transactor,
    private val store: AssistantStore,
    private val pending: PendingTurns,
    private val quota: QuotaCounters,
    private val scenes: MascotScenes,
    private val clock: Clock,
) {
    suspend operator fun invoke(event: AccountDeleted): Resource<Unit> {
        transactor.run("assistant.erase") {
            store.erase(event.userId)
            store.commit(
                AssistantChanges(audit = listOf(AuditEntry(event.userId, "ACCOUNT_DELETED", "chat removed", null))),
            )
        }
        pending.forget(event.userId)
        quota.forget(event.userId, countedDays())
        scenes.forget(event.userId)
        return Resource.success(Unit)
    }

    /** The days whose counters may still exist: they live two days past their last write. */
    private fun countedDays(): List<String> {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        return (0L..COUNTER_DAYS).map { today.minusDays(it).toString() }
    }

    private companion object {
        const val COUNTER_DAYS = 2L
    }
}
