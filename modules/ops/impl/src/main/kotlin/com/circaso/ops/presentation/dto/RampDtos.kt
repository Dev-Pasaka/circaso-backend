package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

@Serializable
data class RampMerchantWalletDto(val network: String, val blockchain: String, val address: String)

/** A ramp merchant. `state`: NEW | APPLIED | APPROVED | SUSPENDED. Their payment credentials are never shown. */
@Serializable
data class RampMerchantDto(
    val merchantId: String,
    val email: String,
    val displayName: String,
    val contactPhone: String,
    val state: String,
    val online: Boolean,
    val onlineUntil: String? = null,
    val paymentsConnected: Boolean,
    val wallets: List<RampMerchantWalletDto>,
    val approvedBy: String? = null,
    val approvedAt: String? = null,
    val createdAt: String,
)

@Serializable
data class RampMerchantsResponse(val merchants: List<RampMerchantDto>, val nextCursor: String? = null)

/**
 * One network's ramp settings. `state`: OFF | PREVIEW | ON. The three limits are KES minor units (cents) as strings.
 * `feeBps` is what a buyer pays (0 to 500), `merchantShareBps` the merchant's part of it (0 to 10000).
 * `feeSettleHourUtc` is the hour of the daily sweep of Circaso's share. `collectionsProvider` is who sends the M-Pesa
 * prompts, one of `collectionsProviders` (read-only: what this server offers). `providerFeeFloorMinor` is the least a
 * merchant's prepaid service balance at the provider must hold for them to go online (KES cents as a string; `"0"`
 * switches the check off). `version` rises on each change; sent back on an update, a change made meanwhile answers
 * `ramp.settings_changed`. `walletSetId`, `updatedBy` and `updatedAt` are read-only too.
 */
@Serializable
data class RampSettingsDto(
    val network: String,
    val state: String,
    val chains: List<String>,
    val orderMinMinor: String,
    val orderMaxMinor: String,
    val dailyLimitMinor: String,
    val promptsPerHour: Int,
    val feeBps: Int,
    val merchantShareBps: Int,
    val feeSettleHourUtc: Int,
    val quoteSeconds: Int,
    val paymentWindowSeconds: Int,
    val kycRequired: Boolean,
    val callbackBaseUrl: String,
    val collectionsProvider: String,
    val providerFeeFloorMinor: String,
    val version: Long? = null,
    val walletSetId: String? = null,
    val updatedBy: String? = null,
    val updatedAt: String? = null,
    val collectionsProviders: List<String> = emptyList(),
)

/**
 * `PUT /ops/ramp/settings?network=`: the settings and why they change. Read-only fields are ignored when sent.
 * `providerFeeFloorMinor` left out keeps the floor as it is (a form from before the setting existed).
 */
@Serializable
data class UpdateRampSettingsRequest(
    val state: String,
    val chains: List<String>,
    val orderMinMinor: String,
    val orderMaxMinor: String,
    val dailyLimitMinor: String,
    val promptsPerHour: Int,
    val feeBps: Int,
    val merchantShareBps: Int,
    val feeSettleHourUtc: Int,
    val quoteSeconds: Int,
    val paymentWindowSeconds: Int,
    val kycRequired: Boolean,
    val callbackBaseUrl: String,
    val collectionsProvider: String,
    val providerFeeFloorMinor: String? = null,
    val version: Long? = null,
    val reason: String = "",
    val network: String? = null,
    val walletSetId: String? = null,
    val updatedBy: String? = null,
    val updatedAt: String? = null,
    val collectionsProviders: List<String>? = null,
)

@Serializable
data class RampPreviewAccessDto(
    val userId: String,
    val network: String,
    val grantedBy: String? = null,
    val at: String,
)

@Serializable
data class RampPreviewAccessResponse(val network: String, val people: List<RampPreviewAccessDto>)

/** `POST /ops/ramp/preview-access`. */
@Serializable
data class RampPreviewAccessRequest(
    val userId: String,
    val network: String,
    val enabled: Boolean,
    val reason: String = "",
)

/** A ramp order waiting for a person. Amounts are minor units as strings; no payer number is ever shown. */
@Serializable
data class RampOrderDto(
    val orderId: String,
    val network: String,
    val userId: String,
    val merchantId: String,
    val merchantName: String,
    val state: String,
    val blockchain: String,
    val fiatCurrency: String,
    val fiatAmountMinor: String,
    val assetAmountMinor: String,
    val feeMinor: String,
    val failureReason: String? = null,
    val custodyTxId: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class RampOrdersResponse(val orders: List<RampOrderDto>, val nextCursor: String? = null)

/** An amount of the ramp's: `unit` is `KES` or `USDC`, `amountMinor` exact, `amount` for display. */
@Serializable
data class RampAmountDto(val unit: String, val amountMinor: String, val amount: String)

/**
 * A network's totals for a period: the sums of the merchant rows. `orders` is all of them, `completed` those that
 * finished; `fees` is inside `collected`, `merchantShare` and `circasoShare` add up to `fees`, and `swept` and `held`
 * add up to `circasoShareUsdc`.
 */
@Serializable
data class RampOverviewTotalsDto(
    val orders: Int,
    val completed: Int,
    val failed: Int,
    val inReview: Int,
    val collected: RampAmountDto,
    val sold: RampAmountDto,
    val fees: RampAmountDto,
    val merchantShare: RampAmountDto,
    val circasoShare: RampAmountDto,
    val circasoShareUsdc: RampAmountDto,
    val swept: RampAmountDto,
    val held: RampAmountDto,
)

/** One merchant's row. `online` is the trading window; `successRatePercent` is completed out of all orders. */
@Serializable
data class RampOverviewMerchantDto(
    val merchantId: String,
    val displayName: String,
    val state: String,
    val online: Boolean,
    val orders: Int,
    val completed: Int,
    val successRatePercent: String,
    val collected: RampAmountDto,
    val sold: RampAmountDto,
    val fees: RampAmountDto,
    val merchantShare: RampAmountDto,
    val circasoShare: RampAmountDto,
    val circasoShareUsdc: RampAmountDto,
    val held: RampAmountDto,
    val lastOrderAt: String? = null,
)

/** `GET /ops/ramp/overview`. `from` and `to` are Nairobi calendar days, inclusive. */
@Serializable
data class RampOverviewResponse(
    val network: String,
    val from: String,
    val to: String,
    val totals: RampOverviewTotalsDto,
    val merchants: List<RampOverviewMerchantDto>,
)

/** Something at the merchant's payment provider only they can fix. `code`: `SERVICE_BALANCE`. */
@Serializable
data class RampPaymentsIssueDto(val code: String, val since: String? = null)

/** The merchant's account. `collectionsProvider` is where their stored payment account is, null when none is. */
@Serializable
data class RampSummaryMerchantDto(
    val merchantId: String,
    val displayName: String,
    val email: String,
    val contactPhone: String,
    val state: String,
    val createdAt: String,
    val approvedAt: String? = null,
    val online: Boolean,
    val onlineUntil: String? = null,
    val paymentsConnected: Boolean,
    val collectionsProvider: String? = null,
    val paymentsIssue: RampPaymentsIssueDto? = null,
)

@Serializable
data class RampSummaryWalletDto(val blockchain: String, val address: String)

/** The float wallet's USDC: `free` is `balance` less what open orders hold and what is held for Circaso. */
@Serializable
data class RampFloatDto(
    val balance: RampAmountDto,
    val reserved: RampAmountDto,
    val owedToCircaso: RampAmountDto,
    val free: RampAmountDto,
)

/** `completed` are counted on the day they completed, the others on the day they were made; `total` is all. */
@Serializable
data class RampOrderCountsDto(
    val total: Int,
    val completed: Int,
    val failed: Int,
    val expired: Int,
    val cancelled: Int,
    val inReview: Int,
    val open: Int,
)

@Serializable
data class RampDayDto(
    val day: String,
    val orders: Int,
    val completed: Int,
    val collected: RampAmountDto,
    val sold: RampAmountDto,
    val fees: RampAmountDto,
    val merchantShare: RampAmountDto,
    val circasoShare: RampAmountDto,
)

/** One daily sweep of Circaso's share. `state`: PENDING | CONFIRMED | FAILED. */
@Serializable
data class RampSweepDto(
    val settlementId: String,
    val day: String,
    val amount: RampAmountDto,
    val state: String,
    val orders: Int,
    val txHash: String? = null,
)

/**
 * `GET /ops/ramp/merchants/{merchantId}/summary`. `wallet` is null until the ops server has made it on this network;
 * `float` is null without a wallet or when the custodian can't be read. `daily` has one row per Nairobi day with
 * activity, oldest first. The money is what the merchant's own earnings page shows for the same period.
 */
@Serializable
data class RampMerchantSummaryResponse(
    val merchant: RampSummaryMerchantDto,
    val network: String,
    val from: String,
    val to: String,
    val wallet: RampSummaryWalletDto? = null,
    val float: RampFloatDto? = null,
    val orders: RampOrderCountsDto,
    val successRatePercent: String,
    val collected: RampAmountDto,
    val sold: RampAmountDto,
    val fees: RampAmountDto,
    val merchantShare: RampAmountDto,
    val circasoShare: RampAmountDto,
    val circasoShareUsdc: RampAmountDto,
    val swept: RampAmountDto,
    val held: RampAmountDto,
    val daily: List<RampDayDto>,
    val sweeps: List<RampSweepDto>,
)

/**
 * One of a merchant's orders. The payer's number is masked. `released` is the USDC the order is for (it reached the
 * buyer only when `state` is COMPLETED). `completedAt`, the three shares and `settlementId` are null until the order
 * completes; `settlementId` stays null until the sweep that took Circaso's share is confirmed.
 */
@Serializable
data class RampMerchantOrderDto(
    val orderId: String,
    val createdAt: String,
    val completedAt: String? = null,
    val state: String,
    val direction: String,
    val blockchain: String,
    val maskedPhone: String,
    val paid: RampAmountDto,
    val released: RampAmountDto,
    val rate: String,
    val fee: RampAmountDto,
    val merchantShare: RampAmountDto? = null,
    val circasoShare: RampAmountDto? = null,
    val circasoShareUsdc: RampAmountDto? = null,
    val failureReason: String? = null,
    val settlementId: String? = null,
    val txHash: String? = null,
    val collectionsProvider: String,
)

@Serializable
data class RampMerchantOrdersResponse(val orders: List<RampMerchantOrderDto>, val nextCursor: String? = null)
