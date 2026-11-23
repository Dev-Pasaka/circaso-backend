package com.circaso.ramp.presentation.dto

import com.circaso.ramp.api.RampAmount
import kotlinx.serialization.Serializable

/** MA1 request. */
@Serializable
data class SignInCodeRequest(val email: String = "")

/** MA1 response: the same whoever asked. `retryAfterSeconds` is how long before another code may be asked for. */
@Serializable
data class SignInCodeResponse(val sent: Boolean, val retryAfterSeconds: Long)

/** MA2 request. The string form hides the code. */
@Serializable
data class VerifyCodeRequest(val email: String = "", val code: String = "") {
    override fun toString(): String = "VerifyCodeRequest(***)"
}

/** MA2 response. `sessionToken` goes in `X-Merchant-Session` on every later call; it is shown once. */
@Serializable
data class SignedInResponse(val sessionToken: String, val expiresAt: String, val merchant: MerchantResponse) {
    override fun toString(): String = "SignedInResponse(***)"
}

@Serializable
data class MerchantStepsDto(
    val profile: Boolean,
    /** Identity verified, or not asked for on this network. */
    val kyc: Boolean,
    val approved: Boolean,
    val wallet: Boolean,
    val payments: Boolean,
)

@Serializable
data class MerchantWalletDto(val blockchain: String, val address: String)

@Serializable
data class MerchantFloatDto(val balance: RampAmount, val reserved: RampAmount, val free: RampAmount)

@Serializable
data class MerchantPaymentsDto(
    val provider: String,
    val connected: Boolean,
    val channelId: String?,
    val callbackUrl: String?,
)

/**
 * The merchant's trading session (RM2's `online`, and RM6's answer). `online` and `until` are as they always were;
 * the rest came with sessions that last until the merchant stops them (2026-11-23).
 *
 * - `state`: `ONLINE` or `OFFLINE`, the same fact as `online`.
 * - `until`: a timed window's end; null while offline **and** while online until stopped (`openEnded` true): show
 *   "Online until you go offline".
 * - `keysHeld`: whether the server holds the unlocked payment account. The keys live only in its memory, so a restart
 *   (a deploy) ends every session, open-ended ones too: then `keysHeld` is false and `offlineReason` is `RESTART`
 *   ("You were taken offline when Circaso restarted: go online again").
 * - `since`: when the current session began (null while offline).
 * - `offlineReason`: while offline, why the last session ended: `STOPPED` (you went offline), `SIGNED_OUT`,
 *   `SUSPENDED`, `PROVIDER_BALANCE` (your provider refused a prompt for its service balance), `WINDOW_ENDED`, or
 *   `RESTART`; null when none is known. `offlineAt` is when.
 */
@Serializable
data class OnlineResponse(
    val online: Boolean,
    val until: String?,
    val state: String,
    val openEnded: Boolean,
    val keysHeld: Boolean,
    val since: String?,
    val offlineReason: String?,
    val offlineAt: String?,
)

/**
 * Something at the merchant's payment provider only they can put right. `code`: `SERVICE_BALANCE` (the provider
 * refused a prompt because the prepaid service wallet its fees are taken from is empty: "Top up your PayHero service
 * wallet"). `since` is when it was noticed.
 */
@Serializable
data class MerchantPaymentsIssueDto(val code: String, val since: String?)

/**
 * RM2 (also MA2's `merchant` and RM1's answer). `state`: NEW | APPLIED | APPROVED | SUSPENDED. `displayName` and
 * `contactPhone` are null until the profile is given. `wallet` is null until the ops server has made it; `float` is
 * null without a wallet or when the custodian can't be asked; `payments` is null until a payment account is stored.
 * `payments.connected` is false (and `missing` says `payments`) when the stored account is at another provider than
 * the one the network uses now: connect again with RM5.
 * `missing` lists what stands between the merchant and going online: `profile`, `kyc`, `approval`, `wallet`,
 * `payments`, `float`, `provider_balance`. `kyc` (identity verification, RM3) comes before approval: staff can't
 * approve a merchant who hasn't passed it. The last comes with `paymentsIssue` and takes the merchant offline; it
 * doesn't make
 * `canGoOnline` false, because going online again (RM6) is what checks the balance and clears it.
 */
@Serializable
data class MerchantResponse(
    val merchantId: String,
    val email: String,
    val displayName: String?,
    val contactPhone: String?,
    val state: String,
    val network: String,
    val steps: MerchantStepsDto,
    val wallet: MerchantWalletDto?,
    val float: MerchantFloatDto?,
    val payments: MerchantPaymentsDto?,
    val pinSet: Boolean,
    val online: OnlineResponse,
    val canGoOnline: Boolean,
    val missing: List<String>,
    val paymentsIssue: MerchantPaymentsIssueDto? = null,
    /** The merchant's identity verification on this network: start or continue it with RM3. */
    val kyc: VerificationDto,
    /** `INDIVIDUAL` or `BUSINESS`, set by Circaso: it decides the order limit you start with (RM16 shows the limit). */
    val merchantType: String = "INDIVIDUAL",
    /** Which directions you take orders in: your own switches (RM17). */
    val offers: MerchantOffersDto = MerchantOffersDto(onRamp = true, offRamp = false),
    /** Selling (2026-11-23): your payout account and your balances at the provider. */
    val offRamp: MerchantOffRampDto? = null,
    /** Whether your FinoQ webhook signing secret is set (RM5c). The secret itself is never sent back. */
    val webhookSecretSet: Boolean = false,
    /** When it was last set (ISO-8601 UTC); null when it isn't set. */
    val webhookSecretUpdatedAt: String? = null,
)

/** One balance at the provider, and the call it was read from (for support: which of the provider's wallets). */
@Serializable
data class KesBalanceDto(val amount: RampAmount, val source: String)

/**
 * RM2's `offRamp` (selling, 2026-11-23). `accountId` is your account's number on the FinoQ dashboard: payouts need it
 * (set it with `PUT /ramp/merchant/payments/account`). `payoutBalance` is what payouts spend and `feeBalance` what the
 * provider's fees are taken from; **null means unknown** (we couldn't read it), never zero: show "unknown", not "0".
 * They are read live while you are online (`balancesLive` true; at most every 30 seconds), otherwise they are the last
 * reading, from `balancesReadAt`. `held` is what your open sales hold of the payout balance (their payouts and fees).
 * `flags`: `payout_account_missing`, `payout_balance_low` (below the network's line: top up), `service_balance_low`
 * (the fee balance is below the floor), `provider_balance` (the provider refused a prompt for your fee balance),
 * `payout_balance` (the provider refused a payout for your payout balance) and `payout_refused` (it refused a payout
 * outright: check your account); with either of the last two no sale is routed to you until you go online again.
 */
@Serializable
data class MerchantOffRampDto(
    val offered: Boolean,
    val accountId: String?,
    val payoutBalance: KesBalanceDto?,
    val feeBalance: KesBalanceDto?,
    val balancesLive: Boolean,
    val balancesReadAt: String?,
    val held: RampAmount,
    val payoutIssue: MerchantPaymentsIssueDto?,
    val flags: List<String>,
)

/**
 * `PUT /ramp/merchant/payments/webhook-secret` request: the webhook signing secret from your FinoQ dashboard, as shown
 * there. The string form hides it.
 */
@Serializable
data class WebhookSecretRequest(val secret: String = "") {
    override fun toString(): String = "WebhookSecretRequest(***)"
}

/** `PUT /ramp/merchant/payments/account` request: the account's number on the FinoQ dashboard, digits only. */
@Serializable
data class PayoutAccountRequest(val accountId: String = "")

/** `POST /ramp/merchant/test-payout` request: KES cents as a string, 1000 to 10000 (KES 10 to 100), whole shillings. */
@Serializable
data class TestPayoutRequest(val amountMinor: String = "")

/**
 * A test payout to your own contact phone (2026-11-23). `state`: `SENDING` (being sent: if it never answered, it may
 * have gone out), `ACCEPTED` (the provider took it; not paid yet), `UNKNOWN` (no clear answer: it may have gone out,
 * and it is looked up, never sent again), `PAID`, `FAILED`, `NOT_SENT` (refused, or the provider couldn't be reached:
 * nothing left your account) and `NOT_FOUND` (the provider says it knows no such payout: check your M-Pesa
 * statement). `outcome` says what the provider answered in our words (`accepted`, `rejected:INVALID_REQUEST`,
 * `insufficient_balance`, `unavailable`, `unknown`, `failed:<its word>`, `looked_up:PENDING`, `paid`).
 * `providerFee` is the provider's published charge for the amount; `charged` is what your payout balance actually
 * fell by beyond the amount (before − after − amount), when both readings exist. Read it again (`GET`) until it
 * leaves `SENDING`, `ACCEPTED` and `UNKNOWN`.
 */
@Serializable
data class TestPayoutResponse(
    val testId: String,
    val state: String,
    val amount: RampAmount,
    val maskedPhone: String,
    val outcome: String?,
    val providerReference: String?,
    val receipt: String?,
    val paid: RampAmount?,
    val providerFee: KesBalanceDto?,
    val balanceBefore: KesBalanceDto?,
    val balanceAfter: KesBalanceDto?,
    val charged: RampAmount?,
    val createdAt: String,
    val updatedAt: String,
)

/** `onRamp`: people buying USDC from you with M-Pesa. `offRamp`: people selling USDC to you for M-Pesa. */
@Serializable
data class MerchantOffersDto(val onRamp: Boolean, val offRamp: Boolean)

/** RM17 request: either switch may be left out, and then stays as it is. */
@Serializable
data class MerchantOffersRequest(val onRamp: Boolean? = null, val offRamp: Boolean? = null)

/** The statistics window of RM16: your last `orders` finished orders within `days` days. */
@Serializable
data class PerformanceWindowDto(val orders: Int, val days: Int)

/**
 * RM16's newcomer line. `applies` is true while you have completed fewer than `until` trades: new merchants are given
 * extra turns so they can build a record. `tradesLeft` is how many more completed trades until it ends.
 */
@Serializable
data class NewcomerDto(val applies: Boolean, val until: Int, val tradesLeft: Int)

/**
 * RM16: how you are doing in one direction, in the terms orders are shared out by.
 *
 * `finished` is every order of yours that ended in the window; `counted` is the ones a rate is taken over:
 * `completed` and `failed`. **`failed` is only what was down to you**: your payment provider refused the prompt, your
 * prepaid balance there was empty, you were offline when the prompt had to go out, or (selling) a payout failed or
 * was missed. `notCounted` is everything else, and never moves a rate: a buyer who ignored or cancelled the prompt, a
 * cancelled order, the provider being out of reach. The two rates are percentages of `counted` (null with nothing
 * counted yet).
 *
 * `medianSeconds` and `p90Seconds` are how long your completed orders took from the payment being confirmed to the
 * USDC arriving (selling: from the USDC arriving to the payout). `speed`: `NO_DATA`, `ON_TARGET` (the median is
 * within `targetSeconds`), `WITHIN_SLA` (within `slaSeconds`) or `SLOW`.
 */
@Serializable
data class PerformanceDto(
    val direction: String,
    val offered: Boolean,
    val orderLimit: RampAmount,
    val window: PerformanceWindowDto,
    val finished: Int,
    val counted: Int,
    val completed: Int,
    val failed: Int,
    val notCounted: Int,
    val completionRatePercent: String?,
    val failureRatePercent: String?,
    val medianSeconds: Long?,
    val p90Seconds: Long?,
    val targetSeconds: Int,
    val slaSeconds: Int,
    val speed: String,
    val trades: Int,
    val newcomer: NewcomerDto,
)

/** RM16 response: your own figures only, one entry per direction (`ON_RAMP`, `OFF_RAMP`). */
@Serializable
data class PerformanceResponse(val network: String, val merchantType: String, val directions: List<PerformanceDto>)

/** RM1 request. */
@Serializable
data class ProfileRequest(val displayName: String = "", val contactPhone: String = "")

/** RM5 request. The string form hides all of it: it never reaches a log line. */
@Serializable
data class PaymentsRequest(
    val username: String = "",
    val password: String = "",
    val channelId: String = "",
    val pin: String = "",
    /** Selling (2026-11-23): your account's number on the FinoQ dashboard, digits only; left out stays as it is. */
    val accountId: String? = null,
) {
    override fun toString(): String = "PaymentsRequest(***)"
}

/** RM5 response: the merchant's own callback address, to paste into their provider account. */
@Serializable
data class PaymentsSavedResponse(val connected: Boolean, val callbackUrl: String)

/** RM14 response. */
@Serializable
data class CallbackResponse(val callbackUrl: String)

/**
 * RM6 request: the PIN, and either `minutes` (a window of 15 to 720) or `untilStopped: true` (online until you go
 * offline yourself, with no end time; a server restart still ends it). Not both.
 */
@Serializable
data class GoOnlineRequest(val pin: String = "", val minutes: Int? = null, val untilStopped: Boolean = false) {
    override fun toString(): String = "GoOnlineRequest(***)"
}

/** RM9 request. */
@Serializable
data class ChangePinRequest(val currentPin: String = "", val newPin: String = "") {
    override fun toString(): String = "ChangePinRequest(***)"
}

/** RM9 response: `{}`. */
@Serializable
class PinChangedResponse

/** One of the merchant's orders (RM7): the payer's number masked. */
@Serializable
data class MerchantOrderDto(
    val orderId: String,
    val direction: String,
    val state: String,
    val pay: RampAmount,
    val receive: RampAmount,
    val fee: RampAmount,
    val maskedPhone: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class MerchantOrdersResponse(val orders: List<MerchantOrderDto>, val next: String? = null)

/** One daily sweep of Circaso's share. `day` is the Nairobi day it closed; `state`: PENDING | CONFIRMED | FAILED. */
@Serializable
data class SweepDto(
    val settlementId: String,
    val day: String,
    val amount: RampAmount,
    val state: String,
    val orders: Int,
    val txHash: String?,
)

/**
 * RM8. `from` and `to` are Nairobi calendar days, inclusive. `fees` is inside `collected`; `yourShare` and
 * `circasoShare` add up to `fees`; `swept` and `held` add up to `circasoShareUsdc`.
 */
@Serializable
data class EarningsResponse(
    val from: String,
    val to: String,
    val network: String,
    val orders: Int,
    val collected: RampAmount,
    val sold: RampAmount,
    val fees: RampAmount,
    val yourShare: RampAmount,
    val circasoShare: RampAmount,
    val circasoShareUsdc: RampAmount,
    val swept: RampAmount,
    val held: RampAmount,
    val feeRatePercent: String,
    val yourSharePercent: String,
    val sweeps: List<SweepDto>,
)

/** One completed exchange (RM15). `settlementId` is null while Circaso's share is still held in the float. */
@Serializable
data class StatementLineDto(
    val orderId: String,
    val completedAt: String,
    val maskedPhone: String,
    val paid: RampAmount,
    val released: RampAmount,
    val rate: String,
    val fee: RampAmount,
    val yourShare: RampAmount,
    val circasoShare: RampAmount,
    val circasoShareUsdc: RampAmount,
    val settlementId: String?,
    val txHash: String?,
)

@Serializable
data class StatementTotalsDto(
    val orders: Int,
    val collected: RampAmount,
    val sold: RampAmount,
    val fees: RampAmount,
    val yourShare: RampAmount,
    val circasoShare: RampAmount,
    val circasoShareUsdc: RampAmount,
)

/** RM15: newest first; `totals` are for the whole period, whatever the page. */
@Serializable
data class StatementResponse(
    val from: String,
    val to: String,
    val network: String,
    val lines: List<StatementLineDto>,
    val totals: StatementTotalsDto,
    val next: String? = null,
)
