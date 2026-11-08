package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.wallet.domain.repositories.WalletReportRepository

/** Staff: how many group wallets there are (decision 2026-11-08). */
class CountGroupWallets(private val reports: WalletReportRepository) {
    suspend operator fun invoke(): Resource<Long> = Resource.success(reports.groupWalletCount())
}
