package com.circaso.ops.domain.support

import com.circaso.identity.api.AccountAdmin
import com.circaso.identity.api.AccountStatus
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.models.Suspension
import com.circaso.ops.domain.models.UserAccount
import com.circaso.ops.domain.models.UserSummary
import com.circaso.ops.domain.repositories.AuditRepository
import com.circaso.shared.UserId
import com.circaso.users.api.ProfileAdmin

/**
 * How people are shown to staff, shared by the user use cases: an account from identity, its profile from users, and,
 * while it is suspended, the suspension from the audit log (when, why, by whom), which is where ops keeps it.
 */
class UserViews(
    private val accounts: AccountAdmin,
    private val profiles: ProfileAdmin,
    private val audit: AuditRepository,
) {
    suspend fun account(userId: UserId): UserAccount? {
        val account = accounts.account(userId) ?: return null
        val profile = profiles.profiles(setOf(userId))[userId]
        val suspension =
            if (account.status == AccountStatus.SUSPENDED) {
                audit.latestSucceeded(AuditAction.USER_SUSPEND.wire, userId.toString())?.let {
                    Suspension(since = it.at, reason = it.reason, byEmail = it.actorEmail)
                }
            } else {
                null
            }
        return UserAccount(account, profile, suspension)
    }

    /** Rows for [ids] in the order given; ids with no account are left out. */
    suspend fun rows(ids: List<UserId>): List<UserSummary> {
        if (ids.isEmpty()) return emptyList()
        val unique = ids.toSet()
        val found = accounts.accounts(unique)
        val named = profiles.profiles(unique)
        return ids.distinct().mapNotNull { id ->
            val account = found[id] ?: return@mapNotNull null
            val profile = named[id]
            UserSummary(
                userId = id,
                displayName = profile?.displayName,
                handle = profile?.handle,
                status = account.status,
                kind = account.kind,
                maskedPhone = account.maskedPhone,
                createdAt = account.createdAt,
            )
        }
    }

    /** Display names for [ids] that have a profile. */
    suspend fun names(ids: Set<UserId>): Map<UserId, String> =
        if (ids.isEmpty()) emptyMap() else profiles.profiles(ids).mapValues { it.value.displayName }
}
