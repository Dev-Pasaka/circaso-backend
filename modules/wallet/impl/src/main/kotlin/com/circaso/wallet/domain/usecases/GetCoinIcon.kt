package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.CoinIcon
import com.circaso.wallet.domain.repositories.CoinIconRepository

/**
 * IC1 `GET /assets/icons/{symbol}`: a coin's mono mark for the app to draw when it doesn't bundle one. The symbol is
 * matched case-insensitively and must pass the allow-list (`[A-Z0-9.]{1,15}` once upper-cased) before anything is
 * looked up, so no other path can be reached through it.
 */
class GetCoinIcon(private val icons: CoinIconRepository) {
    suspend operator fun invoke(symbol: String): Resource<CoinIcon> {
        val key = symbol.uppercase()
        if (!SYMBOL.matches(key)) return Resource.error(WalletError.IconSymbolInvalid)
        return icons.find(key)?.let { Resource.success(it) } ?: Resource.error(WalletError.IconNotFound)
    }

    private companion object {
        val SYMBOL = Regex("[A-Z0-9.]{1,15}")
    }
}
