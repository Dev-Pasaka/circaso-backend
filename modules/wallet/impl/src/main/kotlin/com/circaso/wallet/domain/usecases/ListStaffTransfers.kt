package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.wallet.api.TransferFilter
import com.circaso.wallet.domain.models.TransferQuery
import com.circaso.wallet.domain.models.TransferResults
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.support.StaffTransferPages

/**
 * Staff: every transfer matching [filter], newest first (decision 2026-11-08). With a person it is their transfers on
 * both sides (two index reads merged); without one, a walk down `transfers_newest_idx` (or the confirmed ones' partial
 * index), the other filters applied on the way. An unknown state is `request.malformed`.
 */
class ListStaffTransfers(private val pages: StaffTransferPages) {
    suspend operator fun invoke(
        filter: TransferFilter,
        cursor: String?,
        limit: Int,
    ): Resource<TransferResults> {
        val state =
            filter.state?.let { wanted ->
                TransferState.entries.firstOrNull { it.name == wanted.trim().uppercase() }
                    ?: return Resource.error(SharedError.Malformed("state", "unknown"))
            }
        val query =
            TransferQuery(
                state = state,
                blockchain = filter.blockchain?.trim()?.takeIf { it.isNotEmpty() },
                asset = filter.asset?.trim()?.takeIf { it.isNotEmpty() },
                from = filter.from,
                until = filter.to,
            )
        return pages.page(query, filter.userId, cursor, limit)
    }
}
