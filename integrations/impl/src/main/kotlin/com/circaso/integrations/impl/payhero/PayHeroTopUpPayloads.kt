package com.circaso.integrations.impl.payhero

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The top-ups' bodies (2026-11-23). Both prompts are documented with their request and a 200; neither documents a
// refusal, so refusals are read as the payout's are ([PayoutRefusal]).

/**
 * `POST /api/v2/topup` (docs.payhero.co.ke, "Service Wallet Top Up"): the phone gets an M-Pesa prompt to pay into the
 * account's service wallet. The docs show only these two fields: no reference of ours, no callback.
 */
@Serializable
internal data class ServiceTopUpBody(
    val amount: Long,
    @SerialName("phone_number") val phoneNumber: String,
)

/**
 * `POST /api/v2/payments` as a **wallet-based** collection (docs.payhero.africa, Kenya collections): `network_code`
 * instead of `channel_id` settles the money into the account's own FinoQ wallet, the one payouts spend.
 */
@Serializable
internal data class WalletTopUpBody(
    val amount: Long,
    @SerialName("phone_number") val phoneNumber: String,
    val provider: String,
    @SerialName("network_code") val networkCode: String,
    @SerialName("account_id") val accountId: Long,
    @SerialName("external_reference") val externalReference: String,
    @SerialName("callback_url") val callbackUrl: String? = null,
)

/**
 * Both prompts' documented 200: `{"success":true,"status":"QUEUED","reference":"…","CheckoutRequestID":"ws_CO_…"}`
 * (the wallet collection also echoes `external_reference`).
 */
@Serializable
internal data class TopUpReply(
    val success: Boolean? = null,
    val status: String? = null,
    val reference: String? = null,
    @SerialName("CheckoutRequestID") val checkoutRequestId: String? = null,
    @SerialName("checkout_request_id") val checkoutRequestIdSnake: String? = null,
    @SerialName("merchant_reference") val merchantReference: String? = null,
) {
    /** The key the status lookups take, else the next best id the answer carries. */
    val lookupKey: String?
        get() = listOf(reference, merchantReference).firstOrNull { !it.isNullOrBlank() }

    val checkoutId: String?
        get() = listOf(checkoutRequestId, checkoutRequestIdSnake).firstOrNull { !it.isNullOrBlank() }
}
