package com.circaso.chama.data.repositories

import com.circaso.chama.api.RequestId
import com.circaso.chama.data.tables.ApprovalsTable
import com.circaso.chama.data.tables.ContributionsTable
import com.circaso.chama.data.tables.GoalsTable
import com.circaso.chama.data.tables.GroupSettingsTable
import com.circaso.chama.data.tables.RequestsTable
import com.circaso.chama.domain.models.Approval
import com.circaso.chama.domain.models.ApprovalPolicy
import com.circaso.chama.domain.models.Contribution
import com.circaso.chama.domain.models.Decision
import com.circaso.chama.domain.models.Goal
import com.circaso.chama.domain.models.GroupSettings
import com.circaso.chama.domain.models.Payout
import com.circaso.chama.domain.models.RequestState
import com.circaso.chama.domain.ports.ChamaOutbox
import com.circaso.chama.domain.repositories.ContributionRepository
import com.circaso.chama.domain.repositories.GoalRepository
import com.circaso.chama.domain.repositories.RequestRepository
import com.circaso.chama.domain.repositories.SettingsRepository
import com.circaso.infrastructure.api.Database
import com.circaso.messaging.api.ChatId
import com.circaso.shared.EventEnvelope
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.upsert
import java.time.Instant

private const val USDC = "USDC"

class GoalRepositoryImpl(private val database: Database) : GoalRepository {
    override suspend fun find(chatId: ChatId): Goal? =
        database.tx("goals.find") {
            GoalsTable
                .selectAll()
                .where { GoalsTable.chatId eq chatId.value }
                .map { it.toGoal() }
                .singleOrNull()
        }

    override suspend fun save(goal: Goal) {
        database.tx("goals.save") {
            GoalsTable.upsert {
                it[chatId] = goal.chatId.value
                it[name] = goal.name
                it[targetMinor] = goal.targetMinor
                it[asset] = USDC
                it[deadline] = goal.deadline
                it[treasurerId] = goal.treasurerId?.value
                it[createdBy] = goal.createdBy.value
                it[createdAt] = goal.createdAt
                it[updatedAt] = goal.updatedAt
                it[version] = goal.version
            }
        }
    }

    override suspend fun delete(chatId: ChatId): Boolean =
        database.tx("goals.delete") { GoalsTable.deleteWhere { GoalsTable.chatId eq chatId.value } > 0 }

    private fun ResultRow.toGoal() =
        Goal(
            chatId = ChatId(this[GoalsTable.chatId]),
            name = this[GoalsTable.name],
            targetMinor = this[GoalsTable.targetMinor],
            deadline = this[GoalsTable.deadline],
            treasurerId = this[GoalsTable.treasurerId]?.let(::UserId),
            createdBy = UserId(this[GoalsTable.createdBy]),
            createdAt = this[GoalsTable.createdAt],
            updatedAt = this[GoalsTable.updatedAt],
            version = this[GoalsTable.version],
        )
}

class ContributionRepositoryImpl(private val database: Database) : ContributionRepository {
    override suspend fun insert(contribution: Contribution): Boolean =
        database.tx("contributions.insert") {
            ContributionsTable
                .insertIgnore {
                    it[transferId] = contribution.transferId.value
                    it[chatId] = contribution.chatId.value
                    it[userId] = contribution.userId.value
                    it[amountMinor] = contribution.amountMinor
                    it[asset] = USDC
                    it[at] = contribution.at
                    it[legacy] = contribution.legacy
                }.insertedCount > 0
        }

    override suspend fun list(
        chatId: ChatId,
        limit: Int,
        before: Instant?,
    ): List<Contribution> =
        database.tx("contributions.list") {
            ContributionsTable
                .selectAll()
                .where {
                    (ContributionsTable.chatId eq chatId.value).let { c ->
                        if (before !=
                            null
                        ) {
                            c and (ContributionsTable.at less before)
                        } else {
                            c
                        }
                    }
                }.orderBy(ContributionsTable.at to SortOrder.DESC)
                .limit(limit)
                .map { it.toContribution() }
                .toList()
        }

    override suspend fun totals(chatId: ChatId, since: Instant?): Map<UserId, Long> =
        database.tx("contributions.totals") {
            ContributionsTable
                .selectAll()
                .where {
                    val counted = (ContributionsTable.chatId eq chatId.value) and (ContributionsTable.legacy eq false)
                    if (since != null) counted and (ContributionsTable.at greaterEq since) else counted
                }.map { UserId(it[ContributionsTable.userId]) to it[ContributionsTable.amountMinor] }
                .toList()
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, amounts) -> amounts.sum() }
        }

    private fun ResultRow.toContribution() =
        Contribution(
            transferId = TransferId(this[ContributionsTable.transferId]),
            chatId = ChatId(this[ContributionsTable.chatId]),
            userId = UserId(this[ContributionsTable.userId]),
            amountMinor = this[ContributionsTable.amountMinor],
            at = this[ContributionsTable.at],
            legacy = this[ContributionsTable.legacy],
        )
}

class SettingsRepositoryImpl(private val database: Database) : SettingsRepository {
    override suspend fun find(chatId: ChatId): GroupSettings =
        database.tx("group-settings.find") {
            GroupSettingsTable
                .selectAll()
                .where { GroupSettingsTable.chatId eq chatId.value }
                .map {
                    GroupSettings(
                        chatId,
                        ApprovalPolicy.valueOf(it[GroupSettingsTable.approvalPolicy]),
                        UserId(it[GroupSettingsTable.updatedBy]),
                        it[GroupSettingsTable.updatedAt],
                    )
                }.singleOrNull()
        } ?: GroupSettings.default(chatId)

    override suspend fun save(settings: GroupSettings) {
        val by = checkNotNull(settings.updatedBy) { "settings are saved by someone" }
        val at = checkNotNull(settings.updatedAt) { "settings are saved at a time" }
        database.tx("group-settings.save") {
            GroupSettingsTable.upsert {
                it[chatId] = settings.chatId.value
                it[approvalPolicy] = settings.approvalPolicy.name
                it[updatedBy] = by.value
                it[updatedAt] = at
            }
        }
    }
}

class RequestRepositoryImpl(private val database: Database) : RequestRepository {
    override suspend fun insert(request: Payout) {
        database.tx("requests.insert") {
            RequestsTable.insertIgnore {
                it[requestId] = request.requestId.value
                it[chatId] = request.chatId.value
                it[requestedBy] = request.requestedBy.value
                it[payeeId] = request.payeeId?.value
                it[payeeAddress] = request.payeeAddress
                it[payeeName] = request.payeeName
                it[amountMinor] = request.amountMinor
                it[asset] = USDC
                it[note] = request.note
                it[state] = request.state.name
                it[approvalsNeeded] = request.approvalsNeeded
                it[transferId] = request.transferId?.value
                it[expiresAt] = request.expiresAt
                it[createdAt] = request.createdAt
                it[updatedAt] = request.updatedAt
            }
            request.approvals.forEach { approval ->
                ApprovalsTable.insertIgnore {
                    it[requestId] = request.requestId.value
                    it[userId] = approval.userId.value
                    it[decision] = approval.decision.name
                    it[at] = approval.at
                }
            }
        }
    }

    override suspend fun find(requestId: RequestId): Payout? =
        database.tx("requests.find") {
            RequestsTable
                .selectAll()
                .where {
                    RequestsTable.requestId eq requestId.value
                }.toList()
                .let { withApprovals(it) }
                .singleOrNull()
        }

    override suspend fun byTransfer(transferId: TransferId): Payout? =
        database.tx("requests.by-transfer") {
            RequestsTable
                .selectAll()
                .where {
                    RequestsTable.transferId eq transferId.value
                }.toList()
                .let { withApprovals(it) }
                .singleOrNull()
        }

    override suspend fun open(chatId: ChatId): List<Payout> =
        database.tx("requests.open") {
            RequestsTable
                .selectAll()
                .where {
                    (RequestsTable.chatId eq chatId.value) and
                        (RequestsTable.state inList listOf(RequestState.PENDING.name, RequestState.APPROVED.name))
                }.orderBy(RequestsTable.createdAt to SortOrder.DESC)
                .toList()
                .let { withApprovals(it) }
                .toList()
        }

    override suspend fun paid(
        chatId: ChatId,
        limit: Int,
        before: Instant?,
    ): List<Payout> =
        database.tx("requests.paid") {
            RequestsTable
                .selectAll()
                .where {
                    ((RequestsTable.chatId eq chatId.value) and (RequestsTable.state eq RequestState.PAID.name))
                        .let { c -> if (before != null) c and (RequestsTable.updatedAt less before) else c }
                }.orderBy(RequestsTable.updatedAt to SortOrder.DESC)
                .limit(limit)
                .toList()
                .let { withApprovals(it) }
                .toList()
        }

    override suspend fun addApproval(requestId: RequestId, approval: Approval): Boolean =
        database.tx("approvals.insert") {
            ApprovalsTable
                .insertIgnore {
                    it[this.requestId] = requestId.value
                    it[userId] = approval.userId.value
                    it[decision] = approval.decision.name
                    it[at] = approval.at
                }.insertedCount > 0
        }

    override suspend fun move(
        requestId: RequestId,
        to: RequestState,
        from: Set<RequestState>,
        transferId: TransferId?,
        at: Instant,
    ): Payout? =
        database.tx("requests.move") {
            val moved =
                RequestsTable.update({
                    (RequestsTable.requestId eq requestId.value) and
                        (RequestsTable.state inList from.map { it.name })
                }) {
                    it[state] = to.name
                    it[updatedAt] = at
                    if (transferId != null) it[this.transferId] = transferId.value
                }
            if (moved == 0) {
                null
            } else {
                RequestsTable
                    .selectAll()
                    .where {
                        RequestsTable.requestId eq requestId.value
                    }.toList()
                    .let { withApprovals(it) }
                    .singleOrNull()
            }
        }

    override suspend fun expire(now: Instant): Int =
        database.tx("requests.expire") {
            RequestsTable.update({
                (RequestsTable.state eq RequestState.PENDING.name) and
                    (RequestsTable.expiresAt less now)
            }) {
                it[state] = RequestState.EXPIRED.name
                it[updatedAt] = now
            }
        }

    override suspend fun paidOutTotal(chatId: ChatId): Long =
        database.tx("requests.paid-out") {
            RequestsTable
                .selectAll()
                .where { (RequestsTable.chatId eq chatId.value) and (RequestsTable.state eq RequestState.PAID.name) }
                .map { it[RequestsTable.amountMinor] }
                .toList()
                .sum()
        }

    /**
     * The requests on [rows] with their approvals, in two queries and never one inside another: with R2DBC a query
     * issued while another's rows are still streaming on the same connection waits for it forever (it hung every
     * open-requests read for 10 s and dropped the connection, SQLSTATE 08006, once a group had an open request).
     */
    private suspend fun withApprovals(rows: List<ResultRow>): List<Payout> {
        if (rows.isEmpty()) return emptyList()
        val byRequest =
            ApprovalsTable
                .selectAll()
                .where { ApprovalsTable.requestId inList rows.map { it[RequestsTable.requestId] } }
                .orderBy(ApprovalsTable.at to SortOrder.ASC)
                .toList()
                .groupBy({ it[ApprovalsTable.requestId] }) {
                    Approval(
                        UserId(it[ApprovalsTable.userId]),
                        Decision.valueOf(it[ApprovalsTable.decision]),
                        it[ApprovalsTable.at],
                    )
                }
        return rows.map { it.toRequest(byRequest[it[RequestsTable.requestId]].orEmpty()) }
    }

    private fun ResultRow.toRequest(approvals: List<Approval>) =
        Payout(
            requestId = RequestId(this[RequestsTable.requestId]),
            chatId = ChatId(this[RequestsTable.chatId]),
            requestedBy = UserId(this[RequestsTable.requestedBy]),
            payeeId = this[RequestsTable.payeeId]?.let { UserId(it) },
            payeeAddress = this[RequestsTable.payeeAddress],
            payeeName = this[RequestsTable.payeeName],
            amountMinor = this[RequestsTable.amountMinor],
            note = this[RequestsTable.note],
            state = RequestState.valueOf(this[RequestsTable.state]),
            approvalsNeeded = this[RequestsTable.approvalsNeeded],
            approvals = approvals,
            transferId = this[RequestsTable.transferId]?.let { TransferId(it) },
            expiresAt = this[RequestsTable.expiresAt],
            createdAt = this[RequestsTable.createdAt],
            updatedAt = this[RequestsTable.updatedAt],
        )
}

/** Chama's outbox rows, through infrastructure's `writeOutbox`. */
class ChamaOutboxImpl(private val database: Database) : ChamaOutbox {
    override suspend fun write(events: List<EventEnvelope>) {
        if (events.isEmpty()) return
        database.tx("outbox.write") { events.forEach { database.writeOutbox(it) } }
    }
}
