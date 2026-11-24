package com.circaso.share.domain.usecases

import com.circaso.share.domain.models.PayLink
import com.circaso.share.domain.support.PayLinks
import com.circaso.shared.Resource

/**
 * A pay link's page (decision 2026-11-24): what the link asks for, checked. Nothing is stored or looked up, so the page
 * shows only what is in the link; no throttle, because there is nothing behind it to protect.
 */
class ReadPayLink {
    suspend operator fun invoke(
        address: String?,
        chain: String?,
        asset: String?,
        amount: String?,
    ): Resource<PayLink> = Resource.success(PayLinks.read(address, chain, asset, amount))
}
