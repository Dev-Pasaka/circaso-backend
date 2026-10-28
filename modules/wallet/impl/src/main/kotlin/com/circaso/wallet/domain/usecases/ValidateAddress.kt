package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Resource
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.AddressCheck
import com.circaso.wallet.domain.support.Addresses
import com.circaso.wallet.domain.support.orCustodyUnavailable

/**
 * W11 `POST /wallet/addresses/validate`: whether an outside address is valid on [blockchain] (decision 2026-10-28:
 * the chain is named, and paying an address needs it to be valid on that same chain), asked of Circle. An invalid
 * address is an answer (`valid: false`), not an error.
 */
class ValidateAddress(private val custody: StablecoinCustody) {
    suspend operator fun invoke(blockchain: String, address: String): Resource<AddressCheck> {
        custody.supported.find(blockchain) ?: return Resource.error(WalletError.ChainUnsupported)
        return when (val checked = custody.validateAddress(blockchain, address).orCustodyUnavailable()) {
            is Resource.Success -> {
                Resource.success(
                    AddressCheck(blockchain, Addresses.normalise(address), checked.data),
                )
            }

            is Resource.Error -> {
                checked
            }
        }
    }
}
