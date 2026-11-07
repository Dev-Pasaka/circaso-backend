package com.circaso.wallet.presentation.services

import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.CoinIcon
import com.circaso.wallet.domain.usecases.GetCoinIcon

/** Coin icons, IC1. */
class CoinIconService(private val getCoinIcon: GetCoinIcon) {
    suspend fun icon(symbol: String): Resource<CoinIcon> = getCoinIcon(symbol)
}
