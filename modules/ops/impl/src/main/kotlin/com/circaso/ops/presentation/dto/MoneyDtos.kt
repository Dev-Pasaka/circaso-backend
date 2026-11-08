package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

// Money: integer micro-dollars (`…UsdMicros`, 1 USD = 1_000_000) and exact coin amounts as strings in the coin's minor
// units with their decimals, so no figure passes through a floating point number on the way to the portal.

@Serializable
data class BalanceDto(
    val asset: String,
    val amountMinor: String,
    val decimals: Int,
    val usdMicros: Long? = null,
)

@Serializable
data class ChainWalletDto(
    val blockchain: String,
    val address: String,
    val state: String,
    val balances: List<BalanceDto>,
)

@Serializable
data class HoldingsResponse(
    val hasWallet: Boolean,
    val wallets: List<ChainWalletDto>,
    val totalUsdMicros: Long? = null,
    val balancesAvailable: Boolean,
)

@Serializable
data class TransferDto(
    val transferId: String,
    val senderId: String,
    val senderName: String? = null,
    val recipientId: String? = null,
    val recipientName: String? = null,
    val destinationAddress: String,
    val blockchain: String,
    val asset: String,
    val amountMinor: String,
    val decimals: Int,
    val feeMinor: String,
    val usdTotalMicros: Long,
    val state: String,
    val origin: String,
    val kind: String,
    val txHash: String? = null,
    val failureReason: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class TransferPageResponse(val items: List<TransferDto>, val nextCursor: String? = null)

@Serializable
data class UserTransfersResponse(
    val items: List<TransferDto>,
    val nextCursor: String? = null,
    val feesPaidUsdMicros: Long,
)

@Serializable
data class PeriodDto(
    val period: String,
    val count: Long,
    val volumeUsdMicros: Long,
    val feesUsdMicros: Long,
)

@Serializable
data class ChainAssetDto(
    val blockchain: String,
    val asset: String,
    val count: Long,
    val amountMinor: String,
    val decimals: Int,
    val volumeUsdMicros: Long,
    val feesUsdMicros: Long,
)

@Serializable
data class FinanceTotalsDto(val count: Long, val volumeUsdMicros: Long, val feesUsdMicros: Long)

@Serializable
data class GroupWalletsDto(val count: Long)

@Serializable
data class FinanceResponse(
    val from: String,
    val to: String,
    val bucket: String,
    val series: List<PeriodDto>,
    val byChainAsset: List<ChainAssetDto>,
    val totals: FinanceTotalsDto,
    val groupWallets: GroupWalletsDto,
)
