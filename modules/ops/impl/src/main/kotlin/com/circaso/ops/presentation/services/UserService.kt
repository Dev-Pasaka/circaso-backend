package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.GroupSummary
import com.circaso.ops.domain.models.TicketLine
import com.circaso.ops.domain.models.UserAccount
import com.circaso.ops.domain.models.UserDevices
import com.circaso.ops.domain.models.UserPage
import com.circaso.ops.domain.usecases.CountPendingSignUps
import com.circaso.ops.domain.usecases.DeletePendingSignUps
import com.circaso.ops.domain.usecases.GetUserAccount
import com.circaso.ops.domain.usecases.GetUserDevices
import com.circaso.ops.domain.usecases.ListUserGroups
import com.circaso.ops.domain.usecases.ListUserTickets
import com.circaso.ops.domain.usecases.SearchUsers
import com.circaso.ops.domain.usecases.SuspendUser
import com.circaso.ops.domain.usecases.UnsuspendUser
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** People: search, the account, phones and sessions, groups, tickets, and suspension. */
class UserService(
    private val searchUsers: SearchUsers,
    private val getUserAccount: GetUserAccount,
    private val getUserDevices: GetUserDevices,
    private val listUserGroups: ListUserGroups,
    private val listUserTickets: ListUserTickets,
    private val suspendUser: SuspendUser,
    private val unsuspendUser: UnsuspendUser,
    private val countPendingSignUps: CountPendingSignUps,
    private val deletePendingSignUps: DeletePendingSignUps,
) {
    suspend fun search(
        query: String,
        cursor: String?,
        limit: Int,
        includePending: Boolean,
    ): Resource<UserPage> = searchUsers(query, cursor, limit, includePending)

    suspend fun pendingSignUps(): Resource<Long> = countPendingSignUps()

    suspend fun deletePendingSignUps(actor: Actor): Resource<Int> = deletePendingSignUps.invoke(actor)

    suspend fun account(userId: UserId): Resource<UserAccount> = getUserAccount(userId)

    suspend fun devices(userId: UserId): Resource<UserDevices> = getUserDevices(userId)

    suspend fun groups(userId: UserId): Resource<List<GroupSummary>> = listUserGroups(userId)

    suspend fun tickets(userId: UserId): Resource<List<TicketLine>> = listUserTickets(userId)

    suspend fun suspend(
        actor: Actor,
        userId: UserId,
        reason: String,
    ): Resource<UserAccount> = suspendUser(actor, userId, reason)

    suspend fun unsuspend(
        actor: Actor,
        userId: UserId,
        reason: String,
    ): Resource<UserAccount> = unsuspendUser(actor, userId, reason)
}
