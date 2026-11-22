package com.circaso.ramp.presentation.services

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.models.MerchantRecord
import com.circaso.ramp.domain.models.OrderView
import com.circaso.ramp.domain.models.PreviewAccess
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.SettingsChange
import com.circaso.ramp.domain.usecases.DecideMerchant
import com.circaso.ramp.domain.usecases.GetSettings
import com.circaso.ramp.domain.usecases.ListCollectionProviders
import com.circaso.ramp.domain.usecases.ListMerchants
import com.circaso.ramp.domain.usecases.ListPreviewAccess
import com.circaso.ramp.domain.usecases.ListReviewOrders
import com.circaso.ramp.domain.usecases.SetPreviewAccess
import com.circaso.ramp.domain.usecases.UpdateSettings
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** What staff ask the ramp, behind `ramp:api`'s `RampAdmin`. No logic of its own: each method is one use case. */
class RampAdminService(
    private val listMerchants: ListMerchants,
    private val decideMerchant: DecideMerchant,
    private val getSettings: GetSettings,
    private val updateSettingsUseCase: UpdateSettings,
    private val listPreviewAccess: ListPreviewAccess,
    private val setPreviewAccessUseCase: SetPreviewAccess,
    private val listReviewOrders: ListReviewOrders,
    private val listCollectionProviders: ListCollectionProviders,
) {
    suspend fun merchants(before: MerchantId?, limit: Int): Resource<List<MerchantRecord>> =
        listMerchants(before, limit)

    suspend fun decide(
        merchantId: MerchantId,
        approve: Boolean,
        by: String,
    ): Resource<MerchantRecord> = decideMerchant(merchantId, approve, by)

    suspend fun settings(network: Network): Resource<RampSettings> = getSettings(network)

    suspend fun collectionProviders(): Resource<List<String>> = listCollectionProviders()

    suspend fun updateSettings(
        settings: RampSettings,
        expectedVersion: Long?,
        by: String,
    ): Resource<SettingsChange> = updateSettingsUseCase(settings, expectedVersion, by)

    suspend fun previewAccess(network: Network): Resource<List<PreviewAccess>> = listPreviewAccess(network)

    suspend fun setPreviewAccess(
        userId: UserId,
        network: Network,
        enabled: Boolean,
        by: String,
    ): Resource<Boolean> = setPreviewAccessUseCase(userId, network, enabled, by)

    suspend fun ordersInReview(
        network: Network?,
        after: RampOrderId?,
        limit: Int,
    ): Resource<List<OrderView>> = listReviewOrders(network, after, limit)
}
