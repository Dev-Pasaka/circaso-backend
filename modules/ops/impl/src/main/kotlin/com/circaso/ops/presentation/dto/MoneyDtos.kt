package com.circaso.ops.presentation.dto

import io.ktor.openapi.JsonSchema
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

/**
 * One side of a transfer. `kind`: USER (id = user id), GROUP (id = the group's chat id; `name` is always null, a
 * group's name is end-to-end encrypted, and `memberCount` is null once the group is gone), EXTERNAL (id = the
 * address, `name` = it shortened) or FEE (Circaso's fee wallet; id = its address, `name` = "Circaso fee").
 */
@Serializable
@JsonSchema.Description(
    "One side of a transfer: USER (id = user id), GROUP (id = chat id, name always null: group names are " +
        "end-to-end encrypted), EXTERNAL (id = address, name = it shortened) or FEE (Circaso's fee wallet).",
)
data class PartyDto(
    val kind: String,
    val id: String,
    val name: String? = null,
    val memberCount: Int? = null,
)

/** The fee leg that went to Circaso's fee wallet in the payment's own signature; its amount is the transfer's fee. */
@Serializable
data class FeeLegDto(
    val amountMinor: String,
    val decimals: Int,
    val asset: String,
    val to: PartyDto,
)

@Serializable
data class TransferDto(
    val transferId: String,
    val senderId: String,
    @JsonSchema.DeprecatedSchema
    @JsonSchema.Description("Deprecated: use sender.name. The sender's display name when the sender is a person.")
    val senderName: String? = null,
    val recipientId: String? = null,
    @JsonSchema.DeprecatedSchema
    @JsonSchema.Description("Deprecated: use recipient.name. The recipient's display name when it is a person.")
    val recipientName: String? = null,
    val sender: PartyDto,
    val recipient: PartyDto,
    /** Null when the payment carried no fee leg. */
    val fee: FeeLegDto? = null,
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
