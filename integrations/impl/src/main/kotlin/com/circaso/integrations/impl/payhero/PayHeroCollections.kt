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
import com.circaso.integrations.api.PayoutLookup
import com.circaso.integrations.api.PayoutRequest
import com.circaso.integrations.api.PayoutResult
import com.circaso.integrations.api.PayoutStatus
import com.circaso.integrations.api.ProviderBalances
import com.circaso.integrations.api.TopUpLookup
import com.circaso.integrations.api.TopUpRequest
import com.circaso.integrations.api.TopUpResult
import com.circaso.integrations.api.TopUpStatus
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
import java.math.BigDecimal
import java.util.Base64
import kotlin.math.roundToLong

/**
 * PayHero, now FinoQ. The **on-ramp half** lives here and is unchanged: docs.payhero.co.ke over the shared
 * `HttpClients.named("payhero")` client, base `https://backend.payhero.co.ke`. Every call is authenticated with the
 * **merchant's own** API username and password (HTTP Basic), passed in per call. Collections on a merchant's own till
 * settle straight there; PayHero holds nothing of them.
 *
 * PayHero documents no idempotency key, so the prompt goes out without an `Idempotency-Key` header and the shared
 * client never repeats it; after a timeout the caller asks [paymentStatus]. Bodies are never logged: they carry phone
 * numbers and amounts.
 *
 * The **payout half** (2026-11-23) is [PayHeroPayouts] ([sendPayout], [lookUpPayout]) and [PayHeroBalances]
 * ([balances]), over the hosts and the fallback order in [PayHeroHosts]. A merchant's own **top-ups** of their two
 * wallets are [PayHeroTopUps] ([requestTopUp], [lookUpTopUp]).
 *
 * [balance] reads the **service wallet**, the prepaid float PayHero's per-transaction fees are taken from: when it is
 * empty PayHero refuses the prompt (`400 … merchant has insufficient balance …`, seen in the first real test), which
 * [requestPayment] answers as `ServiceBalanceLow`.
 */
class PayHeroCollections(hosts: PayHeroHosts) : MobileCollections {
    private val log = LoggerFactory.getLogger(PayHeroCollections::class.java)
    private val http: HttpClient = hosts.legacy
    private val payouts = PayHeroPayouts(hosts)
    private val providerBalances = PayHeroBalances(hosts)
    private val topUps = PayHeroTopUps(hosts, payouts)

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
            if (stateOf(reply.status) == CollectionState.FAILED) {
                log.info(
                    "payhero payment failed: status={} reason={}",
                    PayHeroPayouts.word(reply.status),
                    PayHeroPayouts.masked(reply.said),
                )
            }
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

    override suspend fun sendPayout(credentials: CollectionCredentials, request: PayoutRequest): PayoutResult =
        payouts.send(credentials, request)

    override suspend fun lookUpPayout(credentials: CollectionCredentials, lookup: PayoutLookup): PayoutStatus =
        payouts.lookUp(credentials, lookup)

    override suspend fun requestTopUp(credentials: CollectionCredentials, request: TopUpRequest): TopUpResult =
        topUps.send(credentials, request)

    override suspend fun lookUpTopUp(credentials: CollectionCredentials, lookup: TopUpLookup): TopUpStatus =
        topUps.lookUp(credentials, lookup)

    override suspend fun balances(credentials: CollectionCredentials, accountId: String?): Resource<ProviderBalances> =
        providerBalances.read(credentials, accountId)

    /** The service wallet's available balance as `fees`. `payout` is not a reading here: see [balances]. */
    override suspend fun balance(credentials: CollectionCredentials): Resource<CollectionBalances> =
        serviceWallet("service_wallet_balance", credentials).flatMapBody("service_wallet_balance") { text ->
            payHeroJson.decodeFromString(WalletReply.serializer(), text).availableBalance?.let { shillings ->
                val fees = BigDecimal.valueOf(shillings).movePointRight(FiatAmount.MINOR_DIGITS).toLong()
                CollectionBalances(payout = FiatAmount(0, KES), fees = FiatAmount(fees, KES))
            }
        }

    override suspend fun verify(credentials: CollectionCredentials): Resource<Unit> =
        serviceWallet("verify_credentials", credentials).flatMapBody("verify_credentials") { }

    private suspend fun serviceWallet(operation: String, credentials: CollectionCredentials) =
        call(operation) {
            http.get("/api/v2/wallets") {
                operation(operation)
                authorize(credentials)
                parameter("wallet_type", "service_wallet")
            }
        }

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
                        val reason = refusalReason(text)
                        log.warn("payhero refused: operation={} status={} reason={}", operation, status, reason)
                        if (isBalanceRefusal(reason)) {
                            Resource.error(IntegrationError.ServiceBalanceLow())
                        } else {
                            rejected("The payment provider refused the request.")
                        }
                    }
                }
            }
        }

    private fun <T> unavailable(operation: String, status: Int): Resource<T> {
        log.warn("payhero unavailable: operation={} status={}", operation, status)
        return Resource.error(IntegrationError.Unavailable(VENDOR))
    }

    private fun <T> rejected(message: String): Resource<T> = Resource.error(IntegrationError.Rejected(message))

    private fun stateOf(status: String): CollectionState? =
        when (status.uppercase()) {
            "SUCCESS" -> CollectionState.SUCCESS
            "FAILED" -> CollectionState.FAILED
            "QUEUED" -> CollectionState.QUEUED
            else -> null
        }

    companion object {
        const val BASE_URL = PayHeroHosts.LEGACY_URL
        const val VENDOR = "PayHero"
        private const val KES = "KES"
        private const val MPESA = "m-pesa"
        private const val KENYA = "+254"
        private val SUCCESS = 200..299
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val TOO_MANY = 429
        private const val SERVER_ERROR = 500

        /**
         * Why PayHero refused, for the log: its own short message ("The passed channel is not active"), which is what
         * tells an operator what the merchant must fix. It is PayHero's wording about the request, not the request:
         * every run of digits is masked in case it echoes a number, and it is cut short. Never the body itself.
         */
        internal fun refusalReason(body: String): String {
            val refusal = runCatching { payHeroJson.decodeFromString(RefusalBody.serializer(), body) }.getOrNull()
            val said = listOfNotNull(refusal?.errorCode, refusal?.errorMessage ?: refusal?.message).joinToString(": ")
            if (said.isBlank()) return "not given"
            return said
                .replace(DIGIT_RUN, "#")
                .replace(WHITESPACE, " ")
                .trim()
                .take(MAX_REASON)
        }

        /**
         * Whether a refusal is PayHero saying the merchant's service wallet can't pay its fee ("merchant has
         * insufficient balance to allow you complete this transaction"). Read from PayHero's words because it gives
         * no code for it; the words themselves go no further than the log.
         */
        internal fun isBalanceRefusal(reason: String): Boolean = INSUFFICIENT_BALANCE.containsMatchIn(reason)

        private val INSUFFICIENT_BALANCE = Regex("""insufficient\s+(\w+\s+){0,2}balance""", RegexOption.IGNORE_CASE)
        private val DIGIT_RUN = Regex("""\d{4,}""")
        private val WHITESPACE = Regex("""\s+""")
        private const val MAX_REASON = 160

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
