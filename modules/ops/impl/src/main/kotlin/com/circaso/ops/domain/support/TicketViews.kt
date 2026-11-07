package com.circaso.ops.domain.support

import com.circaso.ops.domain.models.TicketLine
import com.circaso.ops.domain.models.TicketView
import com.circaso.support.api.TicketDetail
import com.circaso.support.api.TicketSummary
import java.util.UUID

/** Puts names on tickets for staff: the user's display name, and the email of the assignee and of each staff writer. */
class TicketViews(private val users: UserViews, private val roster: StaffRoster) {
    suspend fun rows(summaries: List<TicketSummary>): List<TicketLine> {
        val names = users.names(summaries.map { it.userId }.toSet())
        val staff = roster.byIds(summaries.mapNotNull { it.assigneeId }.toSet())
        return summaries.map { TicketLine(it, names[it.userId], it.assigneeId?.let { id -> staff[id]?.email }) }
    }

    suspend fun view(detail: TicketDetail): TicketView {
        val row = rows(listOf(detail.summary)).single()
        val writers: Set<UUID> = detail.messages.mapNotNull { it.staffId }.toSet()
        val emails = roster.byIds(writers).mapValues { it.value.email }
        return TicketView(detail, row, emails)
    }
}
