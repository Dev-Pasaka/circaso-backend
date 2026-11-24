package com.circaso.ramp.presentation.api

import com.circaso.integrations.api.CollectionNotifications
import com.circaso.integrations.api.KycEnvironment
import com.circaso.integrations.api.KycNotifications
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsMerchant
import com.circaso.ramp.api.OpsMerchantWallet
import com.circaso.ramp.api.OpsPayoutFeeBand
import com.circaso.ramp.api.OpsRampBlock
import com.circaso.ramp.api.OpsRampBlockCursor
import com.circaso.ramp.api.OpsRampDay
import com.circaso.ramp.api.OpsRampFloat
import com.circaso.ramp.api.OpsRampKesBalances
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
import com.circaso.ramp.api.OpsRampProviderWallet
import com.circaso.ramp.api.OpsRampResolved
import com.circaso.ramp.api.OpsRampSettings
import com.circaso.ramp.api.OpsRampSettingsChange
import com.circaso.ramp.api.OpsRampSweep
import com.circaso.ramp.api.OpsRampUserVerification
import com.circaso.ramp.api.OpsRampVerification
import com.circaso.ramp.api.Ramp
import com.circaso.ramp.api.RampAdmin
import com.circaso.ramp.api.RampFrames
import com.circaso.ramp.api.RampKycEnvironments
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.api.RampOrderStates
import com.circaso.ramp.api.RampResolutions
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Block
import com.circaso.ramp.domain.models.MerchantFigures
import com.circaso.ramp.domain.models.MerchantRecord
import com.circaso.ramp.domain.models.MerchantSummary
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.OrderView
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.PayoutFeeBand
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.ProviderWalletView
import com.circaso.ramp.domain.models.RampOverview
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.domain.models.Resolution
import com.circaso.ramp.domain.models.ResolveCommand
import com.circaso.ramp.domain.models.StaffOrder
import com.circaso.ramp.domain.models.StaffOrderFilter
import com.circaso.ramp.domain.models.StatementTotals
import com.circaso.ramp.domain.models.Verification
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

    /** A payout's doorbell (2026-11-23): the ramp asks the provider itself; a payment's reference is ignored here. */
    override suspend fun onPayoutCallback(callbackKey: String, reference: String): Resource<Unit> =
        events.payoutCallback(callbackKey, reference)

    /** The merchant's FinoQ webhook signing secret (2026-11-23), for integrations to check a callback with. */
    override suspend fun callbackSigningSecret(callbackKey: String): String? = events.callbackSecret(callbackKey)
}

/**
 * Integrations' KYC webhook rings here with the session's id and the environment its signature was checked against;
 * the ramp reads the decision from the provider itself.
 */
class RampKycNotifications(private val events: RampEventsService) : KycNotifications {
    override suspend fun onStatusChanged(environment: KycEnvironment, sessionId: String): Resource<Unit> =
        events.kycNotification(environment, sessionId)
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

    override suspend fun settings(network: Network): OpsRampSettings = current(network).toOps(offered(), offeredKyc())

    override suspend fun updateSettings(settings: OpsRampSettings, by: UUID): Resource<OpsRampSettingsChange> =
        settings.toDomain(current(settings.network)).flatMap { wanted ->
            val offered = offered()
            val offeredKyc = offeredKyc()
            admin
                .updateSettings(wanted, settings.version, by.toString())
                .map {
                    OpsRampSettingsChange(
                        it.before.toOps(offered, offeredKyc),
                        it.after.toOps(offered, offeredKyc),
                    )
                }
        }

    private suspend fun offered(): List<String> = admin.collectionProviders().getOrNull().orEmpty()

    private suspend fun offeredKyc(): List<String> = admin.kycProviders().getOrNull().orEmpty()

    /**
     * The settings in force: what a read answers, and what stands in for anything an update doesn't name (a form
     * from before that setting existed).
     */
    private suspend fun current(network: Network): RampSettings =
        admin.settings(network).getOrNull() ?: RampSettings.defaults(network, Instant.EPOCH)

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
        admin.merchantSummary(merchantId, network, period?.toDomain()).map { summary ->
            val kyc = admin.merchantVerification(merchantId, network).getOrNull()?.toOps()
            summary.toOps().let { it.copy(merchant = it.merchant.copy(kyc = kyc)) }
        }

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

    override suspend fun userVerification(userId: UserId): OpsRampUserVerification {
        val found = admin.userVerifications(userId).getOrNull()
        return OpsRampUserVerification(
            userId = userId,
            verifications = found?.verifications.orEmpty().map { it.toOps() },
            block = found?.block?.toOps(),
        )
    }

    override suspend fun blocks(after: OpsRampBlockCursor?, limit: Int): List<OpsRampBlock> =
        admin
            .blocks(after?.let { PageCursor(it.at, it.blockId) }, limit)
            .getOrNull()
            .orEmpty()
            .mapNotNull { it.toOps() }

    override suspend fun block(
        userId: UserId,
        note: String,
        by: UUID,
    ): Resource<OpsRampBlock> =
        admin.block(userId, note, by.toString()).flatMap { block ->
            block.toOps()?.let { Resource.success(it) } ?: Resource.error(SharedError.Internal)
        }

    override suspend fun unblock(userId: UserId, by: UUID): Boolean = admin.unblock(userId).getOrNull() ?: false

    override suspend fun resetUserVerification(
        userId: UserId,
        environment: String,
        by: UUID,
    ): Resource<Boolean> = environmentOf(environment).flatMap { admin.resetUserVerification(userId, it) }

    override suspend fun resetMerchantVerification(
        merchantId: MerchantId,
        environment: String,
        by: UUID,
    ): Resource<Boolean> = environmentOf(environment).flatMap { admin.resetMerchantVerification(merchantId, it) }

    override suspend fun resolve(
        orderId: RampOrderId,
        resolution: String,
        receipt: String?,
        by: UUID,
    ): Resource<OpsRampResolved> {
        val outcome =
            Resolution.entries.firstOrNull { it.name == resolution.trim().uppercase() }
                ?: return Resource.error(
                    SharedError.Malformed("resolution", "must be one of ${RampResolutions.ALL.joinToString()}"),
                )
        return admin.resolve(ResolveCommand(orderId, outcome, receipt, by.toString())).map {
            OpsRampResolved(it.view.toOps(), it.providerSaid)
        }
    }

    private fun environmentOf(name: String): Resource<KycEnvironment> =
        KycEnvironment.parse(name)?.let { Resource.success(it) }
            ?: Resource.error(SharedError.Malformed("environment", "must be one of ${RampKycEnvironments.ALL}"))
}

/** A verification for staff: where it stands and why, never the session's link or the identity key itself. */
internal fun Verification.toOps() =
    OpsRampVerification(
        environment = environment.name,
        status = status.name,
        reason = reason?.code,
        provider = provider,
        attempts = attempts,
        hasIdentity = identityKey != null,
        startedAt = startedAt,
        verifiedAt = verifiedAt,
        updatedAt = updatedAt,
    )

/** A user's block for staff; an identity's own row is never listed. */
private fun Block.toOps(): OpsRampBlock? =
    userId?.let { OpsRampBlock(blockId, it, reason.name, note, createdBy, createdAt) }

internal fun MerchantRecord.toOps() =
    OpsMerchant(
        merchantId = merchant.merchantId,
        email = merchant.email,
        displayName = merchant.displayName,
        contactPhone = merchant.contactPhone,
        state = merchant.state.name,
        onlineUntil = merchant.onlineUntil,
        onlineOpenEnded = merchant.onlineOpenEnded,
        offlineReason = merchant.offlineReason?.name,
        paymentsConnected = paymentsConnected,
        wallets = wallets.map { OpsMerchantWallet(it.network, it.blockchain, it.address) },
        approvedBy = merchant.approvedBy,
        approvedAt = merchant.approvedAt,
        createdAt = merchant.createdAt,
        verifications = verifications.map { it.toOps() },
        merchantType = merchant.merchantType.name,
        orderLimitMinor = merchant.orderLimitMinor,
        offersOnRamp = merchant.offersOnramp,
        offersOffRamp = merchant.offersOfframp,
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
        direction = order.direction.name,
        providerFeeMinor = order.providerFeeMinor,
        fundedMinor = order.fundedMinor,
        payoutAttempts = order.payoutAttempts,
        payoutOutcome = order.payoutOutcome,
        hasReceipt = order.mpesaReceipt != null,
        rate = order.rate.stripTrailingZeros().toPlainString(),
        rateSource = order.rateBasis?.source?.name,
        referenceRate = order.rateBasis?.referenceRate?.toPlainString(),
        spreadBps = order.rateBasis?.spreadBps,
    )

private fun RampSettings.toOps(offered: List<String>, offeredKyc: List<String>) =
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
        kycProvider = kycProvider,
        kycEnvironment = kycEnvironment.name,
        kycWorkflowUser = kycWorkflowUser,
        kycWorkflowMerchant = kycWorkflowMerchant,
        merchantPortalUrl = merchantPortalUrl,
        kycAttemptsPerDay = kycAttemptsPerDay,
        kycPendingHours = kycPendingHours,
        kycProviders = offeredKyc,
        offRampState = offRampState.name,
        offRampMinMinor = offRampMinMinor,
        offRampMaxMinor = offRampMaxMinor,
        offRampDailyLimitMinor = offRampDailyLimitMinor,
        transferWindowSeconds = transferWindowSeconds,
        payoutWindowSeconds = payoutWindowSeconds,
        payoutFeeBands = payoutFeeBands.map { OpsPayoutFeeBand(it.upToMinor, it.feeMinor) },
        payoutBalanceLowMinor = payoutBalanceLowMinor,
    )

/**
 * Staff's settings as the domain's; the read-only fields are whatever the row has. [current] stands in for each newer
 * setting the update doesn't name (the provider fee floor, the identity settings).
 */
private fun OpsRampSettings.toDomain(current: RampSettings): Resource<RampSettings> {
    val wanted =
        RampState.entries.firstOrNull { it.name == state.trim().uppercase() }
            ?: return Resource.error(RampError.SettingsInvalid("state", "must be OFF, PREVIEW or ON"))
    val environment =
        kycEnvironment?.let {
            KycEnvironment.parse(it)
                ?: return Resource.error(RampError.SettingsInvalid("kycEnvironment", "must be SANDBOX or LIVE"))
        }
    val selling =
        offRampState?.let { name ->
            RampState.entries.firstOrNull { it.name == name.trim().uppercase() }
                ?: return Resource.error(RampError.SettingsInvalid("offRampState", "must be OFF, PREVIEW or ON"))
        }
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
            providerFeeFloorMinor = providerFeeFloorMinor ?: current.providerFeeFloorMinor,
            walletSetId = null,
            updatedBy = null,
            updatedAt = Instant.EPOCH,
            version = version ?: 0,
            kycProvider = kycProvider?.trim()?.lowercase() ?: current.kycProvider,
            kycEnvironment = environment ?: current.kycEnvironment,
            kycWorkflowUser = kycWorkflowUser?.trim() ?: current.kycWorkflowUser,
            kycWorkflowMerchant = kycWorkflowMerchant?.trim() ?: current.kycWorkflowMerchant,
            merchantPortalUrl = merchantPortalUrl?.trim() ?: current.merchantPortalUrl,
            kycAttemptsPerDay = kycAttemptsPerDay ?: current.kycAttemptsPerDay,
            kycPendingHours = kycPendingHours ?: current.kycPendingHours,
            offRampState = selling ?: current.offRampState,
            offRampMinMinor = offRampMinMinor ?: current.offRampMinMinor,
            offRampMaxMinor = offRampMaxMinor ?: current.offRampMaxMinor,
            offRampDailyLimitMinor = offRampDailyLimitMinor ?: current.offRampDailyLimitMinor,
            transferWindowSeconds = transferWindowSeconds ?: current.transferWindowSeconds,
            payoutWindowSeconds = payoutWindowSeconds ?: current.payoutWindowSeconds,
            payoutFeeBands =
                payoutFeeBands?.map { PayoutFeeBand(it.upToMinor, it.feeMinor) } ?: current.payoutFeeBands,
            payoutBalanceLowMinor = payoutBalanceLowMinor ?: current.payoutBalanceLowMinor,
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
        paidOut = Amounts.fiat(paidOutMinor),
        bought = Amounts.usdc(boughtMinor),
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
        refunded = unfinished.refunded,
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
                    providerWallets = it.providerWallets.map { wallet -> wallet.toOps() },
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
                onlineOpenEnded = merchant.onlineOpenEnded,
                offlineReason = merchant.offlineReason?.name,
                paymentsConnected = paymentsProvider != null,
                collectionsProvider = paymentsProvider,
                paymentsIssue = merchant.paymentsIssue?.let { OpsRampPaymentsIssue(it.name, merchant.paymentsIssueAt) },
                offersOffRamp = merchant.offersOfframp,
                payoutAccountId = merchant.payoutAccountId,
                payoutIssue = merchant.payoutIssue?.let { OpsRampPaymentsIssue(it.name, merchant.payoutIssueAt) },
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
        kesBalances =
            OpsRampKesBalances(
                payout = kesBalances.finoqPayment?.let { Amounts.fiat(it.minor) },
                payoutSource = kesBalances.finoqPayment?.source,
                fees = kesBalances.finoqService?.let { Amounts.fiat(it.minor) },
                feesSource = kesBalances.finoqService?.source,
                readAt = kesBalances.readAt,
                held = Amounts.fiat(kesHeldMinor),
                wallets = providerWallets.map { it.toOps() },
            ),
    )

private fun ProviderWalletView.toOps() =
    OpsRampProviderWallet(
        wallet = target.name,
        name = target.label,
        provider = target.provider,
        direction = target.direction.name,
        balance = balance?.let { Amounts.fiat(it.minor) },
        source = balance?.source,
        readAt = balance?.readAt,
        low = low,
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
        rateSource = order.rateBasis?.source?.name,
        referenceRate = order.rateBasis?.referenceRate?.toPlainString(),
        spreadBps = order.rateBasis?.spreadBps,
    )
