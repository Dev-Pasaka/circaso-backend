package com.circaso.ramp.presentation.services

import com.circaso.integrations.api.KycEnvironment
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.models.Block
import com.circaso.ramp.domain.models.BlockReason
import com.circaso.ramp.domain.models.MerchantRecord
import com.circaso.ramp.domain.models.MerchantSummary
import com.circaso.ramp.domain.models.OrderView
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.PreviewAccess
import com.circaso.ramp.domain.models.RampOverview
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.SettingsChange
import com.circaso.ramp.domain.models.StaffOrder
import com.circaso.ramp.domain.models.StaffOrderFilter
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.models.UserVerifications
import com.circaso.ramp.domain.models.Verification
import com.circaso.ramp.domain.usecases.BlockUser
import com.circaso.ramp.domain.usecases.DecideMerchant
import com.circaso.ramp.domain.usecases.GetMerchantSummary
import com.circaso.ramp.domain.usecases.GetMerchantVerification
import com.circaso.ramp.domain.usecases.GetRampOverview
import com.circaso.ramp.domain.usecases.GetSettings
import com.circaso.ramp.domain.usecases.GetUserVerifications
import com.circaso.ramp.domain.usecases.ListBlocks
import com.circaso.ramp.domain.usecases.ListCollectionProviders
import com.circaso.ramp.domain.usecases.ListKycProviders
import com.circaso.ramp.domain.usecases.ListMerchants
import com.circaso.ramp.domain.usecases.ListPreviewAccess
import com.circaso.ramp.domain.usecases.ListReviewOrders
import com.circaso.ramp.domain.usecases.ListStaffMerchantOrders
import com.circaso.ramp.domain.usecases.ResetMerchantVerification
import com.circaso.ramp.domain.usecases.ResetVerification
import com.circaso.ramp.domain.usecases.SetPreviewAccess
import com.circaso.ramp.domain.usecases.UnblockUser
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
    private val getRampOverview: GetRampOverview,
    private val getMerchantSummary: GetMerchantSummary,
    private val listStaffMerchantOrders: ListStaffMerchantOrders,
    private val listKycProviders: ListKycProviders,
    private val getUserVerifications: GetUserVerifications,
    private val getMerchantVerification: GetMerchantVerification,
    private val listBlocks: ListBlocks,
    private val blockUser: BlockUser,
    private val unblockUser: UnblockUser,
    private val resetVerification: ResetVerification,
    private val resetMerchantVerificationUseCase: ResetMerchantVerification,
) {
    suspend fun kycProviders(): Resource<List<String>> = listKycProviders()

    suspend fun userVerifications(userId: UserId): Resource<UserVerifications> = getUserVerifications(userId)

    suspend fun merchantVerification(merchantId: MerchantId, network: Network): Resource<Verification?> =
        getMerchantVerification(merchantId, network)

    suspend fun blocks(after: PageCursor?, limit: Int): Resource<List<Block>> = listBlocks(after, limit)

    suspend fun block(
        userId: UserId,
        note: String,
        by: String,
    ): Resource<Block> = blockUser(userId, BlockReason.OPS, note, by)

    suspend fun unblock(userId: UserId): Resource<Boolean> = unblockUser(userId)

    suspend fun resetUserVerification(userId: UserId, environment: KycEnvironment): Resource<Boolean> =
        resetVerification(Subject.user(userId), environment)

    suspend fun resetMerchantVerification(merchantId: MerchantId, environment: KycEnvironment): Resource<Boolean> =
        resetMerchantVerificationUseCase(merchantId, environment)

    suspend fun overview(network: Network, period: Period?): Resource<RampOverview> = getRampOverview(network, period)

    suspend fun merchantSummary(
        merchantId: MerchantId,
        network: Network,
        period: Period?,
    ): Resource<MerchantSummary> = getMerchantSummary(merchantId, network, period)

    suspend fun merchantOrders(
        filter: StaffOrderFilter,
        after: PageCursor?,
        limit: Int,
    ): Resource<List<StaffOrder>> = listStaffMerchantOrders(filter, after, limit)

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
