package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.SupportAccountRow
import com.circaso.ops.domain.models.SupportAccountRows
import com.circaso.ops.domain.usecases.CreateSupportAccount
import com.circaso.ops.domain.usecases.ListSupportAccounts
import com.circaso.shared.Resource

class SupportAccountService(
    private val listSupportAccounts: ListSupportAccounts,
    private val createSupportAccount: CreateSupportAccount,
) {
    suspend fun list(cursor: String?, limit: Int): Resource<SupportAccountRows> = listSupportAccounts(cursor, limit)

    suspend fun create(
        actor: Actor,
        displayName: String,
        handle: String,
    ): Resource<SupportAccountRow> = createSupportAccount(actor, displayName, handle)
}
