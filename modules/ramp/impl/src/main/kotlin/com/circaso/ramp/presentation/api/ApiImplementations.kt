package com.circaso.ramp.presentation.api

import com.circaso.integrations.api.CollectionNotifications
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsMerchant
import com.circaso.ramp.api.OpsMerchantWallet
import com.circaso.ramp.api.OpsRampDay
import com.circaso.ramp.api.OpsRampFloat
import com.circaso.ramp.api.OpsRampMerchantActivity
import com.circaso.ramp.api.OpsRampMerchantOrder
import com.circaso.ramp.api.OpsRampMerchantProfile
import com.circaso.ramp.api.OpsRampMerchantSummary
import com.circaso.ramp.api.OpsRampMoney
import com.circaso.ramp.api.OpsRampOrder
import com.circaso.ramp.api.OpsRampOrderCounts
import com.circaso.ramp.api.OpsRampOrderCursor
import com.circaso.ramp.api.OpsRampOverview
import com.circaso.ramp.api.OpsRampPaymentsIssue
import com.circaso.ramp.api.OpsRampPeriod
import com.circaso.ramp.api.OpsRampPreviewAccess
import com.circaso.ramp.api.OpsRampSettings
import com.circaso.ramp.api.OpsRampSettingsChange
import com.circaso.ramp.api.OpsRampSweep
import com.circaso.ramp.api.Ramp
import com.circaso.ramp.api.RampAdmin
import com.circaso.ramp.api.RampFrames
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.api.RampOrderStates
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.MerchantFigures
import com.circaso.ramp.domain.models.MerchantRecord
import com.circaso.ramp.domain.models.MerchantSummary
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.OrderView
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.RampOverview
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.domain.models.StaffOrder
import com.circaso.ramp.domain.models.StaffOrderFilter
import com.circaso.ramp.domain.models.StatementTotals
import com.circaso.ramp.domain.support.Amounts
import com.circaso.ramp.presentation.services.RampAdminService
import com.circaso.ramp.presentation.services.RampEventsService
import com.circaso.shared.CircasoJson
import com.circaso.shared.Network
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.RealtimeReplay
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.flatMap
import com.circaso.shared.getOrNull
import com.circaso.shared.map
import java.time.Instant
import java.time.LocalDate
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
        settings.toDomain(currentFloor(settings.network)).flatMap { wanted ->
            val offered = offered()
            admin
                .updateSettings(wanted, settings.version, by.toString())
                .map { OpsRampSettingsChange(it.before.toOps(offered), it.after.toOps(offered)) }
        }

    private suspend fun offered(): List<String> = admin.collectionProviders().getOrNull().orEmpty()

    /** The floor in force, for an update that doesn't name one (a form from before the setting existed). */
    private suspend fun currentFloor(network: Network): Long =
        admin.settings(network).getOrNull()?.providerFeeFloorMinor ?: RampSettings.DEFAULT_PROVIDER_FEE_FLOOR_MINOR

    override suspend fun overview(network: Network, period: OpsRampPeriod?): OpsRampOverview =
        admin
            .overview(network, period?.toDomain())
            .getOrNull()
            ?.toOps()
            ?: OpsRampOverview(
                network = network,
                period = period ?: OpsRampPeriod(LocalDate.EPOCH, LocalDate.EPOCH),
                orders = MerchantFigures.NONE.toCounts(),
                money = MerchantFigures.NONE.totals.toMoney(),
                merchants = emptyList(),
            )

    override suspend fun merchantSummary(
        merchantId: MerchantId,
        network: Network,
        period: OpsRampPeriod?,
    ): Resource<OpsRampMerchantSummary> =
        admin.merchantSummary(merchantId, network, period?.toDomain()).map { it.toOps() }

    override suspend fun merchantOrders(
        merchantId: MerchantId,
        network: Network,
        period: OpsRampPeriod?,
        state: String?,
        after: OpsRampOrderCursor?,
        limit: Int,
    ): Resource<List<OpsRampMerchantOrder>> {
        val wanted =
            state?.let { name ->
                OrderState.entries.firstOrNull { it.name == name.trim().uppercase() }
                    ?: return Resource.error(SharedError.Malformed("state", "must be one of ${RampOrderStates.ALL}"))
            }
        val filter = StaffOrderFilter(merchantId, network, period?.toDomain(), wanted)
        val cursor = after?.let { PageCursor(it.at, it.orderId.value) }
        return admin.merchantOrders(filter, cursor, limit).map { page -> page.map { it.toOps() } }
    }

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
        providerFeeFloorMinor = providerFeeFloorMinor,
        version = version,
        walletSetId = walletSetId,
        updatedBy = updatedBy,
        updatedAt = updatedAt,
        collectionsProviders = offered,
    )

/**
 * Staff's settings as the domain's; the read-only fields are whatever the row has. [currentFloor] stands in when the
 * update names no provider fee floor.
 */
private fun OpsRampSettings.toDomain(currentFloor: Long): Resource<RampSettings> {
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
            providerFeeFloorMinor = providerFeeFloorMinor ?: currentFloor,
            walletSetId = null,
            updatedBy = null,
            updatedAt = Instant.EPOCH,
            version = version ?: 0,
        ),
    )
}

// ---- staff's view of each merchant's activity -------------------------------------------------------------

private fun OpsRampPeriod.toDomain() = Period(from, to)

private fun Period.toOps() = OpsRampPeriod(from, to)

/** The same amounts, formatted the same way, as the merchant's own earnings overview. */
private fun StatementTotals.toMoney() =
    OpsRampMoney(
        collected = Amounts.fiat(collectedMinor),
        sold = Amounts.usdc(soldMinor),
        fees = Amounts.fiat(feesMinor),
        merchantShare = Amounts.fiat(merchantMinor),
        circasoShare = Amounts.fiat(platformMinor),
        circasoShareUsdc = Amounts.usdc(platformAssetMinor),
        swept = Amounts.usdc(sweptAssetMinor),
        held = Amounts.usdc(heldAssetMinor),
    )

private fun MerchantFigures.toCounts() =
    OpsRampOrderCounts(
        total = orders,
        completed = completed,
        failed = unfinished.failed,
        expired = unfinished.expired,
        cancelled = unfinished.cancelled,
        inReview = unfinished.inReview,
        open = unfinished.open,
    )

private fun RampOverview.toOps() =
    OpsRampOverview(
        network = network,
        period = period.toOps(),
        orders = totals.toCounts(),
        money = totals.totals.toMoney(),
        merchants =
            merchants.map {
                OpsRampMerchantActivity(
                    merchantId = it.merchant.merchantId,
                    displayName = it.merchant.displayName,
                    state = it.merchant.state.name,
                    online = it.online,
                    orders = it.figures.toCounts(),
                    successRatePercent = it.figures.successRatePercent,
                    money = it.figures.totals.toMoney(),
                    lastOrderAt = it.lastOrderAt,
                )
            },
    )

private fun MerchantSummary.toOps() =
    OpsRampMerchantSummary(
        merchant =
            OpsRampMerchantProfile(
                merchantId = merchant.merchantId,
                displayName = merchant.displayName,
                email = merchant.email,
                contactPhone = merchant.contactPhone,
                state = merchant.state.name,
                createdAt = merchant.createdAt,
                approvedAt = merchant.approvedAt,
                online = online,
                onlineUntil = merchant.onlineUntil,
                paymentsConnected = paymentsProvider != null,
                collectionsProvider = paymentsProvider,
                paymentsIssue = merchant.paymentsIssue?.let { OpsRampPaymentsIssue(it.name, merchant.paymentsIssueAt) },
            ),
        network = network,
        period = period.toOps(),
        wallet = wallet?.let { OpsMerchantWallet(it.network, it.blockchain, it.address) },
        float =
            float?.let {
                OpsRampFloat(
                    balance = Amounts.usdc(it.balanceMinor),
                    reserved = Amounts.usdc(it.reservedMinor),
                    owedToCircaso = Amounts.usdc(it.owedMinor),
                    free = Amounts.usdc(it.freeMinor),
                )
            },
        orders = figures.toCounts(),
        successRatePercent = figures.successRatePercent,
        money = figures.totals.toMoney(),
        daily =
            daily.map {
                OpsRampDay(
                    day = it.totals.day,
                    orders = it.orders,
                    completed = it.totals.completed,
                    collected = Amounts.fiat(it.totals.collectedMinor),
                    sold = Amounts.usdc(it.totals.soldMinor),
                    fees = Amounts.fiat(it.totals.feesMinor),
                    merchantShare = Amounts.fiat(it.totals.merchantMinor),
                    circasoShare = Amounts.fiat(it.totals.platformMinor),
                )
            },
        sweeps =
            sweeps.map {
                OpsRampSweep(it.settlementId, it.day, Amounts.usdc(it.amountMinor), it.state.name, it.orders, it.txHash)
            },
    )

/** The payer's number leaves the ramp masked, for staff as for the merchant. */
private fun StaffOrder.toOps() =
    OpsRampMerchantOrder(
        orderId = order.orderId,
        createdAt = order.createdAt,
        completedAt = commission?.earnedAt,
        state = order.state.name,
        direction = order.direction.name,
        blockchain = order.blockchain,
        maskedPhone = order.payerPhone.masked(),
        paid = Amounts.fiat(order.fiat),
        released = Amounts.usdc(order.assetMinor),
        rate = order.rate.stripTrailingZeros().toPlainString(),
        fee = Amounts.fiat(order.feeMinor),
        merchantShare = commission?.let { Amounts.fiat(it.merchantMinor) },
        circasoShare = commission?.let { Amounts.fiat(it.platformMinor) },
        circasoShareUsdc = commission?.let { Amounts.usdc(it.platformAssetMinor) },
        failureReason = order.failureReason,
        settlementId = commission?.settlementId,
        txHash = order.txHash,
        collectionsProvider = order.collectionsProvider,
    )
