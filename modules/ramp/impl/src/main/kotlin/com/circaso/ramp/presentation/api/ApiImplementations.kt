package com.circaso.ramp.presentation.api

import com.circaso.integrations.api.CollectionNotifications
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsMerchant
import com.circaso.ramp.api.OpsMerchantWallet
import com.circaso.ramp.api.OpsRampOrder
import com.circaso.ramp.api.OpsRampPreviewAccess
import com.circaso.ramp.api.OpsRampSettings
import com.circaso.ramp.api.OpsRampSettingsChange
import com.circaso.ramp.api.Ramp
import com.circaso.ramp.api.RampAdmin
import com.circaso.ramp.api.RampFrames
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.MerchantRecord
import com.circaso.ramp.domain.models.OrderView
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.presentation.services.RampAdminService
import com.circaso.ramp.presentation.services.RampEventsService
import com.circaso.shared.CircasoJson
import com.circaso.shared.Network
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeReplay
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.flatMap
import com.circaso.shared.getOrNull
import com.circaso.shared.map
import java.time.Instant
import java.util.UUID

/** ramp:api. Every call goes through a service, like any other way in. */
class RampImpl(private val events: RampEventsService) : Ramp {
    override suspend fun hasOpenOrder(userId: UserId): Boolean = events.hasOpenOrder(userId)
}

/**
 * Integrations' payment-provider webhook rings here with the key of the address it arrived on and our own reference;
 * the ramp checks both are its own and then asks the provider itself.
 */
class RampCollectionNotifications(private val events: RampEventsService) : CollectionNotifications {
    override suspend fun onPaymentCallback(callbackKey: String, reference: String): Resource<Unit> =
        events.paymentCallback(callbackKey, reference)
}

/** The gateway's replay after a reconnect: the frames since `seq`, or a `resync` when the gap is older than what is kept. */
class RampRealtimeReplay(private val events: RampEventsService) : RealtimeReplay {
    override suspend fun since(userId: UserId, seq: Long): Resource<List<RealtimeEnvelope>> {
        val frames =
            events.missedFrames(userId, seq).getOrNull()
                ?: return Resource.success(listOf(RealtimeEnvelope.resync(RampFrames.MODULE, seq)))
        return Resource.success(frames.map { CircasoJson.decodeFromString(RealtimeEnvelope.serializer(), it.json) })
    }
}

/** ramp:api's staff contract, for the ops module. Who acted is kept as the staff member's id. */
class RampAdminImpl(private val admin: RampAdminService) : RampAdmin {
    override suspend fun merchants(before: MerchantId?, limit: Int): List<OpsMerchant> =
        admin
            .merchants(before, limit)
            .getOrNull()
            .orEmpty()
            .map { it.toOps() }

    override suspend fun approve(merchantId: MerchantId, by: UUID): Resource<OpsMerchant> =
        admin.decide(merchantId, approve = true, by = by.toString()).map { it.toOps() }

    override suspend fun suspend(merchantId: MerchantId, by: UUID): Resource<OpsMerchant> =
        admin.decide(merchantId, approve = false, by = by.toString()).map { it.toOps() }

    override suspend fun settings(network: Network): OpsRampSettings {
        val settings = admin.settings(network).getOrNull() ?: RampSettings.defaults(network, Instant.EPOCH)
        return settings.toOps(offered())
    }

    override suspend fun updateSettings(settings: OpsRampSettings, by: UUID): Resource<OpsRampSettingsChange> =
        settings.toDomain().flatMap { wanted ->
            val offered = offered()
            admin
                .updateSettings(wanted, settings.version, by.toString())
                .map { OpsRampSettingsChange(it.before.toOps(offered), it.after.toOps(offered)) }
        }

    private suspend fun offered(): List<String> = admin.collectionProviders().getOrNull().orEmpty()

    override suspend fun previewAccess(network: Network): List<OpsRampPreviewAccess> =
        admin
            .previewAccess(network)
            .getOrNull()
            .orEmpty()
            .map { OpsRampPreviewAccess(it.userId, it.network, it.grantedBy, it.at) }

    override suspend fun setPreviewAccess(
        userId: UserId,
        network: Network,
        enabled: Boolean,
        by: UUID,
    ): Boolean = admin.setPreviewAccess(userId, network, enabled, by.toString()).getOrNull() ?: false

    override suspend fun ordersInReview(
        network: Network?,
        after: RampOrderId?,
        limit: Int,
    ): List<OpsRampOrder> =
        admin
            .ordersInReview(network, after, limit)
            .getOrNull()
            .orEmpty()
            .map { it.toOps() }
}

private fun MerchantRecord.toOps() =
    OpsMerchant(
        merchantId = merchant.merchantId,
        email = merchant.email,
        displayName = merchant.displayName,
        contactPhone = merchant.contactPhone,
        state = merchant.state.name,
        onlineUntil = merchant.onlineUntil,
        paymentsConnected = paymentsConnected,
        wallets = wallets.map { OpsMerchantWallet(it.network, it.blockchain, it.address) },
        approvedBy = merchant.approvedBy,
        approvedAt = merchant.approvedAt,
        createdAt = merchant.createdAt,
    )

private fun OrderView.toOps() =
    OpsRampOrder(
        orderId = order.orderId,
        network = order.network,
        userId = order.userId,
        merchantId = order.merchantId,
        merchantName = merchantName,
        state = order.state.name,
        blockchain = order.blockchain,
        fiatCurrency = order.fiat.currency,
        fiatAmountMinor = order.fiat.amountMinor,
        assetAmountMinor = order.assetMinor,
        feeMinor = order.feeMinor,
        failureReason = order.failureReason,
        custodyTxId = order.custodyTxId,
        createdAt = order.createdAt,
        updatedAt = order.updatedAt,
    )

private fun RampSettings.toOps(offered: List<String>) =
    OpsRampSettings(
        network = network,
        state = state.name,
        chains = chains,
        orderMinMinor = orderMinMinor,
        orderMaxMinor = orderMaxMinor,
        dailyLimitMinor = dailyLimitMinor,
        promptsPerHour = promptsPerHour,
        feeBps = feeBps,
        merchantShareBps = merchantShareBps,
        feeSettleHourUtc = feeSettleHourUtc,
        quoteSeconds = quoteSeconds,
        paymentWindowSeconds = paymentWindowSeconds,
        kycRequired = kycRequired,
        callbackBaseUrl = callbackBaseUrl,
        collectionsProvider = collectionsProvider,
        version = version,
        walletSetId = walletSetId,
        updatedBy = updatedBy,
        updatedAt = updatedAt,
        collectionsProviders = offered,
    )

/** Staff's settings as the domain's; the read-only fields are whatever the row has. */
private fun OpsRampSettings.toDomain(): Resource<RampSettings> {
    val wanted =
        RampState.entries.firstOrNull { it.name == state.trim().uppercase() }
            ?: return Resource.error(RampError.SettingsInvalid("state", "must be OFF, PREVIEW or ON"))
    return Resource.success(
        RampSettings(
            network = network,
            state = wanted,
            chains = chains.map { it.trim().uppercase() },
            orderMinMinor = orderMinMinor,
            orderMaxMinor = orderMaxMinor,
            dailyLimitMinor = dailyLimitMinor,
            promptsPerHour = promptsPerHour,
            feeBps = feeBps,
            merchantShareBps = merchantShareBps,
            feeSettleHourUtc = feeSettleHourUtc,
            quoteSeconds = quoteSeconds,
            paymentWindowSeconds = paymentWindowSeconds,
            kycRequired = kycRequired,
            callbackBaseUrl = callbackBaseUrl.trim(),
            collectionsProvider = collectionsProvider.trim().lowercase(),
            walletSetId = null,
            updatedBy = null,
            updatedAt = Instant.EPOCH,
            version = version ?: 0,
        ),
    )
}
