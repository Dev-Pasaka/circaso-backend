package com.circaso.ramp.domain.usecases

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.TopUp
import com.circaso.ramp.domain.models.TopUpCommand
import com.circaso.ramp.domain.support.MerchantTopUps
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.util.UUID

/**
 * RM19 `POST /ramp/merchant/topups`: an M-Pesa prompt to the merchant's own contact phone to fill one of their two
 * wallets at the provider (2026-11-23). See [MerchantTopUps].
 */
class StartTopUp(private val topUps: MerchantTopUps) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        command: TopUpCommand,
    ): Resource<TopUp> = topUps.start(merchantId, network, command)
}

/** RM19 `GET /ramp/merchant/topups/{topUpId}`: one top-up, asking the provider first while it is open. */
class GetTopUp(private val topUps: MerchantTopUps) {
    suspend operator fun invoke(merchantId: MerchantId, topUpId: UUID): Resource<TopUp> =
        topUps.view(merchantId, topUpId)
}

/** RM19 `GET /ramp/merchant/topups`: the merchant's latest top-ups, newest first. */
class ListTopUps(private val topUps: MerchantTopUps) {
    suspend operator fun invoke(merchantId: MerchantId, limit: Int): Resource<List<TopUp>> =
        Resource.success(topUps.recent(merchantId, limit))
}
