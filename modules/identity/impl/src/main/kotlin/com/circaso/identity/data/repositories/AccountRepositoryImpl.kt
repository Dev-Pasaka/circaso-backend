package com.circaso.identity.data.repositories

import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.data.tables.AccountsTable
import com.circaso.identity.data.tables.DevicesTable
import com.circaso.identity.data.tables.ProviderLinksTable
import com.circaso.identity.data.tables.SecurityLogTable
import com.circaso.identity.data.tables.SessionsTable
import com.circaso.identity.data.tables.SupportRecoveriesTable
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.DatabaseException
import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.ComplexExpression
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.deleteReturning
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Clock
import java.time.Instant
import java.util.UUID

class AccountRepositoryImpl(private val database: Database, private val clock: Clock) : AccountRepository {
    override suspend fun find(userId: UserId): Account? =
        database.tx("accounts.find") {
            AccountsTable
                .selectAll()
                .where { AccountsTable.userId eq userId.value }
                .map { it.toAccount() }
                .singleOrNull()
        }

    override suspend fun lock(userId: UserId): Account? =
        database.tx("accounts.lock") {
            AccountsTable
                .selectAll()
                .where { AccountsTable.userId eq userId.value }
                .forUpdate(ForUpdateOption.ForUpdate)
                .map { it.toAccount() }
                .singleOrNull()
        }

    override suspend fun findByPhone(phone: PhoneNumber): Account? =
        database.tx("accounts.find-by-phone") {
            AccountsTable
                .selectAll()
                .where { AccountsTable.phone eq phone.e164 }
                .map { it.toAccount() }
                .singleOrNull()
        }

    override suspend fun create(account: Account) {
        database.tx("accounts.create") {
            AccountsTable.insert {
                it[userId] = account.userId.value
                it[createdAt] = account.createdAt
                it[version] = account.version
                it[kind] = account.kind.name
                write(it, account, clock.instant())
            }
        }
    }

    override suspend fun supportAccounts(after: UserId?, limit: Int): List<Account> =
        database.tx("accounts.support-accounts") {
            // Served by the partial index accounts_support_idx (user_id WHERE kind = 'SUPPORT').
            val support = AccountsTable.kind eq AccountKind.SUPPORT.name
            AccountsTable
                .selectAll()
                .where { if (after == null) support else support and (AccountsTable.userId greater after.value) }
                .orderBy(AccountsTable.userId to SortOrder.ASC)
                .limit(limit)
                .map { it.toAccount() }
                .toList()
        }

    override suspend fun deleteAbandoned(createdBefore: Instant, limit: Int): Int =
        database.tx("accounts.delete-abandoned") {
            val pending = (AccountsTable.status eq AccountStatus.PENDING_PHONE.name)
            // Oldest first, skipping a sign-up that is finishing right now (its transaction holds the row's lock). The
            // pick is taken as one array (`= ANY(ARRAY(…))`): as `IN (…)` PostgreSQL may run it again for each row the
            // delete scans, and with SKIP LOCKED each run can pick other rows, so a chunk could pass its limit.
            val due =
                AccountsTable
                    .select(AccountsTable.userId)
                    .where { pending and (AccountsTable.createdAt less createdBefore) }
                    .orderBy(AccountsTable.createdAt to SortOrder.ASC)
                    .limit(limit)
                    .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            val deleted =
                AccountsTable
                    .deleteReturning(listOf(AccountsTable.userId)) { AnyOfPick(AccountsTable.userId, due) and pending }
                    .map { it[AccountsTable.userId] }
                    .toList()
            if (deleted.isNotEmpty()) deleteBelongings(deleted)
            deleted.size
        }

    override suspend fun dueForDeletion(now: Instant, limit: Int): List<UserId> =
        database.tx("accounts.due-for-deletion") {
            // Served by the partial index accounts_deleted_idx (delete_after WHERE status = 'DELETED').
            AccountsTable
                .select(AccountsTable.userId)
                .where {
                    (AccountsTable.status eq AccountStatus.DELETED.name) and (AccountsTable.deleteAfter lessEq now)
                }.orderBy(AccountsTable.deleteAfter to SortOrder.ASC)
                .limit(limit)
                .map { UserId(it[AccountsTable.userId]) }
                .toList()
        }

    override suspend fun completeDeletion(userId: UserId, now: Instant): Boolean =
        database.tx("accounts.complete-deletion") {
            val deleted =
                AccountsTable
                    .deleteReturning(listOf(AccountsTable.userId)) {
                        (AccountsTable.userId eq userId.value) and
                            (AccountsTable.status eq AccountStatus.DELETED.name) and
                            (AccountsTable.deleteAfter lessEq now)
                    }.toList()
            if (deleted.isNotEmpty()) deleteBelongings(listOf(userId.value))
            deleted.isNotEmpty()
        }

    override suspend fun claimPhone(account: Account, phone: PhoneNumber): Boolean {
        val written =
            try {
                database.tx("accounts.claim-phone") {
                    AccountsTable.update({
                        (AccountsTable.userId eq account.userId.value) and (AccountsTable.version eq account.version)
                    }) {
                        it[version] = account.version + 1
                        it[this.phone] = phone.e164
                        it[updatedAt] = clock.instant()
                    }
                }
            } catch (e: DatabaseException) {
                // Another account holds the number: the unique index is the claim.
                if (e.isUniqueViolation && e.constraint == AccountsTable.PHONE_KEY) return false
                throw e
            }
        check(written == 1) { "Account claim lost: not found, or changed since it was read" }
        return true
    }

    /**
     * For the store: writes a changed account inside the open transaction, if it is still at the version it was read
     * at, and moves the version on by one. A number is set only through [claimPhone].
     */
    internal suspend fun update(account: Account) {
        val written =
            database.tx("accounts.update") {
                AccountsTable.update({
                    (AccountsTable.userId eq account.userId.value) and (AccountsTable.version eq account.version)
                }) {
                    it[version] = account.version + 1
                    write(it, account, clock.instant())
                }
            }
        // Every use case writes an account it read under its lock, so a stale version is a bug, not a race.
        check(written == 1) { "Account write lost: not found, or changed since it was read" }
    }

    /** Every identity row of a person besides the account itself. */
    private suspend fun deleteBelongings(userIds: List<UUID>) {
        ProviderLinksTable.deleteWhere { ProviderLinksTable.userId inList userIds }
        DevicesTable.deleteWhere { DevicesTable.userId inList userIds }
        SessionsTable.deleteWhere { SessionsTable.userId inList userIds }
        SecurityLogTable.deleteWhere { SecurityLogTable.userId inList userIds }
        SupportRecoveriesTable.deleteWhere { SupportRecoveriesTable.userId inList userIds }
    }
}

/**
 * `expr = ANY(ARRAY(pick))`: the pick runs once, as an init plan, so a `DELETE` takes exactly the rows it chose. The
 * DSL has no such operator; the pick is the DSL's own query, so nothing is spliced in.
 */
private class AnyOfPick<T>(private val expr: Expression<T>, private val pick: AbstractQuery<*>) :
    Op<Boolean>(),
    ComplexExpression {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append(expr).append(" = ANY(ARRAY(")
        pick.prepareSQL(queryBuilder)
        queryBuilder.append("))")
    }
}

/** The columns a create and an update both set. */
private fun AccountsTable.write(
    row: UpdateBuilder<*>,
    account: Account,
    now: Instant,
) {
    row[phone] = account.phone?.e164
    row[status] = account.status.name
    row[activeDeviceId] = account.activeDeviceId?.value
    row[verification] = account.verification.name
    row[sessionVersion] = account.sessionVersion
    row[deleteAfter] = account.deleteAfter
    row[updatedAt] = now
}

internal fun ResultRow.toAccount() =
    Account(
        userId = UserId(this[AccountsTable.userId]),
        phone = this[AccountsTable.phone]?.let(PhoneNumber::parse),
        status = AccountStatus.valueOf(this[AccountsTable.status]),
        activeDeviceId = this[AccountsTable.activeDeviceId]?.let(::DeviceId),
        verification = Verification.valueOf(this[AccountsTable.verification]),
        sessionVersion = this[AccountsTable.sessionVersion],
        createdAt = this[AccountsTable.createdAt],
        deleteAfter = this[AccountsTable.deleteAfter],
        version = this[AccountsTable.version],
        kind = AccountKind.valueOf(this[AccountsTable.kind]),
    )
