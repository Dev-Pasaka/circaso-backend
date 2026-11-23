package com.circaso.ramp.presentation.dto

import com.circaso.ramp.api.RampAmount
import kotlinx.serialization.Serializable

/**
 * RM19 request: `wallet` is `PAYHERO_SERVICE` (PayHero's service wallet: buying's prompt fees), `FINOQ_SERVICE`
 * (FinoQ's service wallet: selling's payout fees) or `FINOQ_PAYMENT` (FinoQ's payment wallet: what payouts spend); the
 * old `SERVICE` and `PAYMENT` still mean `PAYHERO_SERVICE` and `FINOQ_PAYMENT`. `amountMinor` is KES cents as digits,
 * whole shillings. `pin` (6 digits) only while you are offline: it unlocks your account for this top-up without putting
 * you online. There is no phone field: the prompt always goes to your own contact phone.
 */
@Serializable
data class TopUpRequest(val wallet: String = "", val amountMinor: String = "", val pin: String? = null) {
    override fun toString(): String = "TopUpRequest($wallet, ***)"
}

/**
 * One top-up (RM19). `state`: `REQUESTED` (the M-Pesa prompt is on your phone, or may be: approve it with your M-Pesa
 * PIN), `CONFIRMED` (the money is in: `confirmedBy` says whether the provider's status or the wallet's balance showed
 * it), `FAILED` (nothing was taken: `failure` says why) or `UNKNOWN` (nothing could confirm or deny it in 5 minutes:
 * check your M-Pesa messages; a late confirmation still moves it to `CONFIRMED`).
 *
 * `failure`: `CANCELLED`, `FAILED` (M-Pesa didn't complete it: a wrong PIN, not enough in M-Pesa, the prompt timed
 * out), and when **no prompt was sent**: `SERVICE_BALANCE_LOW`, `PAYER_NOT_ALLOWED` (FinoQ only prompts the number the
 * account was opened with), `CREDENTIALS_REFUSED`, `PROVIDER_UNAVAILABLE` (safe to try again), `REFUSED`.
 *
 * `balanceBefore` and `balanceAfter` are that wallet's, read before the prompt and once it was confirmed (null when
 * they couldn't be read). `added` is the difference when both are there.
 */
@Serializable
data class TopUpResponse(
    val topupId: String,
    val wallet: String,
    val state: String,
    val failure: String?,
    val amount: RampAmount,
    val maskedPhone: String,
    val confirmedBy: String?,
    val receipt: String?,
    val balanceBefore: RampAmount?,
    val balanceAfter: RampAmount?,
    val added: RampAmount?,
    val outcome: String?,
    val createdAt: String,
    val updatedAt: String,
    val closedAt: String?,
)

/** RM19's list: your latest top-ups, newest first, and the limits a top-up must fit. */
@Serializable
data class TopUpsResponse(val topups: List<TopUpResponse>, val limits: TopUpLimitsDto)

@Serializable
data class TopUpLimitsDto(val min: RampAmount, val max: RampAmount)
