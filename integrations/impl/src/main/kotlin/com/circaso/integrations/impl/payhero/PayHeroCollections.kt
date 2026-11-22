package com.circaso.integrations.impl.payhero

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.CollectionBalances
import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.CollectionReceipt
import com.circaso.integrations.api.CollectionRequest
import com.circaso.integrations.api.CollectionState
import com.circaso.integrations.api.CollectionStatus
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.MobileCollections
import com.circaso.integrations.api.PayoutReceipt
import com.circaso.integrations.api.PayoutRequest
import com.circaso.shared.FiatAmount
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.Base64
import kotlin.math.roundToLong

/**
 * PayHero (docs.payhero.co.ke) over the shared `HttpClients.named("payhero")` client, base
 * `https://backend.payhero.co.ke`. Every call is authenticated with the **merchant's own** API username and password
 * (HTTP Basic), passed in per call. Collections on a merchant's own till settle straight there; PayHero holds nothing.
 *
 * PayHero documents no idempotency key, so the prompt goes out without an `Idempotency-Key` header and the shared
 * client never repeats it; after a timeout the caller asks [paymentStatus]. Bodies are never logged: they carry phone
 * numbers and amounts.
 *
 * Payouts: PayHero has none today (decision 2026-11-22), so [payout], [payoutStatus] and [balance] answer
 * `NotImplemented`.
 */
class PayHeroCollections(private val http: HttpClient) : MobileCollections {
    private val log = LoggerFactory.getLogger(PayHeroCollections::class.java)

    override suspend fun requestPayment(
        credentials: CollectionCredentials,
        request: CollectionRequest,
    ): Resource<CollectionReceipt> {
        if (request.amount.currency != KES || !request.amount.isWholeUnits) {
            return rejected("M-Pesa takes whole shillings only.")
        }
        val channelId =
            credentials.collectionChannelId.toLongOrNull() ?: return rejected("The channel id isn't a number.")
        val body =
            StkPushBody(
                amount = request.amount.wholeUnits,
                phoneNumber = localNumber(request.payer),
                channelId = channelId,
                provider = MPESA,
                externalReference = request.reference,
                callbackUrl = request.callbackUrl,
            )
        return call("stk_push") {
            http.post("/api/v2/payments") {
                operation("stk_push")
                authorize(credentials)
                contentType(ContentType.Application.Json)
                setBody(payHeroJson.encodeToString(StkPushBody.serializer(), body))
            }
        }.flatMapBody("stk_push") { text ->
            val reply = payHeroJson.decodeFromString(StkPushReply.serializer(), text)
            if (reply.reference.isEmpty()) null else CollectionReceipt(reply.reference, reply.checkoutRequestId)
        }
    }

    override suspend fun paymentStatus(
        credentials: CollectionCredentials,
        providerReference: String,
    ): Resource<CollectionStatus> =
        call("transaction_status") {
            http.get("/api/v2/transaction-status") {
                operation("transaction_status")
                authorize(credentials)
                parameter("reference", providerReference)
            }
        }.flatMapBody("transaction_status") { text ->
            val reply = payHeroJson.decodeFromString(TransactionStatusReply.serializer(), text)
            stateOf(reply.status)?.let { state ->
                CollectionStatus(
                    state = state,
                    amount = reply.amount?.let { FiatAmount.ofWholeUnits(it.roundToLong(), KES) },
                    payer = reply.phoneNumber?.let(::e164),
                    reference = reply.externalReference?.takeIf { it.isNotEmpty() },
                    receipt = (reply.providerReference ?: reply.thirdPartyReference)?.takeIf { it.isNotEmpty() },
                )
            }
        }

    override suspend fun payout(credentials: CollectionCredentials, request: PayoutRequest): Resource<PayoutReceipt> =
        noPayouts()

    override suspend fun payoutStatus(
        credentials: CollectionCredentials,
        providerReference: String,
    ): Resource<CollectionStatus> = noPayouts()

    override suspend fun balance(credentials: CollectionCredentials): Resource<CollectionBalances> = noPayouts()

    override suspend fun verify(credentials: CollectionCredentials): Resource<Unit> =
        call("verify_credentials") {
            http.get("/api/v2/wallets") {
                operation("verify_credentials")
                authorize(credentials)
                parameter("wallet_type", "service_wallet")
            }
        }.flatMapBody("verify_credentials") { }

    private fun HttpRequestBuilder.authorize(credentials: CollectionCredentials) {
        val basic = Base64.getEncoder().encodeToString("${credentials.username}:${credentials.password}".toByteArray())
        header(HttpHeaders.Authorization, "Basic $basic")
    }

    /** The status and body of an answer, or the failure when PayHero couldn't be reached. */
    private suspend fun call(operation: String, send: suspend () -> HttpResponse): Resource<Pair<Int, String>> =
        try {
            val response = send()
            Resource.success(response.status.value to response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: CircuitOpenException) {
            Resource.error(IntegrationError.Unavailable(VENDOR), e)
        } catch (e: Exception) {
            log.warn("payhero unreachable: operation={} error={}", operation, e::class.simpleName)
            Resource.error(IntegrationError.Unavailable(VENDOR), e)
        }

    /** Reads a 2xx body with [read] (null or unreadable: `Unavailable`); maps any other status to our errors. */
    private fun <T> Resource<Pair<Int, String>>.flatMapBody(operation: String, read: (String) -> T?): Resource<T> =
        when (this) {
            is Resource.Error -> {
                this
            }

            is Resource.Success -> {
                val (status, text) = data
                when {
                    status in SUCCESS -> {
                        runCatching { read(text) }.getOrNull()?.let { Resource.success(it) }
                            ?: unavailable(operation, status)
                    }

                    status == UNAUTHORIZED || status == FORBIDDEN -> {
                        log.warn(
                            "payhero refused the merchant's credentials: operation={} status={}",
                            operation,
                            status,
                        )
                        rejected("The payment provider refused the account's credentials.")
                    }

                    status == TOO_MANY || status >= SERVER_ERROR -> {
                        unavailable(operation, status)
                    }

                    else -> {
                        log.warn("payhero refused: operation={} status={}", operation, status)
                        rejected("The payment provider refused the request.")
                    }
                }
            }
        }

    private fun <T> unavailable(operation: String, status: Int): Resource<T> {
        log.warn("payhero unavailable: operation={} status={}", operation, status)
        return Resource.error(IntegrationError.Unavailable(VENDOR))
    }

    private fun <T> rejected(message: String): Resource<T> = Resource.error(IntegrationError.Rejected(message))

    private fun <T> noPayouts(): Resource<T> =
        Resource.error(IntegrationError.NotImplemented("MobileCollections payouts", "payhero"))

    private fun stateOf(status: String): CollectionState? =
        when (status.uppercase()) {
            "SUCCESS" -> CollectionState.SUCCESS
            "FAILED" -> CollectionState.FAILED
            "QUEUED" -> CollectionState.QUEUED
            else -> null
        }

    companion object {
        const val BASE_URL = "https://backend.payhero.co.ke"
        const val VENDOR = "PayHero"
        private const val KES = "KES"
        private const val MPESA = "m-pesa"
        private const val KENYA = "+254"
        private val SUCCESS = 200..299
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val TOO_MANY = 429
        private const val SERVER_ERROR = 500

        /** PayHero's examples use the local form (`0712345678`); a number outside Kenya goes as its digits. */
        internal fun localNumber(phone: PhoneNumber): String =
            if (phone.e164.startsWith(KENYA)) "0" + phone.e164.removePrefix(KENYA) else phone.e164.removePrefix("+")

        /** PayHero reports a payer as `+2547…`, `2547…` or `07…`; null when it is none of them. */
        internal fun e164(raw: String): PhoneNumber? {
            val digits = raw.trim().removePrefix("+")
            val normalised = if (digits.startsWith("0")) KENYA + digits.drop(1) else "+$digits"
            return PhoneNumber.parse(normalised)
        }
    }
}
