package com.circaso.ops.presentation.mappers

import com.circaso.ops.presentation.dto.RampAmountDto
import com.circaso.ops.presentation.dto.RampBlockDto
import com.circaso.ops.presentation.dto.RampBlocksResponse
import com.circaso.ops.presentation.dto.RampDayDto
import com.circaso.ops.presentation.dto.RampFloatDto
import com.circaso.ops.presentation.dto.RampKesBalancesDto
import com.circaso.ops.presentation.dto.RampMerchantDto
import com.circaso.ops.presentation.dto.RampMerchantOrderDto
import com.circaso.ops.presentation.dto.RampMerchantOrdersResponse
import com.circaso.ops.presentation.dto.RampMerchantSummaryResponse
import com.circaso.ops.presentation.dto.RampMerchantWalletDto
import com.circaso.ops.presentation.dto.RampMerchantWithdrawalsResponse
import com.circaso.ops.presentation.dto.RampMerchantsResponse
import com.circaso.ops.presentation.dto.RampOrderCountsDto
import com.circaso.ops.presentation.dto.RampOrderDto
import com.circaso.ops.presentation.dto.RampOrdersResponse
import com.circaso.ops.presentation.dto.RampOverviewMerchantDto
import com.circaso.ops.presentation.dto.RampOverviewResponse
import com.circaso.ops.presentation.dto.RampOverviewTotalsDto
import com.circaso.ops.presentation.dto.RampPaymentsIssueDto
import com.circaso.ops.presentation.dto.RampPayoutFeeBandDto
import com.circaso.ops.presentation.dto.RampPreviewAccessDto
import com.circaso.ops.presentation.dto.RampPreviewAccessResponse
import com.circaso.ops.presentation.dto.RampProviderWalletDto
import com.circaso.ops.presentation.dto.RampResolveResponse
import com.circaso.ops.presentation.dto.RampSettingsDto
import com.circaso.ops.presentation.dto.RampSummaryMerchantDto
import com.circaso.ops.presentation.dto.RampSummaryWalletDto
import com.circaso.ops.presentation.dto.RampSweepDto
import com.circaso.ops.presentation.dto.RampUserVerificationResponse
import com.circaso.ops.presentation.dto.RampVerificationDto
import com.circaso.ops.presentation.dto.RampWithdrawalDestinationDto
import com.circaso.ops.presentation.dto.RampWithdrawalDto
import com.circaso.ops.presentation.dto.UpdateRampSettingsRequest
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsMerchant
import com.circaso.ramp.api.OpsPayoutFeeBand
import com.circaso.ramp.api.OpsRampBlock
import com.circaso.ramp.api.OpsRampBlockCursor
import com.circaso.ramp.api.OpsRampDay
import com.circaso.ramp.api.OpsRampMerchantActivity
import com.circaso.ramp.api.OpsRampMerchantOrder
import com.circaso.ramp.api.OpsRampMerchantProfile
import com.circaso.ramp.api.OpsRampMerchantSummary
import com.circaso.ramp.api.OpsRampOrder
import com.circaso.ramp.api.OpsRampOrderCursor
import com.circaso.ramp.api.OpsRampOverview
import com.circaso.ramp.api.OpsRampPeriod
import com.circaso.ramp.api.OpsRampPreviewAccess
import com.circaso.ramp.api.OpsRampProviderWallet
import com.circaso.ramp.api.OpsRampResolved
import com.circaso.ramp.api.OpsRampSettings
import com.circaso.ramp.api.OpsRampUserVerification
import com.circaso.ramp.api.OpsRampVerification
import com.circaso.ramp.api.OpsRampWithdrawals
import com.circaso.ramp.api.RampAmount
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.api.RampOrderStates
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

fun parseMerchantId(raw: String?, field: String = "merchantId"): Resource<MerchantId> =
    raw?.let(MerchantId::parse)?.let { Resource.success(it) } ?: malformed(field, "must be a merchant id")

fun parseRampOrderId(raw: String?): Resource<RampOrderId> =
    raw?.let(RampOrderId::parse)?.let { Resource.success(it) } ?: malformed("orderId", "must be an order id")

/** An optional id cursor: absent is the first page, anything that isn't an id is refused. */
fun merchantCursor(raw: String?): Resource<MerchantId?> =
    if (raw == null) Resource.success(null) else parseMerchantId(raw, "cursor")

fun orderCursor(raw: String?): Resource<RampOrderId?> =
    when {
        raw == null -> Resource.success(null)
        else -> RampOrderId.parse(raw)?.let { Resource.success(it) } ?: malformed("cursor", "must be an order id")
    }

fun OpsRampVerification.toDto() =
    RampVerificationDto(
        environment = environment,
        status = status,
        reason = reason,
        provider = provider,
        attempts = attempts,
        hasIdentity = hasIdentity,
        startedAt = startedAt?.toString(),
        verifiedAt = verifiedAt?.toString(),
        updatedAt = updatedAt.toString(),
    )

fun OpsRampBlock.toDto() =
    RampBlockDto(
        blockId = blockId.toString(),
        userId = userId.toString(),
        reason = reason,
        note = note,
        createdBy = createdBy,
        createdAt = createdAt.toString(),
    )

fun OpsRampUserVerification.toResponse() =
    RampUserVerificationResponse(
        userId = userId.toString(),
        verifications = verifications.map { it.toDto() },
        blocked = block != null,
        block = block?.toDto(),
    )

/** A page of blocks; `nextCursor` continues after its last block, null on the last page. */
fun List<OpsRampBlock>.toBlocksResponse(limit: Int) =
    RampBlocksResponse(
        blocks = map { it.toDto() },
        nextCursor = lastOrNull()?.takeIf { size >= limit }?.let { "${it.createdAt}$CURSOR_SEPARATOR${it.blockId}" },
    )

/** A page's cursor: `<createdAt>~<blockId>` of its last block, as `nextCursor` gave it. */
fun blockCursor(raw: String?): Resource<OpsRampBlockCursor?> {
    if (raw == null) return Resource.success(null)
    val parts = raw.split(CURSOR_SEPARATOR)
    val cursor =
        runCatching { OpsRampBlockCursor(Instant.parse(parts[0]), UUID.fromString(parts[1])) }
            .getOrNull()
            ?.takeIf { parts.size == 2 }
    return cursor?.let { Resource.success(it) } ?: malformed("cursor", "must be a nextCursor of this list")
}

private fun OpsMerchant.toDto(now: Instant) =
    RampMerchantDto(
        merchantId = merchantId.toString(),
        email = email,
        displayName = displayName,
        contactPhone = contactPhone,
        state = state,
        online = onlineOpenEnded || onlineUntil?.isAfter(now) == true,
        onlineUntil = onlineUntil?.toString(),
        onlineOpenEnded = onlineOpenEnded,
        offlineReason = offlineReason,
        paymentsConnected = paymentsConnected,
        wallets = wallets.map { RampMerchantWalletDto(it.network.name, it.blockchain, it.address) },
        approvedBy = approvedBy,
        approvedAt = approvedAt?.toString(),
        createdAt = createdAt.toString(),
        verifications = verifications.map { it.toDto() },
        merchantType = merchantType,
        orderLimitMinor = orderLimitMinor?.toString(),
        offersOnRamp = offersOnRamp,
        offersOffRamp = offersOffRamp,
    )

fun OpsMerchant.toResponse(now: Instant) = toDto(now)

fun List<OpsMerchant>.toResponse(limit: Int, now: Instant) =
    RampMerchantsResponse(
        merchants = map { it.toDto(now) },
        nextCursor = lastOrNull()?.takeIf { size >= limit }?.merchantId?.toString(),
    )

fun OpsRampSettings.toDto() =
    RampSettingsDto(
        network = network.name,
        state = state,
        chains = chains,
        orderMinMinor = orderMinMinor.toString(),
        orderMaxMinor = orderMaxMinor.toString(),
        dailyLimitMinor = dailyLimitMinor.toString(),
        promptsPerHour = promptsPerHour,
        feeBps = feeBps,
        merchantShareBps = merchantShareBps,
        feeSettleHourUtc = feeSettleHourUtc,
        quoteSeconds = quoteSeconds,
        paymentWindowSeconds = paymentWindowSeconds,
        kycRequired = kycRequired,
        callbackBaseUrl = callbackBaseUrl,
        collectionsProvider = collectionsProvider,
        providerFeeFloorMinor = (providerFeeFloorMinor ?: 0).toString(),
        version = version,
        walletSetId = walletSetId,
        updatedBy = updatedBy,
        updatedAt = updatedAt?.toString(),
        collectionsProviders = collectionsProviders,
        kycProvider = kycProvider.orEmpty(),
        kycEnvironment = kycEnvironment.orEmpty(),
        kycWorkflowUser = kycWorkflowUser.orEmpty(),
        kycWorkflowMerchant = kycWorkflowMerchant.orEmpty(),
        merchantPortalUrl = merchantPortalUrl.orEmpty(),
        kycAttemptsPerDay = kycAttemptsPerDay ?: 0,
        kycPendingHours = kycPendingHours ?: 0,
        kycProviders = kycProviders,
        offRampState = offRampState ?: "OFF",
        offRampMinMinor = (offRampMinMinor ?: 0).toString(),
        offRampMaxMinor = (offRampMaxMinor ?: 0).toString(),
        offRampDailyLimitMinor = (offRampDailyLimitMinor ?: 0).toString(),
        transferWindowSeconds = transferWindowSeconds ?: 0,
        payoutWindowSeconds = payoutWindowSeconds ?: 0,
        payoutFeeBands =
            payoutFeeBands.orEmpty().map { RampPayoutFeeBandDto(it.upToMinor.toString(), it.feeMinor.toString()) },
        payoutBalanceLowMinor = (payoutBalanceLowMinor ?: 0).toString(),
        withdrawDestinationDelayHours = withdrawDestinationDelayHours ?: 0,
        withdrawMinMinor = (withdrawMinMinor ?: 0).toString(),
        withdrawDailyMaxMinor = (withdrawDailyMaxMinor ?: 0).toString(),
        onlineSessionMaxDays = onlineSessionMaxDays ?: 0,
    )

/** The request's settings for [network]; an amount that isn't a whole number of cents is refused, naming its field. */
fun UpdateRampSettingsRequest.toSettings(network: Network): Resource<OpsRampSettings> {
    val amounts =
        listOf(
            "orderMinMinor" to orderMinMinor,
            "orderMaxMinor" to orderMaxMinor,
            "dailyLimitMinor" to dailyLimitMinor,
        )
    val parsed = amounts.map { (field, raw) -> raw.trim().toLongOrNull() ?: return malformed(field, NOT_CENTS) }
    val floor =
        providerFeeFloorMinor?.let { it.trim().toLongOrNull() ?: return malformed("providerFeeFloorMinor", NOT_CENTS) }
    val selling =
        listOf(
            "offRampMinMinor" to offRampMinMinor,
            "offRampMaxMinor" to offRampMaxMinor,
            "offRampDailyLimitMinor" to offRampDailyLimitMinor,
            "payoutBalanceLowMinor" to payoutBalanceLowMinor,
            "withdrawMinMinor" to withdrawMinMinor,
            "withdrawDailyMaxMinor" to withdrawDailyMaxMinor,
        ).map { (field, raw) -> raw?.let { it.trim().toLongOrNull() ?: return malformed(field, NOT_CENTS) } }
    val bands =
        payoutFeeBands?.mapIndexed { index, band ->
            val upTo = band.upToMinor.trim().toLongOrNull()
            val fee = band.feeMinor.trim().toLongOrNull()
            if (upTo == null || fee == null) return malformed("payoutFeeBands[$index]", NOT_CENTS)
            OpsPayoutFeeBand(upTo, fee)
        }
    return Resource.success(
        OpsRampSettings(
            network = network,
            state = state.trim().uppercase(),
            chains = chains.map { it.trim().uppercase() },
            orderMinMinor = parsed[0],
            orderMaxMinor = parsed[1],
            dailyLimitMinor = parsed[2],
            promptsPerHour = promptsPerHour,
            feeBps = feeBps,
            merchantShareBps = merchantShareBps,
            feeSettleHourUtc = feeSettleHourUtc,
            quoteSeconds = quoteSeconds,
            paymentWindowSeconds = paymentWindowSeconds,
            kycRequired = kycRequired,
            callbackBaseUrl = callbackBaseUrl.trim(),
            collectionsProvider = collectionsProvider.trim().lowercase(),
            providerFeeFloorMinor = floor,
            version = version,
            kycProvider = kycProvider?.trim()?.lowercase(),
            kycEnvironment = kycEnvironment?.trim()?.uppercase(),
            kycWorkflowUser = kycWorkflowUser?.trim(),
            kycWorkflowMerchant = kycWorkflowMerchant?.trim(),
            merchantPortalUrl = merchantPortalUrl?.trim(),
            kycAttemptsPerDay = kycAttemptsPerDay,
            kycPendingHours = kycPendingHours,
            offRampState = offRampState?.trim()?.uppercase(),
            offRampMinMinor = selling[0],
            offRampMaxMinor = selling[1],
            offRampDailyLimitMinor = selling[2],
            transferWindowSeconds = transferWindowSeconds,
            payoutWindowSeconds = payoutWindowSeconds,
            payoutFeeBands = bands,
            payoutBalanceLowMinor = selling[3],
            withdrawDestinationDelayHours = withdrawDestinationDelayHours,
            withdrawMinMinor = selling[4],
            withdrawDailyMaxMinor = selling[5],
            onlineSessionMaxDays = onlineSessionMaxDays,
        ),
    )
}

private const val NOT_CENTS = "must be a whole number of cents"

fun List<OpsRampPreviewAccess>.toResponse(network: Network) =
    RampPreviewAccessResponse(
        network = network.name,
        people = map { RampPreviewAccessDto(it.userId.toString(), it.network.name, it.grantedBy, it.at.toString()) },
    )

fun List<OpsRampOrder>.toResponse(limit: Int) =
    RampOrdersResponse(
        orders = map { it.toDto() },
        nextCursor = lastOrNull()?.takeIf { size >= limit }?.orderId?.toString(),
    )

fun OpsRampOrder.toDto() =
    RampOrderDto(
        orderId = orderId.toString(),
        network = network.name,
        userId = userId.toString(),
        merchantId = merchantId.toString(),
        merchantName = merchantName,
        state = state,
        blockchain = blockchain,
        fiatCurrency = fiatCurrency,
        fiatAmountMinor = fiatAmountMinor.toString(),
        assetAmountMinor = assetAmountMinor.toString(),
        feeMinor = feeMinor.toString(),
        failureReason = failureReason,
        custodyTxId = custodyTxId,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        direction = direction,
        providerFeeMinor = providerFeeMinor.toString(),
        fundedMinor = fundedMinor?.toString(),
        payoutAttempts = payoutAttempts,
        payoutOutcome = payoutOutcome,
        hasReceipt = hasReceipt,
        rate = rate,
        rateSource = rateSource,
        referenceRate = referenceRate,
        spreadBps = spreadBps,
    )

fun OpsRampResolved.toResponse() = RampResolveResponse(order.toDto(), providerSaid)

// ---- each merchant's activity ----------------------------------------------------------------------------

private const val CURSOR_SEPARATOR = "~"
private const val MAX_PERIOD_DAYS = 366L

/**
 * `?from=&to=`: Nairobi calendar days, inclusive. Neither given is null (the ramp then takes this month, or for a list
 * of orders every day); one alone, a `to` before `from`, or more than a year is refused.
 */
fun rampPeriod(from: String?, to: String?): Resource<OpsRampPeriod?> {
    if (from == null && to == null) return Resource.success(null)
    val first = from?.let(::dayOrNull) ?: return malformed("from", "must be a date (2026-11-01)")
    val last = to?.let(::dayOrNull) ?: return malformed("to", "must be a date (2026-11-30)")
    if (last.isBefore(first)) return malformed("to", "is before from")
    if (ChronoUnit.DAYS.between(first, last) >= MAX_PERIOD_DAYS) return malformed("to", "more than a year after from")
    return Resource.success(OpsRampPeriod(first, last))
}

private fun dayOrNull(raw: String): LocalDate? =
    try {
        LocalDate.parse(raw)
    } catch (_: DateTimeParseException) {
        null
    }

/** `?state=`: one of the ramp's order states, in any case; absent is every state. */
fun rampOrderState(raw: String?): Resource<String?> {
    if (raw == null) return Resource.success(null)
    val state = raw.uppercase()
    return if (state in RampOrderStates.ALL) {
        Resource.success(state)
    } else {
        malformed("state", "must be one of ${RampOrderStates.ALL.joinToString(", ")}")
    }
}

/** A page's cursor: `<createdAt>~<orderId>` of its last order, as `nextCursor` gave it. */
fun merchantOrderCursor(raw: String?): Resource<OpsRampOrderCursor?> {
    if (raw == null) return Resource.success(null)
    val parts = raw.split(CURSOR_SEPARATOR)
    val at =
        try {
            parts.takeIf { it.size == 2 }?.let { Instant.parse(it[0]) }
        } catch (_: DateTimeParseException) {
            null
        }
    val orderId = parts.getOrNull(1)?.let(RampOrderId::parse)
    return if (at == null || orderId == null) {
        malformed("cursor", "must be a nextCursor of this list")
    } else {
        Resource.success(OpsRampOrderCursor(at, orderId))
    }
}

private fun RampAmount.toDto() = RampAmountDto(unit, amountMinor, amount)

fun OpsRampOverview.toResponse() =
    RampOverviewResponse(
        network = network.name,
        from = period.from.toString(),
        to = period.to.toString(),
        totals =
            RampOverviewTotalsDto(
                orders = orders.total,
                completed = orders.completed,
                failed = orders.failed,
                inReview = orders.inReview,
                collected = money.collected.toDto(),
                sold = money.sold.toDto(),
                fees = money.fees.toDto(),
                merchantShare = money.merchantShare.toDto(),
                circasoShare = money.circasoShare.toDto(),
                circasoShareUsdc = money.circasoShareUsdc.toDto(),
                swept = money.swept.toDto(),
                held = money.held.toDto(),
                paidOut = money.paidOut.toDto(),
                bought = money.bought.toDto(),
                refunded = orders.refunded,
            ),
        merchants = merchants.map { it.toDto() },
    )

private fun OpsRampMerchantActivity.toDto() =
    RampOverviewMerchantDto(
        merchantId = merchantId.toString(),
        displayName = displayName,
        state = state,
        online = online,
        orders = orders.total,
        completed = orders.completed,
        successRatePercent = successRatePercent,
        collected = money.collected.toDto(),
        sold = money.sold.toDto(),
        fees = money.fees.toDto(),
        merchantShare = money.merchantShare.toDto(),
        circasoShare = money.circasoShare.toDto(),
        circasoShareUsdc = money.circasoShareUsdc.toDto(),
        held = money.held.toDto(),
        lastOrderAt = lastOrderAt?.toString(),
        paidOut = money.paidOut.toDto(),
        bought = money.bought.toDto(),
        providerWallets = providerWallets.map { it.toDto() },
    )

private fun OpsRampProviderWallet.toDto() =
    RampProviderWalletDto(
        wallet = wallet,
        name = name,
        provider = provider,
        direction = direction,
        balance = balance?.toDto(),
        source = source,
        readAt = readAt?.toString(),
        low = low,
    )

private fun OpsRampMerchantProfile.toDto() =
    RampSummaryMerchantDto(
        merchantId = merchantId.toString(),
        displayName = displayName,
        email = email,
        contactPhone = contactPhone,
        state = state,
        createdAt = createdAt.toString(),
        approvedAt = approvedAt?.toString(),
        online = online,
        onlineUntil = onlineUntil?.toString(),
        onlineOpenEnded = onlineOpenEnded,
        offlineReason = offlineReason,
        paymentsConnected = paymentsConnected,
        collectionsProvider = collectionsProvider,
        paymentsIssue = paymentsIssue?.let { RampPaymentsIssueDto(it.code, it.since?.toString()) },
        kyc = kyc?.toDto(),
        offersOffRamp = offersOffRamp,
        payoutAccountId = payoutAccountId,
        payoutIssue = payoutIssue?.let { RampPaymentsIssueDto(it.code, it.since?.toString()) },
    )

private fun OpsRampDay.toDto() =
    RampDayDto(
        day = day.toString(),
        orders = orders,
        completed = completed,
        collected = collected.toDto(),
        sold = sold.toDto(),
        fees = fees.toDto(),
        merchantShare = merchantShare.toDto(),
        circasoShare = circasoShare.toDto(),
    )

fun OpsRampMerchantSummary.toResponse() =
    RampMerchantSummaryResponse(
        merchant = merchant.toDto(),
        network = network.name,
        from = period.from.toString(),
        to = period.to.toString(),
        wallet = wallet?.let { RampSummaryWalletDto(it.blockchain, it.address) },
        float =
            float?.let {
                RampFloatDto(it.balance.toDto(), it.reserved.toDto(), it.owedToCircaso.toDto(), it.free.toDto())
            },
        orders =
            RampOrderCountsDto(
                total = orders.total,
                completed = orders.completed,
                failed = orders.failed,
                expired = orders.expired,
                cancelled = orders.cancelled,
                inReview = orders.inReview,
                open = orders.open,
                refunded = orders.refunded,
            ),
        successRatePercent = successRatePercent,
        collected = money.collected.toDto(),
        sold = money.sold.toDto(),
        fees = money.fees.toDto(),
        merchantShare = money.merchantShare.toDto(),
        circasoShare = money.circasoShare.toDto(),
        circasoShareUsdc = money.circasoShareUsdc.toDto(),
        swept = money.swept.toDto(),
        held = money.held.toDto(),
        daily = daily.map { it.toDto() },
        sweeps =
            sweeps.map {
                RampSweepDto(
                    settlementId = it.settlementId.toString(),
                    day = it.day.toString(),
                    amount = it.amount.toDto(),
                    state = it.state,
                    orders = it.orders,
                    txHash = it.txHash,
                )
            },
        paidOut = money.paidOut.toDto(),
        bought = money.bought.toDto(),
        kesBalances =
            kesBalances?.let {
                RampKesBalancesDto(
                    payout = it.payout?.toDto(),
                    payoutSource = it.payoutSource,
                    fees = it.fees?.toDto(),
                    feesSource = it.feesSource,
                    readAt = it.readAt?.toString(),
                    held = it.held.toDto(),
                    wallets = it.wallets.map { wallet -> wallet.toDto() },
                )
            },
    )

private fun OpsRampMerchantOrder.toDto() =
    RampMerchantOrderDto(
        orderId = orderId.toString(),
        createdAt = createdAt.toString(),
        completedAt = completedAt?.toString(),
        state = state,
        direction = direction,
        blockchain = blockchain,
        maskedPhone = maskedPhone,
        paid = paid.toDto(),
        released = released.toDto(),
        rate = rate,
        fee = fee.toDto(),
        merchantShare = merchantShare?.toDto(),
        circasoShare = circasoShare?.toDto(),
        circasoShareUsdc = circasoShareUsdc?.toDto(),
        failureReason = failureReason,
        settlementId = settlementId?.toString(),
        txHash = txHash,
        collectionsProvider = collectionsProvider,
        rateSource = rateSource,
        referenceRate = referenceRate,
        spreadBps = spreadBps,
    )

/** A page of a merchant's orders; `nextCursor` continues after its last order, null on the last page. */
fun List<OpsRampMerchantOrder>.toMerchantOrdersResponse(limit: Int) =
    RampMerchantOrdersResponse(
        orders = map { it.toDto() },
        nextCursor = lastOrNull()?.takeIf { size >= limit }?.let { "${it.createdAt}$CURSOR_SEPARATOR${it.orderId}" },
    )

fun OpsRampWithdrawals.toResponse() =
    RampMerchantWithdrawalsResponse(
        withdrawals =
            withdrawals.map {
                RampWithdrawalDto(
                    withdrawalId = it.withdrawalId.toString(),
                    state = it.state,
                    amount = it.amount.toDto(),
                    blockchain = it.blockchain,
                    destinationAddress = it.destinationAddress,
                    destinationLabel = it.destinationLabel,
                    txHash = it.txHash,
                    explorerUrl = it.explorerUrl,
                    failureReason = it.failureReason,
                    createdAt = it.createdAt.toString(),
                    updatedAt = it.updatedAt.toString(),
                    closedAt = it.closedAt?.toString(),
                )
            },
        destinations =
            destinations.map {
                RampWithdrawalDestinationDto(
                    destinationId = it.destinationId.toString(),
                    label = it.label,
                    address = it.address,
                    blockchain = it.blockchain,
                    createdAt = it.createdAt.toString(),
                    usableAt = it.usableAt.toString(),
                    removedAt = it.removedAt?.toString(),
                )
            },
    )
