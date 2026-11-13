package com.circaso.identity.presentation.api

import com.circaso.identity.api.AccountAdmin
import com.circaso.identity.api.AccountPage
import com.circaso.identity.api.ActiveCounts
import com.circaso.identity.api.AdminAccount
import com.circaso.identity.api.AdminDevice
import com.circaso.identity.api.AdminSession
import com.circaso.identity.api.SignUpStats
import com.circaso.identity.api.SupportAccountPage
import com.circaso.identity.api.SupportAccountView
import com.circaso.identity.api.SupportAccounts
import com.circaso.identity.presentation.mappers.supportAccountCommand
import com.circaso.identity.presentation.mappers.toAdmin
import com.circaso.identity.presentation.mappers.toView
import com.circaso.identity.presentation.services.AccountAdminService
import com.circaso.identity.presentation.services.SupportAccountService
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.flatMap
import com.circaso.shared.getOrNull
import com.circaso.shared.getOrThrow
import com.circaso.shared.map
import java.time.Instant
import java.time.LocalDate

/**
 * identity:api's staff contract (decision 2026-11-08), bound for the ops module only: the app gateway never mounts it.
 * Every call goes through [AccountAdminService], like any other way in; the mapping masks the phone and emails.
 */
class AccountAdminImpl(private val admin: AccountAdminService) : AccountAdmin {
    override suspend fun account(userId: UserId): AdminAccount? = accounts(setOf(userId))[userId]

    override suspend fun accounts(ids: Set<UserId>): Map<UserId, AdminAccount> =
        admin
            .accounts(ids)
            .getOrNull()
            .orEmpty()
            .mapValues { (_, record) -> record.toAdmin() }

    override suspend fun findByPhone(phone: PhoneNumber): UserId? = admin.findByPhone(phone).getOrNull()

    override suspend fun findByEmail(email: String): List<UserId> = admin.findByEmail(email).getOrNull().orEmpty()

    override suspend fun newest(
        cursor: String?,
        limit: Int,
        includePending: Boolean,
    ): Resource<AccountPage> = admin.newest(cursor, limit, includePending).map { it.toAdmin() }

    override suspend fun pendingSignUps(): Long = admin.pendingSignUps().getOrThrow()

    override suspend fun deletePendingSignUps(): Resource<Int> = admin.deletePendingSignUps()

    override suspend fun devices(userId: UserId): List<AdminDevice> =
        admin
            .devices(userId)
            .getOrNull()
            .orEmpty()
            .map { it.toAdmin() }

    override suspend fun sessions(userId: UserId, limit: Int): List<AdminSession> =
        admin
            .sessions(userId, limit)
            .getOrNull()
            .orEmpty()
            .map { it.toAdmin() }

    override suspend fun suspend(userId: UserId): Resource<AdminAccount> = admin.suspend(userId).map { it.toAdmin() }

    override suspend fun unsuspend(userId: UserId): Resource<AdminAccount> =
        admin.unsuspend(userId).map { it.toAdmin() }

    override suspend fun signUps(from: LocalDate, to: LocalDate): SignUpStats = admin.signUps(from, to).getOrThrow()

    override suspend fun activeUsers(now: Instant): ActiveCounts = admin.activeUsers(now).getOrThrow()
}

/** OPS1 and OPS2 for the ops module (decision 2026-11-08), with the shape checks the app-gateway route had. */
class SupportAccountsImpl(private val support: SupportAccountService) : SupportAccounts {
    override suspend fun create(
        displayName: String,
        handle: String,
        createdBy: String,
    ): Resource<SupportAccountView> =
        supportAccountCommand(displayName, handle, createdBy)
            .flatMap { support.create(it) }
            .map { it.toView() }

    override suspend fun list(cursor: String?, limit: Int): Resource<SupportAccountPage> =
        support.list(cursor, limit).map { it.toView() }
}
