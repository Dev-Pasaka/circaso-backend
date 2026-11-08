package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.models.AccountKey
import com.circaso.identity.domain.models.AccountRecord
import com.circaso.identity.domain.models.AccountRecordPage
import com.circaso.identity.domain.repositories.AccountAdminRepository
import com.circaso.identity.domain.support.AdminCursors
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

/**
 * Staff: every account, newest first (decision 2026-11-08), paged by keyset on `(created_at, user_id)` through
 * `accounts_newest_idx`, never by offset. Pending, suspended and support accounts are listed too: staff look for them.
 */
class ListNewestAccounts(private val admin: AccountAdminRepository) {
    suspend operator fun invoke(cursor: String?, limit: Int): Resource<AccountRecordPage> {
        val after =
            cursor?.let {
                AdminCursors.decode(it) ?: return Resource.error(SharedError.Malformed("cursor", "invalid"))
            }
        val page = limit.coerceIn(1, MAX_PAGE)
        val found = admin.newest(after, page + 1)
        val shown = found.take(page)
        val links = admin.links(shown.map { it.userId }.toSet())
        val next =
            if (found.size > page) {
                shown.last().let { AdminCursors.encode(AccountKey(it.createdAt, it.userId)) }
            } else {
                null
            }
        return Resource.success(
            AccountRecordPage(shown.map { AccountRecord(it, links[it.userId].orEmpty()) }, next),
        )
    }

    private companion object {
        const val MAX_PAGE = 200
    }
}
