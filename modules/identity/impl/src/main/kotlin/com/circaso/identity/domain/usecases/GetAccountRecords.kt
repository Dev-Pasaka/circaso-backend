package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.models.AccountRecord
import com.circaso.identity.domain.repositories.AccountAdminRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * Staff (decision 2026-11-08): the accounts among [ids] that exist, whatever their status or kind, each with its
 * Google and Apple logins. Two reads however many ids, so a page of search results costs the same as one account.
 */
class GetAccountRecords(private val admin: AccountAdminRepository) {
    suspend operator fun invoke(ids: Set<UserId>): Resource<Map<UserId, AccountRecord>> {
        if (ids.isEmpty()) return Resource.success(emptyMap())
        val wanted = ids.take(MAX_IDS).toSet()
        val accounts = admin.accounts(wanted)
        val links = admin.links(accounts.map { it.userId }.toSet())
        return Resource.success(
            accounts.associate { it.userId to AccountRecord(it, links[it.userId].orEmpty()) },
        )
    }

    private companion object {
        /** A page of search results at most; the ops module never asks for more at once. */
        const val MAX_IDS = 500
    }
}
