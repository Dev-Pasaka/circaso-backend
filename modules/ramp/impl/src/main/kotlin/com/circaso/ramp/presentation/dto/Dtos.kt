package com.circaso.ramp.presentation.dto

import com.circaso.ramp.api.RampAmount
import kotlinx.serialization.Serializable

/** One side of a quote request: `amountMinor` (exact) or `amount` (whole units); both only if they agree. */
@Serializable
data class AmountDto(val amountMinor: String? = null, val amount: String? = null)

/** R2 request. Give `fiat` (what you pay, whole shillings) or `crypto` (what you want to receive), not both. */
@Serializable
data class QuoteRequest(
    val direction: String = "ON_RAMP",
    val blockchain: String,
    val fiat: AmountDto? = null,
    val crypto: AmountDto? = null,
)

@Serializable
data class QuoteResponse(
    val quoteId: String,
    val direction: String,
    val network: String,
    val blockchain: String,
    val pay: RampAmount,
    val receive: RampAmount,
    /** Shillings per USDC, a decimal string. */
    val rate: String,
    /** Inside `pay`: what is paid, less this, is what buys the USDC. */
    val fee: RampAmount,
    val expiresAt: String,
)

/** R3 request: the quote to use and the M-Pesa number to prompt, which must be the account's own verified number. */
@Serializable
data class StartOnRampRequest(val quoteId: String, val phone: String)

@Serializable
data class OrderResponse(
    val orderId: String,
    val direction: String,
    val state: String,
    val network: String,
    val blockchain: String,
    val pay: RampAmount,
    val receive: RampAmount,
    val rate: String,
    val fee: RampAmount,
    val maskedPhone: String,
    val merchantName: String,
    val txHash: String? = null,
    val failureReason: String? = null,
    val expiresAt: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class OrdersResponse(val orders: List<OrderResponse>, val next: String? = null)

@Serializable
data class LimitsDto(
    val currency: String,
    val minOrder: RampAmount,
    val maxOrder: RampAmount,
    val daily: RampAmount,
)

/**
 * R1 response. `canTrade` is every gate at once; the other fields say which one is missing. `kyc` is `VERIFIED`,
 * `NONE` or `NOT_REQUIRED` (the testnet, until identity checks are built).
 */
@Serializable
data class RampStatusResponse(
    val canTrade: Boolean,
    val network: String,
    val kyc: String,
    val accountActive: Boolean,
    val blockchains: List<String>,
    val merchantOnline: Boolean,
    val offRampAvailable: Boolean,
    val openOrderId: String? = null,
    val limits: LimitsDto,
    /** The fee on a purchase as a percentage ("1.5"), inside what is paid. */
    val feePercent: String,
)
