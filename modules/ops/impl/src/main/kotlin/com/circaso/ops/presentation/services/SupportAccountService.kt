package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.SupportAccountEntry
import com.circaso.ops.domain.models.SupportAccountList
import com.circaso.ops.domain.usecases.CreateSupportAccount
import com.circaso.ops.domain.usecases.ListSupportAccounts
import com.circaso.shared.Resource

class SupportAccountService(
    private val listSupportAccounts: ListSupportAccounts,
    private val createSupportAccount: CreateSupportAccount,
) {
    suspend fun list(cursor: String?, limit: Int): Resource<SupportAccountList> = listSupportAccounts(cursor, limit)

    suspend fun create(
        actor: Actor,
        displayName: String,
        handle: String,
    ): Resource<SupportAccountEntry> = createSupportAccount(actor, displayName, handle)
}
