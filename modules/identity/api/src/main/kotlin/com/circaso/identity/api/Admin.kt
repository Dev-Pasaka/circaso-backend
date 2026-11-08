package com.circaso.identity.api

import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant
import java.time.LocalDate

// Identity's staff-facing contract (decision 2026-11-08), for the ops module behind the ops gateway. Never reachable
// from the app gateway. Phones and emails go out masked: staff confirm a person by what they say, not by reading it.

/** A linked Google or Apple sign-in, the email masked (`am•••@gmail.com`). */
data class AdminSignIn(val provider: String, val maskedEmail: String?, val linkedAt: Instant)

/** An account as staff see it. [verification] is `NONE`, `PENDING` or `VERIFIED`. */
data class AdminAccount(
    val userId: UserId,
    val kind: AccountKind,
    val status: AccountStatus,
    val maskedPhone: String?,
    val verification: String,
    val createdAt: Instant,
    val signIns: List<AdminSignIn>,
)

/** One phone the account registered. [status] is `ACTIVE`, `REPLACED` or `REMOVED`; [active] is the account's current one. */
data class AdminDevice(
    val deviceId: DeviceId,
    val platform: String,
    val model: String,
    val appVersion: String,
    val status: String,
    val registeredAt: Instant,
    val lastSeenAt: Instant,
    val active: Boolean,
)

/** One session (not expired). [status] is `ACTIVE` or `REVOKED`; [scope] the token scope (`app`). */
data class AdminSession(
    val sessionId: SessionId,
    val deviceId: DeviceId,
    val scope: String,
    val status: String,
    val createdAt: Instant,
    val rotatedAt: Instant,
    val expiresAt: Instant,
)

/** A page of accounts, newest first; [nextCursor] null on the last page. */
data class AccountPage(val accounts: List<AdminAccount>, val nextCursor: String?)

/** How many on one UTC day. */
data class DayCount(val date: LocalDate, val count: Long)

/**
 * People who signed up: [total] all time, [inRange] between the dates (both included, UTC days) and one [series] entry
 * per day of the range, zeros included. Counts person accounts that finished sign-up (not `PENDING_PHONE`) and aren't
 * deleted; support accounts never count.
 */
data class SignUpStats(val total: Long, val inRange: Long, val series: List<DayCount>)

/** Distinct people whose phone was last seen within a day, 7 days and 30 days of now. */
data class ActiveCounts(val day1: Long, val day7: Long, val day30: Long)

interface AccountAdmin {
    suspend fun account(userId: UserId): AdminAccount?

    /** Those of [ids] that exist; for search results. */
    suspend fun accounts(ids: Set<UserId>): Map<UserId, AdminAccount>

    /** The account on [phone] (E.164), if any. */
    suspend fun findByPhone(phone: PhoneNumber): UserId?

    /** Accounts with a Google or Apple sign-in on [email], compared case-insensitively. */
    suspend fun findByEmail(email: String): List<UserId>

    /** Every account, newest first. Error: `request.malformed` for a cursor that isn't one. [limit] 1-200. */
    suspend fun newest(cursor: String?, limit: Int): Resource<AccountPage>

    /** The account's phones, newest first. */
    suspend fun devices(userId: UserId): List<AdminDevice>

    /** Sessions not yet expired, newest first, at most [limit]. */
    suspend fun sessions(userId: UserId, limit: Int): List<AdminSession>

    /**
     * Suspends the account: `SUSPENDED`, every session revoked at once (the deny-list too, so access tokens stop now),
     * a security log line and `identity.AccountStatusChanged`, which closes the sockets, hides the profile and, through
     * the wallet's sender rule, blocks payments (`wallet.account_restricted`). Sign-in then answers
     * `identity.account_suspended`. Errors: `identity.account_not_found`, `identity.account_not_active` (only an `ACTIVE`
     * account can be suspended).
     */
    suspend fun suspend(userId: UserId): Resource<AdminAccount>

    /**
     * Gives a suspended account back: `ACTIVE`, a security log line and `AccountStatusChanged`. The person signs in
     * again (their sessions stay revoked). Errors: `identity.account_not_found`, `identity.account_not_suspended`.
     */
    suspend fun unsuspend(userId: UserId): Resource<AdminAccount>

    /** [from] and [to] are UTC days, both included; at most 366 days apart. */
    suspend fun signUps(from: LocalDate, to: LocalDate): SignUpStats

    suspend fun activeUsers(now: Instant): ActiveCounts
}

/** A Circaso support account (decision 2026-10-31), with its name and handle from users. */
data class SupportAccountView(
    val userId: UserId,
    val displayName: String,
    val handle: String?,
    val createdAt: Instant,
)

data class SupportAccountPage(val accounts: List<SupportAccountView>, val nextCursor: String?)

/**
 * OPS1 and OPS2, moved off the app gateway to the ops gateway (decision 2026-11-08): the ops module serves the routes
 * (ADMIN only, audited) and identity keeps the rules.
 */
interface SupportAccounts {
    /** Errors: `users.handle_invalid`, `users.handle_taken`, `request.malformed`. [createdBy] is the staff id, for the log. */
    suspend fun create(
        displayName: String,
        handle: String,
        createdBy: String,
    ): Resource<SupportAccountView>

    suspend fun list(cursor: String?, limit: Int): Resource<SupportAccountPage>
}
