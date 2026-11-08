package com.circaso.identity.data.repositories

import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.data.tables.AccountsTable
import com.circaso.identity.data.tables.DevicesTable
import com.circaso.identity.data.tables.ProviderLinksTable
import com.circaso.identity.data.tables.SessionsTable
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.AccountKey
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.DeviceStatus
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.models.SessionRecord
import com.circaso.identity.domain.repositories.AccountAdminRepository
import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.JavaLocalDateColumnType
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import java.time.Instant
import java.time.LocalDate

/** Staff reads over identity's tables (decision 2026-11-08); each is served by an index, most by V3__admin_reads. */
class AccountAdminRepositoryImpl(private val database: Database) : AccountAdminRepository {
    override suspend fun accounts(ids: Set<UserId>): List<Account> {
        if (ids.isEmpty()) return emptyList()
        return database.tx("admin.accounts") {
            AccountsTable
                .selectAll()
                .where { AccountsTable.userId inList ids.map { it.value } }
                .map { it.toAccount() }
                .toList()
        }
    }

    override suspend fun links(ids: Set<UserId>): Map<UserId, List<ProviderLink>> {
        if (ids.isEmpty()) return emptyMap()
        return database.tx("admin.links") {
            // The primary key (user_id, provider) serves it.
            ProviderLinksTable
                .selectAll()
                .where { ProviderLinksTable.userId inList ids.map { it.value } }
                .map { it.toProviderLink() }
                .toList()
                .groupBy { it.userId }
        }
    }

    override suspend fun byEmail(email: String, limit: Int): List<UserId> =
        database.tx("admin.by-email") {
            // provider_links_email_idx is on lower(email): the same expression, so the index answers it.
            ProviderLinksTable
                .select(ProviderLinksTable.userId)
                .where { ProviderLinksTable.email.lowerCase() eq email.lowercase() }
                .limit(limit)
                .map { UserId(it[ProviderLinksTable.userId]) }
                .toList()
                .distinct()
        }

    override suspend fun newest(after: AccountKey?, limit: Int): List<Account> =
        database.tx("admin.newest-accounts") {
            AccountsTable
                .selectAll()
                .where { after?.let(::before) ?: Op.TRUE }
                .orderBy(AccountsTable.createdAt to SortOrder.DESC, AccountsTable.userId to SortOrder.DESC)
                .limit(limit)
                .map { it.toAccount() }
                .toList()
        }

    override suspend fun devices(userId: UserId): List<Device> =
        database.tx("admin.devices") {
            // The primary key (user_id, device_id) serves it; an account has a handful of phones at most.
            DevicesTable
                .selectAll()
                .where { DevicesTable.userId eq userId.value }
                .orderBy(DevicesTable.registeredAt to SortOrder.DESC)
                .map { it.toDevice() }
                .toList()
        }

    override suspend fun sessions(
        userId: UserId,
        now: Instant,
        limit: Int,
    ): List<SessionRecord> =
        database.tx("admin.sessions") {
            SessionsTable
                .selectAll()
                .where { (SessionsTable.userId eq userId.value) and (SessionsTable.expiresAt greater now) }
                .orderBy(SessionsTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { SessionRecord(it.toSession(), it[SessionsTable.expiresAt]) }
                .toList()
        }

    override suspend fun signUpTotal(): Long =
        database.tx("admin.sign-up-total") {
            val count = AccountsTable.userId.count()
            AccountsTable
                .select(count)
                .where { signedUp() }
                .map { it[count] }
                .single()
        }

    override suspend fun signUpsByDay(from: Instant, until: Instant): Map<LocalDate, Long> =
        database.tx("admin.sign-ups-by-day") {
            // A range of accounts_newest_idx (created_at DESC, user_id DESC), at most 366 days of sign-ups.
            val day = UtcDay(AccountsTable.createdAt)
            val count = AccountsTable.userId.count()
            AccountsTable
                .select(day, count)
                .where {
                    signedUp() and (AccountsTable.createdAt greaterEq from) and (AccountsTable.createdAt less until)
                }.groupBy(day)
                .map { it[day] to it[count] }
                .toList()
                .toMap()
        }

    override suspend fun activeSince(since: Instant): Long =
        database.tx("admin.active-since") {
            // devices_seen_idx (last_seen_at WHERE status = 'ACTIVE'): a range of the index, at most 30 days of it.
            val people = DevicesTable.userId.countDistinct()
            DevicesTable
                .select(people)
                .where {
                    (DevicesTable.status eq DeviceStatus.ACTIVE.name) and (DevicesTable.lastSeenAt greaterEq since)
                }.map { it[people] }
                .single()
        }

    /** A person who finished sign-up and isn't deleted: the people staff count. */
    private fun signedUp(): Op<Boolean> =
        (AccountsTable.kind eq AccountKind.PERSON.name) and
            (AccountsTable.status notInList NOT_SIGNED_UP)

    /**
     * Rows after [key] newest first: older, or as old with a smaller id. The leading `created_at <=` repeats what the
     * `or` implies, so the planner reads it as a range of `accounts_newest_idx` rather than a filter.
     */
    private fun before(key: AccountKey): Op<Boolean> =
        (AccountsTable.createdAt lessEq key.createdAt) and
            (
                (AccountsTable.createdAt less key.createdAt) or
                    ((AccountsTable.createdAt eq key.createdAt) and (AccountsTable.userId less key.userId.value))
            )

    private companion object {
        val NOT_SIGNED_UP = listOf(AccountStatus.PENDING_PHONE.name, AccountStatus.DELETED.name)
    }
}

/**
 * `(created_at AT TIME ZONE 'UTC')::date`: the UTC day of an instant, whatever the session's time zone. The DSL has no
 * such function; the expression inside is the DSL's own column, so nothing is spliced in.
 */
private class UtcDay(private val instant: Expression<Instant>) : ExpressionWithColumnType<LocalDate>() {
    override val columnType: IColumnType<LocalDate> = JavaLocalDateColumnType()

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("((")
        queryBuilder.append(instant)
        queryBuilder.append(") AT TIME ZONE 'UTC')::date")
    }
}
