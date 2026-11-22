package com.circaso.ops.presentation.mappers

import com.circaso.ops.presentation.dto.RampMerchantDto
import com.circaso.ops.presentation.dto.RampMerchantWalletDto
import com.circaso.ops.presentation.dto.RampMerchantsResponse
import com.circaso.ops.presentation.dto.RampOrderDto
import com.circaso.ops.presentation.dto.RampOrdersResponse
import com.circaso.ops.presentation.dto.RampPreviewAccessDto
import com.circaso.ops.presentation.dto.RampPreviewAccessResponse
import com.circaso.ops.presentation.dto.RampSettingsDto
import com.circaso.ops.presentation.dto.UpdateRampSettingsRequest
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsMerchant
import com.circaso.ramp.api.OpsRampOrder
import com.circaso.ramp.api.OpsRampPreviewAccess
import com.circaso.ramp.api.OpsRampSettings
import com.circaso.ramp.api.RampOrderId
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Instant

fun parseMerchantId(raw: String?, field: String = "merchantId"): Resource<MerchantId> =
    raw?.let(MerchantId::parse)?.let { Resource.success(it) } ?: malformed(field, "must be a merchant id")

/** An optional id cursor: absent is the first page, anything that isn't an id is refused. */
fun merchantCursor(raw: String?): Resource<MerchantId?> =
    if (raw == null) Resource.success(null) else parseMerchantId(raw, "cursor")

fun orderCursor(raw: String?): Resource<RampOrderId?> =
    when {
        raw == null -> Resource.success(null)
        else -> RampOrderId.parse(raw)?.let { Resource.success(it) } ?: malformed("cursor", "must be an order id")
    }

private fun OpsMerchant.toDto(now: Instant) =
    RampMerchantDto(
        merchantId = merchantId.toString(),
        email = email,
        displayName = displayName,
        contactPhone = contactPhone,
        state = state,
        online = onlineUntil?.isAfter(now) == true,
        onlineUntil = onlineUntil?.toString(),
        paymentsConnected = paymentsConnected,
        wallets = wallets.map { RampMerchantWalletDto(it.network.name, it.blockchain, it.address) },
        approvedBy = approvedBy,
        approvedAt = approvedAt?.toString(),
        createdAt = createdAt.toString(),
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
        version = version,
        walletSetId = walletSetId,
        updatedBy = updatedBy,
        updatedAt = updatedAt?.toString(),
        collectionsProviders = collectionsProviders,
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
            version = version,
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
        orders =
            map {
                RampOrderDto(
                    orderId = it.orderId.toString(),
                    network = it.network.name,
                    userId = it.userId.toString(),
                    merchantId = it.merchantId.toString(),
                    merchantName = it.merchantName,
                    state = it.state,
                    blockchain = it.blockchain,
                    fiatCurrency = it.fiatCurrency,
                    fiatAmountMinor = it.fiatAmountMinor.toString(),
                    assetAmountMinor = it.assetAmountMinor.toString(),
                    feeMinor = it.feeMinor.toString(),
                    failureReason = it.failureReason,
                    custodyTxId = it.custodyTxId,
                    createdAt = it.createdAt.toString(),
                    updatedAt = it.updatedAt.toString(),
                )
            },
        nextCursor = lastOrNull()?.takeIf { size >= limit }?.orderId?.toString(),
    )
