package com.circaso.identity.domain.repositories

import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.AccountKey
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.models.SessionRecord
import com.circaso.shared.UserId
import java.time.Instant
import java.time.LocalDate

/**
 * Staff reads (decision 2026-11-08). They run in the ops process on the app's own pools, so each one is a short
 * keyset page or a bounded aggregate served by an index (V3__admin_reads); nothing here writes.
 */
interface AccountAdminRepository {
    /** Those of [ids] that exist, any status and kind. */
    suspend fun accounts(ids: Set<UserId>): List<Account>

    /** The Google and Apple logins of each of [ids], in one read. */
    suspend fun links(ids: Set<UserId>): Map<UserId, List<ProviderLink>>

    /** Accounts with a login on [email], compared lowercased (`provider_links_email_idx`), at most [limit]. */
    suspend fun byEmail(email: String, limit: Int): List<UserId>

    /**
     * Every account newest first, strictly after [after] in `(created_at, user_id) DESC` order, at most [limit]; without
     * the unfinished sign-ups (`PENDING_PHONE`) unless [includePending].
     */
    suspend fun newest(
        after: AccountKey?,
        limit: Int,
        includePending: Boolean,
    ): List<Account>

    /** Unfinished sign-ups (`PENDING_PHONE`) started before [createdBefore] (`accounts_pending_idx`). */
    suspend fun pendingCount(createdBefore: Instant): Long

    /** Every phone the account registered, newest first. */
    suspend fun devices(userId: UserId): List<Device>

    /** Sessions that haven't run out at [now], newest first (`sessions_user_idx`), at most [limit]. */
    suspend fun sessions(
        userId: UserId,
        now: Instant,
        limit: Int,
    ): List<SessionRecord>

    /** Every person who finished sign-up and isn't deleted. */
    suspend fun signUpTotal(): Long

    /** The same people by the UTC day they signed up, for accounts created in [from, until); days with none absent. */
    suspend fun signUpsByDay(from: Instant, until: Instant): Map<LocalDate, Long>

    /** Distinct accounts with an active phone last seen at or after [since] (`devices_seen_idx`). */
    suspend fun activeSince(since: Instant): Long
}
