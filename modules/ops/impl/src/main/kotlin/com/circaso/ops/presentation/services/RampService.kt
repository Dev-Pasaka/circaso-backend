package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.RampMerchantOrdersQuery
import com.circaso.ops.domain.usecases.BlockRampUser
import com.circaso.ops.domain.usecases.DecideRampMerchant
import com.circaso.ops.domain.usecases.GetRampBlocks
import com.circaso.ops.domain.usecases.GetRampMerchantOrders
import com.circaso.ops.domain.usecases.GetRampMerchantSummary
import com.circaso.ops.domain.usecases.GetRampMerchants
import com.circaso.ops.domain.usecases.GetRampOverview
import com.circaso.ops.domain.usecases.GetRampPreviewAccess
import com.circaso.ops.domain.usecases.GetRampReviewOrders
import com.circaso.ops.domain.usecases.GetRampSettings
import com.circaso.ops.domain.usecases.GetRampUserVerification
import com.circaso.ops.domain.usecases.ResetRampMerchantVerification
import com.circaso.ops.domain.usecases.ResetRampUserVerification
import com.circaso.ops.domain.usecases.SetRampPreviewAccess
import com.circaso.ops.domain.usecases.UnblockRampUser
import com.circaso.ops.domain.usecases.UpdateRampSettings
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsMerchant
import com.circaso.ramp.api.OpsRampBlock
import com.circaso.ramp.api.OpsRampBlockCursor
import com.circaso.ramp.api.OpsRampMerchantOrder
import com.circaso.ramp.api.OpsRampMerchantSummary
import com.circaso.ramp.api.OpsRampOrder
import com.circaso.ramp.api.OpsRampOverview
import com.circaso.ramp.api.OpsRampPeriod
import com.circaso.ramp.api.OpsRampPreviewAccess
import com.circaso.ramp.api.OpsRampSettings
import com.circaso.ramp.api.OpsRampSettingsChange
import com.circaso.ramp.api.OpsRampUserVerification
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
    private val getRampOverview: GetRampOverview,
    private val getRampMerchantSummary: GetRampMerchantSummary,
    private val getRampMerchantOrders: GetRampMerchantOrders,
    private val getRampUserVerification: GetRampUserVerification,
    private val getRampBlocks: GetRampBlocks,
    private val blockRampUser: BlockRampUser,
    private val unblockRampUser: UnblockRampUser,
    private val resetRampUserVerification: ResetRampUserVerification,
    private val resetRampMerchantVerification: ResetRampMerchantVerification,
) {
    suspend fun userVerification(userId: UserId): Resource<OpsRampUserVerification> = getRampUserVerification(userId)

    suspend fun blocks(after: OpsRampBlockCursor?, limit: Int): Resource<List<OpsRampBlock>> =
        getRampBlocks(after, limit)

    suspend fun block(
        actor: Actor,
        userId: UserId,
        reason: String,
    ): Resource<OpsRampBlock> = blockRampUser(actor, userId, reason)

    suspend fun unblock(
        actor: Actor,
        userId: UserId,
        reason: String?,
    ): Resource<OpsRampUserVerification> = unblockRampUser(actor, userId, reason)

    suspend fun resetUserVerification(
        actor: Actor,
        userId: UserId,
        environment: String,
        reason: String,
    ): Resource<OpsRampUserVerification> = resetRampUserVerification(actor, userId, environment, reason)

    suspend fun resetMerchantVerification(
        actor: Actor,
        merchantId: MerchantId,
        environment: String,
        reason: String,
    ): Resource<Boolean> = resetRampMerchantVerification(actor, merchantId, environment, reason)

    suspend fun overview(network: Network, period: OpsRampPeriod?): Resource<OpsRampOverview> =
        getRampOverview(network, period)

    suspend fun merchantSummary(
        merchantId: MerchantId,
        network: Network,
        period: OpsRampPeriod?,
    ): Resource<OpsRampMerchantSummary> = getRampMerchantSummary(merchantId, network, period)

    suspend fun merchantOrders(query: RampMerchantOrdersQuery, limit: Int): Resource<List<OpsRampMerchantOrder>> =
        getRampMerchantOrders(query, limit)

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
