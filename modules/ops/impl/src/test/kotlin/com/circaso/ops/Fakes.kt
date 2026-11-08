package com.circaso.ops

import com.circaso.identity.api.AccountAdmin
import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountPage
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.ActiveCounts
import com.circaso.identity.api.AdminAccount
import com.circaso.identity.api.AdminDevice
import com.circaso.identity.api.AdminSession
import com.circaso.identity.api.DayCount
import com.circaso.identity.api.SignUpStats
import com.circaso.identity.api.SupportAccountPage
import com.circaso.identity.api.SupportAccountView
import com.circaso.identity.api.SupportAccounts
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupPage
import com.circaso.messaging.api.GroupReports
import com.circaso.messaging.api.OpsGroup
import com.circaso.messaging.api.OpsGroupMember
import com.circaso.ops.domain.models.AuditEntry
import com.circaso.ops.domain.models.AuditFilter
import com.circaso.ops.domain.models.AuditOutcome
import com.circaso.ops.domain.models.Staff
import com.circaso.ops.domain.repositories.AuditRepository
import com.circaso.ops.domain.repositories.StaffRepository
import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.api.SupportDesk
import com.circaso.support.api.TicketAuthor
import com.circaso.support.api.TicketCategory
import com.circaso.support.api.TicketContext
import com.circaso.support.api.TicketCounts
import com.circaso.support.api.TicketDetail
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketMessage
import com.circaso.support.api.TicketPage
import com.circaso.support.api.TicketQueueFilter
import com.circaso.support.api.TicketSource
import com.circaso.support.api.TicketStatus
import com.circaso.support.api.TicketSummary
import com.circaso.users.api.AdminProfile
import com.circaso.users.api.ProfileAdmin
import com.circaso.users.api.ProfileIdPage
import com.circaso.wallet.api.FinanceReport
import com.circaso.wallet.api.GroupWalletPage
import com.circaso.wallet.api.GroupWalletRef
import com.circaso.wallet.api.OpsHoldings
import com.circaso.wallet.api.OpsTransfer
import com.circaso.wallet.api.ReportBucket
import com.circaso.wallet.api.TransferFilter
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.TransferPage
import com.circaso.wallet.api.WalletReports
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// In-memory stand-ins for the contracts the ops module reaches, and its own two repositories.

private data class FakeError(override val code: String, override val kind: ErrorKind) : DomainError {
    override val message: String = code
}

val EPOCH: Instant = Instant.parse("2026-11-01T00:00:00Z")

class FakeAccounts : AccountAdmin {
    val accounts = ConcurrentHashMap<UserId, AdminAccount>()
    val suspendCalls = CopyOnWriteArrayList<UserId>()

    fun add(status: AccountStatus = AccountStatus.ACTIVE): UserId {
        val id = UserId.new()
        accounts[id] = AdminAccount(id, AccountKind.PERSON, status, "+254•••••456", "NONE", EPOCH, emptyList())
        return id
    }

    override suspend fun account(userId: UserId) = accounts[userId]

    override suspend fun accounts(ids: Set<UserId>) = accounts.filterKeys { it in ids }

    override suspend fun findByPhone(phone: PhoneNumber): UserId? = accounts.keys.firstOrNull()

    override suspend fun findByEmail(email: String): List<UserId> = emptyList()

    override suspend fun newest(cursor: String?, limit: Int) =
        Resource.success(AccountPage(accounts.values.toList().take(limit), null))

    override suspend fun devices(userId: UserId): List<AdminDevice> = emptyList()

    override suspend fun sessions(userId: UserId, limit: Int): List<AdminSession> = emptyList()

    override suspend fun suspend(userId: UserId): Resource<AdminAccount> {
        suspendCalls += userId
        val account =
            accounts[userId] ?: return Resource.error(FakeError("identity.account_not_found", ErrorKind.NOT_FOUND))
        if (account.status != AccountStatus.ACTIVE) {
            return Resource.error(FakeError("identity.account_not_active", ErrorKind.CONFLICT))
        }
        return Resource.success(account.copy(status = AccountStatus.SUSPENDED).also { accounts[userId] = it })
    }

    override suspend fun unsuspend(userId: UserId): Resource<AdminAccount> {
        val account =
            accounts[userId] ?: return Resource.error(FakeError("identity.account_not_found", ErrorKind.NOT_FOUND))
        if (account.status != AccountStatus.SUSPENDED) {
            return Resource.error(FakeError("identity.account_not_suspended", ErrorKind.CONFLICT))
        }
        return Resource.success(account.copy(status = AccountStatus.ACTIVE).also { accounts[userId] = it })
    }

    override suspend fun signUps(from: LocalDate, to: LocalDate) =
        SignUpStats(accounts.size.toLong(), 1, listOf(DayCount(from, 1)))

    override suspend fun activeUsers(now: Instant) = ActiveCounts(1, 2, 3)
}

class FakeProfiles : ProfileAdmin {
    val profiles = ConcurrentHashMap<UserId, AdminProfile>()

    fun name(
        userId: UserId,
        displayName: String,
        handle: String? = null,
    ) {
        profiles[userId] = AdminProfile(userId, displayName, handle, null, null, false, EPOCH)
    }

    override suspend fun profiles(ids: Set<UserId>) = profiles.filterKeys { it in ids }

    override suspend fun searchByHandle(
        prefix: String,
        cursor: String?,
        limit: Int,
    ) = Resource.success(
        ProfileIdPage(
            profiles.values
                .filter {
                    it.handle?.startsWith(prefix) == true
                }.map { it.userId },
            null,
        ),
    )

    override suspend fun searchByName(
        prefix: String,
        cursor: String?,
        limit: Int,
    ) = Resource.success(
        ProfileIdPage(
            profiles.values.filter { it.displayName.startsWith(prefix, ignoreCase = true) }.map { it.userId },
            null,
        ),
    )
}

class FakeGroups : GroupReports {
    /** Every `groupsByIds` call, to prove a page asks once. */
    val lookups = CopyOnWriteArrayList<Set<ChatId>>()
    val groups = ConcurrentHashMap<ChatId, OpsGroup>()

    override suspend fun groups(cursor: String?, limit: Int) = Resource.success(GroupPage(groups.values.toList(), null))

    override suspend fun groupsByIds(ids: Set<ChatId>): Map<ChatId, OpsGroup> {
        lookups += ids
        return groups.filterKeys { it in ids }
    }

    override suspend fun members(chatId: ChatId, limit: Int): List<OpsGroupMember> =
        groups[chatId]?.ownerId?.let { listOf(OpsGroupMember(it, "OWNER", EPOCH)) }.orEmpty()

    override suspend fun groupsOf(userId: UserId, limit: Int) = groups.values.filter { it.ownerId == userId }

    override suspend fun groupCount() = groups.size.toLong()
}

class FakeWallets : WalletReports {
    val wallets = ConcurrentHashMap<ChatId, GroupWalletRef>()
    val holdingsAsked = CopyOnWriteArrayList<UUID>()
    val transfers = CopyOnWriteArrayList<OpsTransfer>()

    override suspend fun holdings(holderId: UUID): Resource<OpsHoldings> {
        holdingsAsked += holderId
        return Resource.success(OpsHoldings(true, emptyList(), 0, true))
    }

    override suspend fun transfersOf(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ) = Resource.success(
        TransferPage(transfers.filter { it.senderId == userId || it.recipientId == userId }, null),
    )

    override suspend fun feesPaidBy(userId: UserId) = 0L

    override suspend fun transfers(
        filter: TransferFilter,
        cursor: String?,
        limit: Int,
    ) = Resource.success(TransferPage(transfers.toList(), null))

    override suspend fun transfer(transferId: TransferId): OpsTransfer? =
        transfers.firstOrNull {
            it.transferId ==
                transferId
        }

    override suspend fun report(
        from: Instant,
        to: Instant,
        bucket: ReportBucket,
    ) = FinanceReport(emptyList(), emptyList(), 0, 0, 0)

    override suspend fun groupWalletCount() = wallets.size.toLong()

    override suspend fun groupWallets(cursor: String?, limit: Int) =
        Resource.success(GroupWalletPage(wallets.values.toList(), null))

    override suspend fun groupWalletsOf(chatIds: Set<ChatId>) = wallets.filterKeys { it in chatIds }
}

class FakeDesk : SupportDesk {
    val tickets = ConcurrentHashMap<TicketId, TicketDetail>()
    val replies = CopyOnWriteArrayList<Pair<TicketId, UUID>>()

    fun open(userId: UserId): TicketId {
        val id = TicketId.new()
        val summary =
            TicketSummary(
                id,
                "CS-000001",
                userId,
                "Payment stuck",
                TicketCategory.PAYMENTS,
                TicketStatus.OPEN,
                TicketSource.APP,
                null,
                EPOCH,
                EPOCH,
                EPOCH,
                null,
            )
        tickets[id] = TicketDetail(summary, "It has been an hour.", TicketContext(), emptyList())
        return id
    }

    private fun notFound(): Resource<Nothing> =
        Resource.error(FakeError("support.ticket_not_found", ErrorKind.NOT_FOUND))

    override suspend fun queue(
        filter: TicketQueueFilter,
        cursor: String?,
        limit: Int,
    ) = Resource.success(TicketPage(tickets.values.map { it.summary }, null))

    override suspend fun ticket(ticketId: TicketId) = tickets[ticketId]?.let { Resource.success(it) } ?: notFound()

    override suspend fun ofUser(userId: UserId, limit: Int) =
        Resource.success(tickets.values.map { it.summary }.filter { it.userId == userId })

    override suspend fun reply(
        ticketId: TicketId,
        staffId: UUID,
        body: String,
    ): Resource<TicketDetail> {
        val ticket = tickets[ticketId] ?: return notFound()
        replies += ticketId to staffId
        val message = TicketMessage(UUID.randomUUID(), TicketAuthor.STAFF, staffId, body, false, EPOCH)
        val next =
            ticket.copy(
                summary = ticket.summary.copy(status = TicketStatus.PENDING_USER),
                messages =
                    ticket.messages + message,
            )
        tickets[ticketId] = next
        return Resource.success(next)
    }

    override suspend fun note(
        ticketId: TicketId,
        staffId: UUID,
        body: String,
    ): Resource<TicketDetail> {
        val ticket = tickets[ticketId] ?: return notFound()
        val message = TicketMessage(UUID.randomUUID(), TicketAuthor.STAFF, staffId, body, true, EPOCH)
        return Resource.success(ticket.copy(messages = ticket.messages + message).also { tickets[ticketId] = it })
    }

    override suspend fun assign(ticketId: TicketId, assigneeId: UUID?): Resource<TicketDetail> {
        val ticket = tickets[ticketId] ?: return notFound()
        return Resource.success(
            ticket.copy(summary = ticket.summary.copy(assigneeId = assigneeId)).also {
                tickets[ticketId] =
                    it
            },
        )
    }

    override suspend fun changeStatus(ticketId: TicketId, status: TicketStatus): Resource<TicketDetail> {
        val ticket = tickets[ticketId] ?: return notFound()
        return Resource.success(
            ticket.copy(summary = ticket.summary.copy(status = status)).also {
                tickets[ticketId] =
                    it
            },
        )
    }

    override suspend fun counts() = Resource.success(TicketCounts(tickets.size.toLong(), 0, 0))
}

class FakeSupportAccounts : SupportAccounts {
    val created = CopyOnWriteArrayList<String>()

    override suspend fun create(
        displayName: String,
        handle: String,
        createdBy: String,
    ): Resource<SupportAccountView> {
        created += createdBy
        return Resource.success(SupportAccountView(UserId.new(), displayName, handle, EPOCH))
    }

    override suspend fun list(cursor: String?, limit: Int) = Resource.success(SupportAccountPage(emptyList(), null))
}

class MemoryStaff : StaffRepository {
    val rows = ConcurrentHashMap<UUID, Staff>()

    override suspend fun findByEmail(email: String) = rows.values.firstOrNull { it.email == email }

    override suspend fun find(id: UUID) = rows[id]

    override suspend fun findMany(ids: Set<UUID>) = rows.filterKeys { it in ids }

    override suspend fun list(limit: Int) = rows.values.sortedBy { it.id }.take(limit)

    override suspend fun insert(staff: Staff): Boolean {
        if (rows.values.any { it.email == staff.email }) return false
        rows[staff.id] = staff
        return true
    }

    override suspend fun update(staff: Staff): Boolean {
        val current = rows[staff.id] ?: return false
        if (current.version != staff.version) return false
        rows[staff.id] = staff.copy(version = staff.version + 1)
        return true
    }
}

class MemoryAudit : AuditRepository {
    val entries = CopyOnWriteArrayList<AuditEntry>()

    override suspend fun begin(entry: AuditEntry) {
        entries += entry
    }

    override suspend fun finish(
        id: UUID,
        outcome: AuditOutcome,
        errorCode: String?,
        finishedAt: Instant,
        detail: String?,
    ) {
        val index = entries.indexOfFirst { it.id == id }
        entries[index] =
            entries[index].copy(outcome = outcome, errorCode = errorCode, detail = detail ?: entries[index].detail)
    }

    override suspend fun list(
        filter: AuditFilter,
        before: UUID?,
        limit: Int,
    ) = entries
        .filter { filter.action == null || it.action == filter.action.wire }
        .filter { filter.targetId == null || it.targetId == filter.targetId }
        .sortedByDescending { it.id }
        .filter { before == null || it.id < before }
        .take(limit)

    override suspend fun latestSucceeded(action: String, targetId: String) =
        entries
            .filter {
                it.action == action && it.targetId == targetId && it.outcome == AuditOutcome.SUCCEEDED
            }.maxByOrNull { it.id }
}
