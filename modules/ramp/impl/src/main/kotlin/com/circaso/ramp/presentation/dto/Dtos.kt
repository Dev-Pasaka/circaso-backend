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
 * Identity verification as the app shows it (R1's `verification`, R0's answer, the `ramp/kyc.updated` frame).
 *
 * - `status`: `NONE` (never started) | `PENDING` (a check is open and undecided: finish it, or, when `submitted`,
 *   wait) | `IN_REVIEW` (being reviewed: wait) | `VERIFIED` | `DECLINED` | `EXPIRED`.
 * - `required`: whether this network asks for verification before trading.
 * - `canStart`: whether R0 will hand out a check now: start one (`NONE`), continue the open one (`PENDING`), or try
 *   again (`DECLINED`, `EXPIRED`). False once submitted, while in review, once verified, when the reason can't be
 *   fixed by trying again, and when today's tries are used up (`retryAt` says when they come back).
 * - `submitted`: the person has finished their part and the provider hasn't decided. True for a `PENDING` check they
 *   completed (the provider is still working on it) and for `IN_REVIEW`; false everywhere else. It is what tells
 *   "submitted, waiting for the answer" from "started, not finished", which are both `PENDING`. While it is true
 *   `canStart` is false and R0 hands out no `session`.
 * - `reason`: a code to word, never the provider's text: `document`, `document_expired`, `document_unreadable`,
 *   `liveness`, `face_match`, `not_approved`, `declined`, `identity_in_use`, `blocked`, `session_expired`,
 *   `resubmission_requested`.
 */
@Serializable
data class VerificationDto(
    val status: String,
    val required: Boolean,
    val canStart: Boolean,
    val reason: String? = null,
    val retryAt: String? = null,
    val submitted: Boolean = false,
)

/**
 * An open check's link. `sessionToken` starts the provider's native SDK; `url` is its hosted page, the fallback.
 * Both stop working at `expiresAt`. The string form hides them.
 */
@Serializable
data class KycSessionDto(val sessionToken: String?, val url: String?, val expiresAt: String?) {
    override fun toString(): String = "KycSessionDto(***)"
}

/**
 * R0 (and RM3) response: the state, and `session` while a check is open for the person to do. `session` is null once
 * they have `submitted` it, as it is in review.
 */
@Serializable
data class KycResponse(
    val status: String,
    val required: Boolean,
    val canStart: Boolean,
    val reason: String? = null,
    val retryAt: String? = null,
    val submitted: Boolean = false,
    val session: KycSessionDto? = null,
)

/**
 * R1 response. `canTrade` is every gate at once; the other fields say which one is missing. `verification` is the
 * identity check in full. `kyc` is the short form older apps read: `VERIFIED`, `NONE` (not verified, whatever the
 * state) or `NOT_REQUIRED`. `blocked` is a bar on this account or its identity: nothing the app can do lifts it.
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
    val verification: VerificationDto,
    val blocked: Boolean = false,
)
