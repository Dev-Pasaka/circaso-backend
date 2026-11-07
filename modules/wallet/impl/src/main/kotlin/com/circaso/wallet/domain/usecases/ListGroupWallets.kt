package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.wallet.domain.models.GroupWalletKey
import com.circaso.wallet.domain.models.GroupWalletResults
import com.circaso.wallet.domain.repositories.WalletReportRepository
import com.circaso.wallet.domain.support.StaffCursors

/** Staff: every group wallet, newest first (decision 2026-11-08), by keyset on `(created_at, wallet_id)`. */
class ListGroupWallets(private val reports: WalletReportRepository) {
    suspend operator fun invoke(cursor: String?, limit: Int): Resource<GroupWalletResults> {
        val after =
            cursor?.let {
                StaffCursors.groupWallet(it) ?: return Resource.error(SharedError.Malformed("cursor", "invalid"))
            }
        val page = limit.coerceIn(1, MAX_PAGE)
        val found = reports.groupWallets(after, page + 1)
        val shown = found.take(page)
        val next =
            if (found.size >
                page
            ) {
                shown.last().let { StaffCursors.encode(GroupWalletKey(it.createdAt, it.walletId.value)) }
            } else {
                null
            }
        return Resource.success(GroupWalletResults(shown, next))
    }

    private companion object {
        const val MAX_PAGE = 200
    }
}
