package com.circaso.ops.domain.usecases

import com.circaso.identity.api.AccountAdmin
import com.circaso.messaging.api.GroupReports
import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.models.GroupSummary
import com.circaso.ops.domain.models.TicketLine
import com.circaso.ops.domain.models.UserAccount
import com.circaso.ops.domain.models.UserDevices
import com.circaso.ops.domain.models.UserPage
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.ops.domain.support.Reasons
import com.circaso.ops.domain.support.TicketViews
import com.circaso.ops.domain.support.UserViews
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.support.api.SupportDesk
import com.circaso.users.api.ProfileAdmin
import com.circaso.wallet.api.WalletReports

// People, as SUPPORT, FINANCE and ADMIN see them. Who may call which is the routes' (Permission); these only read
// through the owning modules' contracts, except suspend and unsuspend, which identity carries out and ops audits.

/**
 * One search box for everything staff search people by (decision 2026-11-08): a user id, an @handle (prefix), a phone
 * number (exact, E.164), an email (exact) or a name (prefix). Empty: the newest accounts.
 */
class SearchUsers(
    private val accounts: AccountAdmin,
    private val profiles: ProfileAdmin,
    private val views: UserViews,
) {
    suspend operator fun invoke(
        query: String,
        cursor: String?,
        limit: Int,
    ): Resource<UserPage> {
        val q = query.trim()
        return when {
            q.isEmpty() -> {
                accounts.newest(cursor, limit).map { page ->
                    UserPage(views.rows(page.accounts.map { it.userId }), page.nextCursor)
                }
            }

            UserId.parse(q) != null -> {
                single(UserId.parse(q))
            }

            q.startsWith("@") -> {
                profiles
                    .searchByHandle(
                        q.removePrefix("@"),
                        cursor,
                        limit,
                    ).map { UserPage(views.rows(it.userIds), it.nextCursor) }
            }

            PhoneNumber.parse(q.filterNot { it == ' ' || it == '-' }) != null -> {
                single(accounts.findByPhone(PhoneNumber.parse(q.filterNot { it == ' ' || it == '-' })!!))
            }

            '@' in q -> {
                Resource.success(UserPage(views.rows(accounts.findByEmail(q)), null))
            }

            else -> {
                profiles.searchByName(q, cursor, limit).map { UserPage(views.rows(it.userIds), it.nextCursor) }
            }
        }
    }

    private suspend fun single(userId: UserId?): Resource<UserPage> =
        Resource.success(UserPage(userId?.let { views.rows(listOf(it)) }.orEmpty(), null))
}

class GetUserAccount(private val views: UserViews) {
    suspend operator fun invoke(userId: UserId): Resource<UserAccount> =
        views.account(userId)?.let { Resource.success(it) } ?: Resource.error(OpsError.UserNotFound)
}

/** Phones and sessions (SUPPORT, ADMIN): what to look at when someone says "I can't sign in". */
class GetUserDevices(private val accounts: AccountAdmin) {
    suspend operator fun invoke(userId: UserId): Resource<UserDevices> {
        accounts.account(userId) ?: return Resource.error(OpsError.UserNotFound)
        return Resource.success(UserDevices(accounts.devices(userId), accounts.sessions(userId, MAX_SESSIONS)))
    }

    private companion object {
        const val MAX_SESSIONS = 50
    }
}

class ListUserGroups(
    private val accounts: AccountAdmin,
    private val groups: GroupReports,
    private val wallets: WalletReports,
    private val views: UserViews,
) {
    suspend operator fun invoke(userId: UserId): Resource<List<GroupSummary>> {
        accounts.account(userId) ?: return Resource.error(OpsError.UserNotFound)
        val found = groups.groupsOf(userId, MAX_GROUPS)
        val withWallet = wallets.groupWalletsOf(found.map { it.chatId }.toSet())
        val owners = views.names(found.mapNotNull { it.ownerId }.toSet())
        return Resource.success(found.map { GroupSummary(it, it.ownerId?.let(owners::get), it.chatId in withWallet) })
    }

    private companion object {
        const val MAX_GROUPS = 200
    }
}

class ListUserTickets(
    private val accounts: AccountAdmin,
    private val desk: SupportDesk,
    private val tickets: TicketViews,
) {
    suspend operator fun invoke(userId: UserId): Resource<List<TicketLine>> {
        accounts.account(userId) ?: return Resource.error(OpsError.UserNotFound)
        return desk.ofUser(userId, MAX_TICKETS).map { tickets.rows(it) }
    }

    private companion object {
        const val MAX_TICKETS = 100
    }
}

/**
 * SUPPORT or ADMIN suspends someone (decision 2026-11-08): a reason is required and audited; identity revokes every
 * session, refuses sign-in with `identity.account_suspended` and tells the rest (sockets close, the profile hides, the
 * wallet refuses payments with `wallet.account_restricted`).
 */
class SuspendUser(private val accounts: AccountAdmin, private val views: UserViews, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        userId: UserId,
        reason: String,
    ): Resource<UserAccount> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail
            .record(actor, AuditAction.USER_SUSPEND, userId.toString(), reason = why) { accounts.suspend(userId) }
            .flatMap { views.account(userId)?.let { Resource.success(it) } ?: Resource.error(OpsError.UserNotFound) }
    }
}

/** Gives a suspended account back; they sign in again. A reason is required and audited. */
class UnsuspendUser(private val accounts: AccountAdmin, private val views: UserViews, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        userId: UserId,
        reason: String,
    ): Resource<UserAccount> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail
            .record(actor, AuditAction.USER_UNSUSPEND, userId.toString(), reason = why) { accounts.unsuspend(userId) }
            .flatMap { views.account(userId)?.let { Resource.success(it) } ?: Resource.error(OpsError.UserNotFound) }
    }
}
