package com.circaso.wallet

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountStatus
import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.api.ChatId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.UserId
import com.circaso.users.api.Profiles
import com.circaso.users.api.PublicProfile
import com.circaso.users.api.ReachPolicy
import com.circaso.wallet.api.ApprovedPayout
import com.circaso.wallet.api.PayoutApprovals
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.domain.models.HistoryFilter
import com.circaso.wallet.domain.models.HistoryPosition
import com.circaso.wallet.domain.models.PaymentHold
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.models.WalletTransaction
import com.circaso.wallet.domain.ports.ActiveWallets
import com.circaso.wallet.domain.ports.DailySpend
import com.circaso.wallet.domain.ports.FrameSender
import com.circaso.wallet.domain.ports.LoggedFrame
import com.circaso.wallet.domain.ports.PaymentHolds
import com.circaso.wallet.domain.ports.RealtimeLog
import com.circaso.wallet.domain.ports.Reminders
import com.circaso.wallet.domain.ports.StuckNotices
import com.circaso.wallet.domain.ports.WalletFrame
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.repositories.TransferRepository
import com.circaso.wallet.domain.repositories.WalletOwnerRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A clock the test moves. */
class MutableClock(
    @Volatile var now: Instant = Instant.parse("2026-10-28T09:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

/** `transfers` in memory, with the same key claim and transition guard as the table. */
class MemoryTransferRepository : TransferRepository {
    val rows = ConcurrentHashMap<TransferId, Transfer>()

    override suspend fun insert(transfer: Transfer): Boolean =
        synchronized(rows) {
            val taken =
                rows.values.any {
                    it.transferId == transfer.transferId ||
                        (it.senderId == transfer.senderId && it.idempotencyKey == transfer.idempotencyKey)
                }
            if (!taken) rows[transfer.transferId] = transfer
            !taken
        }

    override suspend fun byKey(senderId: UserId, idempotencyKey: UUID): Transfer? =
        rows.values.firstOrNull { it.senderId == senderId && it.idempotencyKey == idempotencyKey }

    override suspend fun find(transferId: TransferId): Transfer? = rows[transferId]

    override suspend fun forPayout(payoutRequestId: UUID): List<Transfer> =
        rows.values.filter { it.payoutRequestId == payoutRequestId }

    override suspend fun move(
        transferId: TransferId,
        to: TransferState,
        change: TransferChange,
        from: Set<TransferState>,
    ): Transfer? =
        synchronized(rows) {
            val current = rows[transferId]?.takeIf { it.state in to.from && it.state in from } ?: return null
            val moved =
                current.copy(
                    state = to,
                    updatedAt = change.at,
                    circleTxId = change.circleTxId ?: current.circleTxId,
                    txHash = change.txHash ?: current.txHash,
                    failureReason = change.failureReason ?: current.failureReason,
                )
            rows[transferId] = moved
            moved
        }

    override suspend fun inState(
        state: TransferState,
        createdAfter: Instant,
        createdBefore: Instant,
        limit: Int,
    ): List<Transfer> =
        rows.values
            .filter {
                it.state == state &&
                    !it.createdAt.isBefore(
                        createdAfter,
                    ) && it.createdAt.isBefore(createdBefore)
            }.sortedByDescending { it.createdAt }
            .take(limit)
}

/** `transactions` in memory, unique per (Circle transaction, user) and per transfer. */
class MemoryTransactionRepository : TransactionRepository {
    val rows: MutableList<WalletTransaction> = CopyOnWriteArrayList()

    override suspend fun insert(transaction: WalletTransaction): Boolean =
        synchronized(rows) {
            val taken =
                rows.any {
                    it.id == transaction.id ||
                        (
                            transaction.circleTxId != null &&
                                it.circleTxId == transaction.circleTxId &&
                                it.userId == transaction.userId
                        ) ||
                        (transaction.transferId != null && it.transferId == transaction.transferId)
                }
            if (!taken) rows += transaction
            !taken
        }

    override suspend fun moveByTransfer(
        transferId: TransferId,
        to: TransferState,
        change: TransferChange,
    ): Boolean = move(to, change) { it.transferId == transferId } != null

    override suspend fun moveByCircle(
        circleTxId: String,
        userId: UserId,
        to: TransferState,
        change: TransferChange,
    ): WalletTransaction? = move(to, change) { it.circleTxId == circleTxId && it.userId == userId }

    override suspend fun parties(since: Instant, limit: Int): List<UserId> =
        rows
            .filter { !it.occurredAt.isBefore(since) }
            .flatMap { listOfNotNull(it.userId, it.counterpartyId) }
            .distinct()
            .take(limit)

    /** Ordered as PostgreSQL orders the index: time, then the id's bytes (its hex text), both descending. */
    override suspend fun history(
        userId: UserId,
        states: Set<TransferState>,
        from: Instant?,
        until: Instant?,
        after: HistoryPosition?,
        limit: Int,
        filter: HistoryFilter,
    ): List<WalletTransaction> =
        rows
            .filter { it.userId == userId && it.state in states }
            .filter { filter.direction == null || it.direction == filter.direction }
            .filter { filter.counterpartyId == null || it.counterpartyId == filter.counterpartyId }
            .filter { filter.counterpartyAddress == null || it.counterpartyAddress == filter.counterpartyAddress }
            .filter { filter.asset == null || it.amount.asset == filter.asset }
            .filter { filter.blockchain == null || it.blockchain == filter.blockchain }
            .filter { from == null || !it.occurredAt.isBefore(from) }
            .filter { until == null || it.occurredAt.isBefore(until) }
            .filter { row ->
                after == null ||
                    row.occurredAt.isBefore(after.occurredAt) ||
                    (row.occurredAt == after.occurredAt && row.id.toString() < after.id.toString())
            }.sortedWith(compareByDescending<WalletTransaction> { it.occurredAt }.thenByDescending { it.id.toString() })
            .take(limit)

    override suspend fun find(userId: UserId, id: UUID): WalletTransaction? =
        rows.firstOrNull { it.id == id && it.userId == userId }

    private fun move(
        to: TransferState,
        change: TransferChange,
        where: (WalletTransaction) -> Boolean,
    ): WalletTransaction? =
        synchronized(rows) {
            val index = rows.indexOfFirst { where(it) && it.state in to.from }
            if (index < 0) return null
            val current = rows[index]
            val moved =
                current.copy(
                    state = to,
                    circleTxId = change.circleTxId ?: current.circleTxId,
                    txHash = change.txHash ?: current.txHash,
                )
            rows[index] = moved
            moved
        }
}

/** The frames the wallet sent, by user. */
class RecordingFrames : FrameSender {
    val sent: MutableList<Pair<UserId, WalletFrame>> = CopyOnWriteArrayList()

    override suspend fun send(userId: UserId, frame: WalletFrame) {
        sent += userId to frame
    }

    inline fun <reified F : WalletFrame> to(userId: UserId): List<F> =
        sent.filter { it.first == userId }.map { it.second }.filterIsInstance<F>()
}

/** Use-case tests read no replay; `WalletRealtimeTest` covers the kept frames on PostgreSQL. */
object NoFramesKept : RealtimeLog {
    override suspend fun since(
        userId: UserId,
        seq: Long,
        limit: Int,
    ): List<LoggedFrame> = emptyList()

    override suspend fun latest(userId: UserId): Long? = null
}

class MemoryDailySpend : DailySpend {
    val totals = ConcurrentHashMap<Pair<UserId, LocalDate>, Long>()

    override suspend fun total(userId: UserId, day: LocalDate): Long = totals[userId to day] ?: 0

    override suspend fun add(
        userId: UserId,
        day: LocalDate,
        amountMinor: Long,
    ): Long = totals.merge(userId to day, amountMinor, Long::plus)!!
}

/** `wal:active` in memory: the last time each user was seen. */
class MemoryActiveWallets(private val clock: Clock) : ActiveWallets {
    val seen = ConcurrentHashMap<UserId, Instant>()

    override suspend fun touch(userId: UserId) {
        seen[userId] = clock.instant()
    }

    override suspend fun since(cutoff: Instant): List<UserId> =
        seen.entries
            .filter { !it.value.isBefore(cutoff) }
            .sortedByDescending { it.value }
            .map { it.key }
}

class MemoryStuckNotices : StuckNotices {
    val claimed: MutableSet<TransferId> = ConcurrentHashMap.newKeySet()

    override suspend fun claim(transferId: TransferId): Boolean = claimed.add(transferId)
}

class MemoryReminders : Reminders {
    val claimed: MutableSet<Pair<UserId, UserId>> = ConcurrentHashMap.newKeySet()

    override suspend fun claim(from: UserId, to: UserId): Boolean = claimed.add(from to to)
}

class MemoryHolds : PaymentHolds {
    val holds = ConcurrentHashMap<UserId, PaymentHold>()

    override suspend fun active(userId: UserId): PaymentHold? = holds[userId]
}

/** Every account is a person's and `ACTIVE` unless the test says otherwise. */
class FakeAccounts : AccountDirectory {
    val statuses = ConcurrentHashMap<UserId, AccountStatus>()
    val kinds = ConcurrentHashMap<UserId, AccountKind>()

    override suspend fun status(userId: UserId): AccountStatus? = statuses[userId] ?: AccountStatus.ACTIVE

    override suspend fun kind(userId: UserId): AccountKind? = kinds[userId] ?: AccountKind.PERSON

    override suspend fun resolvePhones(phones: Set<PhoneNumber>): Map<PhoneNumber, UserId> = emptyMap()
}

/** Reach is a set of connected pairs (either way); blocks win over a shared chat. */
class FakeReach : ReachPolicy {
    val connected: MutableSet<Set<UserId>> = ConcurrentHashMap.newKeySet()
    val blocked: MutableSet<Set<UserId>> = ConcurrentHashMap.newKeySet()

    fun connect(a: UserId, b: UserId) {
        connected += setOf(a, b)
    }

    override suspend fun canReach(from: UserId, to: UserId): Boolean =
        setOf(from, to) in connected && setOf(from, to) !in blocked

    override suspend fun reachable(from: UserId, to: Set<UserId>): Set<UserId> =
        to.filter { canReach(from, it) }.toSet()

    override suspend fun blockedEitherWay(a: UserId, b: UserId): Boolean = setOf(a, b) in blocked
}

class FakeChats : ChatDirectory {
    val members = ConcurrentHashMap<ChatId, Set<UserId>>()
    val owners = ConcurrentHashMap<ChatId, UserId>()

    override suspend fun isMember(chatId: ChatId, userId: UserId): Boolean = userId in members[chatId].orEmpty()

    override suspend fun members(chatId: ChatId): Set<UserId> = members[chatId].orEmpty()

    override suspend fun admins(chatId: ChatId): Set<UserId> = members[chatId].orEmpty()

    override suspend fun isGroup(chatId: ChatId): Boolean = members[chatId].orEmpty().size > 2

    override suspend fun groupsOf(userId: UserId): List<ChatId> =
        members.filter { (chat, people) -> userId in people && isGroup(chat) }.keys.toList()

    override suspend fun owner(chatId: ChatId): UserId? = owners[chatId]

    /** A group of [people] owned by the first. */
    fun group(people: List<UserId>): ChatId {
        val chatId = ChatId.new()
        members[chatId] = people.toSet()
        owners[chatId] = people.first()
        return chatId
    }
}

/** `wallet_owners` in memory, one wallet per group as `wallet_owners_chat_key`. */
class MemoryWalletOwners : WalletOwnerRepository {
    val rows = ConcurrentHashMap<WalletId, WalletOwner.Group>()

    /** Space wallets (Spaces phase 3). */
    val spaceRows = ConcurrentHashMap<WalletId, WalletOwner.Space>()

    override suspend fun find(walletId: WalletId): WalletOwner? = rows[walletId] ?: spaceRows[walletId]

    override suspend fun bySpace(spaceId: UUID): WalletOwner.Space? = spaceRows.values.firstOrNull { it.spaceId == spaceId }

    override suspend fun bySpaces(spaceIds: Collection<UUID>): List<WalletOwner.Space> =
        spaceRows.values.filter { it.spaceId in spaceIds }

    override suspend fun insertSpace(owner: WalletOwner.Space): Boolean =
        synchronized(spaceRows) {
            val taken = spaceRows.containsKey(owner.walletId) || spaceRows.values.any { it.spaceId == owner.spaceId }
            if (!taken) spaceRows[owner.walletId] = owner
            !taken
        }

    override suspend fun byChat(chatId: ChatId): WalletOwner.Group? = rows.values.firstOrNull { it.chatId == chatId }

    override suspend fun byChats(chatIds: Collection<ChatId>): List<WalletOwner.Group> =
        rows.values.filter { it.chatId in chatIds }

    override suspend fun bySigner(signerId: UserId): List<WalletOwner> =
        rows.values.filter { it.signerId == signerId } + spaceRows.values.filter { it.signerId == signerId }

    override suspend fun insert(owner: WalletOwner.Group): Boolean =
        synchronized(rows) {
            val taken = rows.containsKey(owner.walletId) || rows.values.any { it.chatId == owner.chatId }
            if (!taken) rows[owner.walletId] = owner
            !taken
        }
}

/** Chama's side of option A: the payouts the admins approved, by request id. */
class FakePayoutApprovals : PayoutApprovals {
    val approved = ConcurrentHashMap<UUID, ApprovedPayout>()

    override suspend fun approved(chatId: ChatId, requestId: UUID): ApprovedPayout? =
        approved[requestId]?.takeIf { it.chatId == chatId }
}

class FakeProfiles : Profiles {
    val names = ConcurrentHashMap<UserId, String>()

    override suspend fun get(ids: Set<UserId>): Map<UserId, PublicProfile> =
        ids.mapNotNull { id -> names[id]?.let { id to PublicProfile(id, it, null, null, null) } }.toMap()
}
