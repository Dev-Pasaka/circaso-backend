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
 * `accounts_newest_idx`, never by offset. Suspended and support accounts are listed too: staff look for them.
 * Unfinished sign-ups (`PENDING_PHONE`) only with [includePending] (2026-11-13): most are Google Play's test robots,
 * which sign in with Google and stop at the phone screen, so by default they'd bury the real people.
 * [verifiedOnly] (the verified badge, 2026-11-24): only verified accounts, through `accounts_verified_idx` (V11).
 */
class ListNewestAccounts(private val admin: AccountAdminRepository) {
    suspend operator fun invoke(
        cursor: String?,
        limit: Int,
        includePending: Boolean,
        verifiedOnly: Boolean = false,
    ): Resource<AccountRecordPage> {
        val after =
            cursor?.let {
                AdminCursors.decode(it) ?: return Resource.error(SharedError.Malformed("cursor", "invalid"))
            }
        val page = limit.coerceIn(1, MAX_PAGE)
        val found = admin.newest(after, page + 1, includePending, verifiedOnly)
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
