package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.repositories.WalletReportRepository

/**
 * Staff: the fees a person paid, all time, in micro-dollars (decision 2026-11-08): the fee legs that were collected
 * (`COLLECTED`), at the dollar price each was charged at. A quoted fee whose payment never went through doesn't count.
 */
class SumFeesPaid(private val reports: WalletReportRepository) {
    suspend operator fun invoke(userId: UserId): Resource<Long> = Resource.success(reports.feesPaidBy(userId))
}
