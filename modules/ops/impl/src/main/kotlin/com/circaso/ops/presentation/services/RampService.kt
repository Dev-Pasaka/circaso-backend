package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.usecases.DecideRampMerchant
import com.circaso.ops.domain.usecases.GetRampMerchants
import com.circaso.ops.domain.usecases.GetRampPreviewAccess
import com.circaso.ops.domain.usecases.GetRampReviewOrders
import com.circaso.ops.domain.usecases.GetRampSettings
import com.circaso.ops.domain.usecases.SetRampPreviewAccess
import com.circaso.ops.domain.usecases.UpdateRampSettings
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsMerchant
import com.circaso.ramp.api.OpsRampOrder
import com.circaso.ramp.api.OpsRampPreviewAccess
import com.circaso.ramp.api.OpsRampSettings
import com.circaso.ramp.api.OpsRampSettingsChange
import com.circaso.ramp.api.RampOrderId
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** The ramp for staff (decision 2026-11-22): merchants, settings, the preview list and the orders in review. */
class RampService(
    private val getRampMerchants: GetRampMerchants,
    private val decideRampMerchant: DecideRampMerchant,
    private val getRampSettings: GetRampSettings,
    private val updateRampSettings: UpdateRampSettings,
    private val getRampPreviewAccess: GetRampPreviewAccess,
    private val setRampPreviewAccess: SetRampPreviewAccess,
    private val getRampReviewOrders: GetRampReviewOrders,
) {
    suspend fun merchants(before: MerchantId?, limit: Int): Resource<List<OpsMerchant>> =
        getRampMerchants(before, limit)

    suspend fun decide(
        actor: Actor,
        merchantId: MerchantId,
        approve: Boolean,
        reason: String,
    ): Resource<OpsMerchant> = decideRampMerchant(actor, merchantId, approve, reason)

    suspend fun settings(network: Network): Resource<OpsRampSettings> = getRampSettings(network)

    suspend fun updateSettings(
        actor: Actor,
        settings: OpsRampSettings,
        reason: String,
    ): Resource<OpsRampSettingsChange> = updateRampSettings(actor, settings, reason)

    suspend fun previewAccess(network: Network): Resource<List<OpsRampPreviewAccess>> = getRampPreviewAccess(network)

    suspend fun setPreviewAccess(
        actor: Actor,
        userId: UserId,
        network: Network,
        enabled: Boolean,
        reason: String,
    ): Resource<List<OpsRampPreviewAccess>> = setRampPreviewAccess(actor, userId, network, enabled, reason)

    suspend fun reviewOrders(
        network: Network?,
        after: RampOrderId?,
        limit: Int,
    ): Resource<List<OpsRampOrder>> = getRampReviewOrders(network, after, limit)
}
