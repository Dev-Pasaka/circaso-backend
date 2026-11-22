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

@Serializable
data class OnlineResponse(val online: Boolean, val until: String?)

/**
 * RM2 (also MA2's `merchant` and RM1's answer). `state`: NEW | APPLIED | APPROVED | SUSPENDED. `displayName` and
 * `contactPhone` are null until the profile is given. `wallet` is null until the ops server has made it; `float` is
 * null without a wallet or when the custodian can't be asked; `payments` is null until a payment account is stored.
 * `missing` lists what stands between the merchant and going online: `profile`, `approval`, `wallet`, `payments`,
 * `float`.
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
)

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
) {
    override fun toString(): String = "PaymentsRequest(***)"
}

/** RM5 response: the merchant's own callback address, to paste into their provider account. */
@Serializable
data class PaymentsSavedResponse(val connected: Boolean, val callbackUrl: String)

/** RM14 response. */
@Serializable
data class CallbackResponse(val callbackUrl: String)

/** RM6 request. */
@Serializable
data class GoOnlineRequest(val pin: String = "", val minutes: Int = 0) {
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
