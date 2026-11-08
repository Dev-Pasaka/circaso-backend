package com.circaso.identity.domain.usecases

import com.circaso.identity.api.SupportProfiles
import com.circaso.identity.domain.models.SupportAccount
import com.circaso.identity.domain.models.SupportAccountPage
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId

/**
 * OPS2, served by the ops module on the ops gateway (decision 2026-11-08): every support account, oldest first, with
 * its name and handle from users.
 */
class ListSupportAccounts(private val accounts: AccountRepository, private val profiles: SupportProfiles) {
    suspend operator fun invoke(cursor: String?, limit: Int): Resource<SupportAccountPage> {
        val after =
            cursor?.let {
                UserId.parse(it) ?: return Resource.error(SharedError.Malformed("cursor", "invalid"))
            }
        val page = limit.coerceIn(1, MAX_PAGE)
        val found = accounts.supportAccounts(after, page + 1)
        val shown = found.take(page)
        val described = profiles.describe(shown.map { it.userId }.toSet())
        val listed =
            shown.map {
                val profile = described[it.userId]
                SupportAccount(it, profile?.displayName, profile?.handle)
            }
        val next = if (found.size > page) shown.last().userId.toString() else null
        return Resource.success(SupportAccountPage(listed, next))
    }

    private companion object {
        const val MAX_PAGE = 200
    }
}
