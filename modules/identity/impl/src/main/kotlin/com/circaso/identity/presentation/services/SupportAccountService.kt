package com.circaso.identity.presentation.services

import com.circaso.identity.domain.models.CreateSupportAccountCommand
import com.circaso.identity.domain.models.SupportAccount
import com.circaso.identity.domain.models.SupportAccountPage
import com.circaso.identity.domain.usecases.CreateSupportAccount
import com.circaso.identity.domain.usecases.ListSupportAccounts
import com.circaso.shared.Resource

/** Circaso's support accounts (decision 2026-10-31), for the ops module behind the ops gateway (decision 2026-11-08). */
class SupportAccountService(
    private val createSupportAccount: CreateSupportAccount,
    private val listSupportAccounts: ListSupportAccounts,
) {
    suspend fun create(command: CreateSupportAccountCommand): Resource<SupportAccount> = createSupportAccount(command)

    suspend fun list(cursor: String?, limit: Int): Resource<SupportAccountPage> = listSupportAccounts(cursor, limit)
}
