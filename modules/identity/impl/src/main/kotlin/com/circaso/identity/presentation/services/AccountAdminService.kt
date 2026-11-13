package com.circaso.identity.presentation.services

import com.circaso.identity.api.ActiveCounts
import com.circaso.identity.api.SignUpStats
import com.circaso.identity.domain.models.AccountRecord
import com.circaso.identity.domain.models.AccountRecordPage
import com.circaso.identity.domain.models.DeviceRecord
import com.circaso.identity.domain.models.SessionRecord
import com.circaso.identity.domain.usecases.CountActiveUsers
import com.circaso.identity.domain.usecases.CountPendingSignUps
import com.circaso.identity.domain.usecases.CountSignUps
import com.circaso.identity.domain.usecases.DeletePendingSignUps
import com.circaso.identity.domain.usecases.FindAccountByPhone
import com.circaso.identity.domain.usecases.FindAccountsByEmail
import com.circaso.identity.domain.usecases.GetAccountRecords
import com.circaso.identity.domain.usecases.ListAccountDevices
import com.circaso.identity.domain.usecases.ListAccountSessions
import com.circaso.identity.domain.usecases.ListNewestAccounts
import com.circaso.identity.domain.usecases.SuspendAccount
import com.circaso.identity.domain.usecases.UnsuspendAccount
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant
import java.time.LocalDate

/** Staff on the ops gateway (decision 2026-11-08): accounts, their phones and sessions, suspension and the counts. */
class AccountAdminService(
    private val getAccountRecords: GetAccountRecords,
    private val findAccountByPhone: FindAccountByPhone,
    private val findAccountsByEmail: FindAccountsByEmail,
    private val listNewestAccounts: ListNewestAccounts,
    private val listAccountDevices: ListAccountDevices,
    private val listAccountSessions: ListAccountSessions,
    private val suspendAccount: SuspendAccount,
    private val unsuspendAccount: UnsuspendAccount,
    private val countSignUps: CountSignUps,
    private val countActiveUsers: CountActiveUsers,
    private val countPendingSignUps: CountPendingSignUps,
    private val deletePendingSignUps: DeletePendingSignUps,
) {
    suspend fun accounts(ids: Set<UserId>): Resource<Map<UserId, AccountRecord>> = getAccountRecords(ids)

    suspend fun findByPhone(phone: PhoneNumber): Resource<UserId?> = findAccountByPhone(phone)

    suspend fun findByEmail(email: String): Resource<List<UserId>> = findAccountsByEmail(email)

    suspend fun newest(
        cursor: String?,
        limit: Int,
        includePending: Boolean,
    ): Resource<AccountRecordPage> = listNewestAccounts(cursor, limit, includePending)

    suspend fun pendingSignUps(): Resource<Long> = countPendingSignUps()

    suspend fun deletePendingSignUps(): Resource<Int> = deletePendingSignUps.invoke()

    suspend fun devices(userId: UserId): Resource<List<DeviceRecord>> = listAccountDevices(userId)

    suspend fun sessions(userId: UserId, limit: Int): Resource<List<SessionRecord>> = listAccountSessions(userId, limit)

    suspend fun suspend(userId: UserId): Resource<AccountRecord> = suspendAccount(userId)

    suspend fun unsuspend(userId: UserId): Resource<AccountRecord> = unsuspendAccount(userId)

    suspend fun signUps(from: LocalDate, to: LocalDate): Resource<SignUpStats> = countSignUps(from, to)

    suspend fun activeUsers(now: Instant): Resource<ActiveCounts> = countActiveUsers(now)
}
