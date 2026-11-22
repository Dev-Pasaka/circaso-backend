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
 * prompts, one of `collectionsProviders` (read-only: what this server offers). `version` rises on each change; sent back on
 * an update, a change made meanwhile answers `ramp.settings_changed`. `walletSetId`, `updatedBy` and `updatedAt` are
 * read-only too.
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
    val version: Long? = null,
    val walletSetId: String? = null,
    val updatedBy: String? = null,
    val updatedAt: String? = null,
    val collectionsProviders: List<String> = emptyList(),
)

/** `PUT /ops/ramp/settings?network=`: the settings and why they change. Read-only fields are ignored when sent. */
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
