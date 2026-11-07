package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.TransferQuery
import com.circaso.wallet.domain.models.TransferResults
import com.circaso.wallet.domain.support.StaffTransferPages

/** Staff: the transfers a person sent or received, newest first (decision 2026-11-08), by keyset, never by offset. */
class ListTransfersOf(private val pages: StaffTransferPages) {
    suspend operator fun invoke(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<TransferResults> = pages.page(TransferQuery(), userId, cursor, limit)
}
