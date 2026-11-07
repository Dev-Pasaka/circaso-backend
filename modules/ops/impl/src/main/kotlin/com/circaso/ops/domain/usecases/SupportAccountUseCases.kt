package com.circaso.ops.domain.usecases

import com.circaso.identity.api.SupportAccountView
import com.circaso.identity.api.SupportAccounts
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.models.SupportAccountRow
import com.circaso.ops.domain.models.SupportAccountRows
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.shared.Resource
import com.circaso.shared.map

// OPS1 and OPS2 (decision 2026-10-31), moved from the app gateway to the ops gateway on 2026-11-08. ADMIN only.

private fun SupportAccountView.row() = SupportAccountRow(userId, displayName, handle, createdAt)

class ListSupportAccounts(private val accounts: SupportAccounts) {
    suspend operator fun invoke(cursor: String?, limit: Int): Resource<SupportAccountRows> =
        accounts.list(cursor, limit).map { page -> SupportAccountRows(page.accounts.map { it.row() }, page.nextCursor) }
}

class CreateSupportAccount(private val accounts: SupportAccounts, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        displayName: String,
        handle: String,
    ): Resource<SupportAccountRow> =
        trail
            .record(actor, AuditAction.SUPPORT_ACCOUNT_CREATE, targetId = null, detail = "handle @${handle.trim()}") {
                accounts.create(displayName, handle, createdBy = actor.staffId.toString())
            }.map { it.row() }
}
