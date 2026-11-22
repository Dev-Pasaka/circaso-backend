package com.circaso.integrations.impl.payhero

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// PayHero's request and response bodies, as PayHero names them (docs.payhero.co.ke). They never leave this package.
// Answers are read leniently: unknown fields ignored, numbers accepted as text, since the docs show both.

internal val payHeroJson =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

/** `POST /api/v2/payments`: the M-Pesa prompt. PayHero takes whole shillings. */
@Serializable
internal data class StkPushBody(
    val amount: Long,
    @SerialName("phone_number") val phoneNumber: String,
    @SerialName("channel_id") val channelId: Long,
    val provider: String,
    @SerialName("external_reference") val externalReference: String,
    @SerialName("callback_url") val callbackUrl: String,
)

@Serializable
internal data class StkPushReply(
    val success: Boolean = false,
    val status: String = "",
    val reference: String = "",
    @SerialName("CheckoutRequestID") val checkoutRequestId: String = "",
)

/**
 * `GET /api/v2/transaction-status`. The docs list the status and the references; the amount, phone and our own
 * reference are read when PayHero sends them and left null when it doesn't.
 */
@Serializable
internal data class TransactionStatusReply(
    val status: String = "",
    val reference: String = "",
    @SerialName("provider_reference") val providerReference: String? = null,
    @SerialName("third_party_reference") val thirdPartyReference: String? = null,
    @SerialName("external_reference") val externalReference: String? = null,
    val amount: Double? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
)

/** The callback PayHero posts to `callback_url`. Unsigned: only [CallbackResult.externalReference] is used. */
@Serializable
internal data class CallbackBody(val response: CallbackResult? = null)

@Serializable
internal data class CallbackResult(
    @SerialName("ExternalReference") val externalReference: String = "",
    @SerialName("CheckoutRequestID") val checkoutRequestId: String = "",
)
