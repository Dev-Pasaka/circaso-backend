package com.circaso.integrations.impl.payhero

import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.PayoutLookup
import com.circaso.integrations.api.PayoutStatus
import com.circaso.integrations.api.TopUpLookup
import com.circaso.integrations.api.TopUpReceipt
import com.circaso.integrations.api.TopUpRejection
import com.circaso.integrations.api.TopUpRequest
import com.circaso.integrations.api.TopUpResult
import com.circaso.integrations.api.TopUpStatus
import com.circaso.integrations.api.TopUpWallet
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.slf4j.LoggerFactory

/**
 * A merchant topping up their own two wallets at PayHero/FinoQ with an M-Pesa prompt to their own phone (2026-11-23):
 *
 * - **Service wallet** (the fees): `POST /api/v2/topup` `{amount, phone_number}` on [PayHeroHosts.LEGACY_URL], the
 *   only host that documents it (docs.payhero.co.ke, "Service Wallet Top Up"). It takes no reference of ours and no
 *   callback address.
 * - **Payment wallet** (what payouts spend): a wallet-based collection, `POST /api/v2/payments` with
 *   `provider: "sasapay"`, `network_code: "63902"` and the `account_id` (docs.payhero.africa, Kenya collections), on
 *   [PayHeroHosts.API_URL] and then [PayHeroHosts.LEGACY_URL] **only when the first provably took nothing** (it
 *   couldn't be reached, refused the credentials, or has no such route), as the payout does.
 *
 * Each prompt is sent once on a client that never retries (a repeat is a second prompt on the merchant's phone).
 * Anything short of a clear answer is [TopUpResult.Unknown]. The status is FinoQ's two lookups, shared with payouts
 * ([PayHeroPayouts.lookUp]). Bodies are never logged; each answer's layout is, once ([ShapeLog]).
 */
class PayHeroTopUps(hosts: PayHeroHosts, private val payouts: PayHeroPayouts) {
    private val log = LoggerFactory.getLogger(PayHeroTopUps::class.java)
    private val shapes = ShapeLog(log)
    private val serviceHosts = listOf(Host("payhero", hosts.legacy))
    private val paymentHosts = listOf(Host("finoq", hosts.api), Host("payhero", hosts.legacy))

    suspend fun send(credentials: CollectionCredentials, request: TopUpRequest): TopUpResult {
        val call = callFor(request) ?: return TopUpResult.Rejected(TopUpRejection.INVALID_REQUEST)
        val passedOver = mutableListOf<PassedOver>()
        for (host in call.hosts) {
            val answer =
                log.ask(call.operation) {
                    host.client.post(call.path) {
                        operation(call.operation)
                        authorize(credentials)
                        contentType(ContentType.Application.Json)
                        setBody(call.json)
                    }
                }
            when (val outcome = outcome(call, host, answer)) {
                is Outcome.Final -> return outcome.result
                is Outcome.TryNext -> passedOver += outcome.why
            }
        }
        return when {
            PassedOver.UNREACHABLE in passedOver -> TopUpResult.Unavailable
            PassedOver.CREDENTIALS in passedOver -> TopUpResult.Rejected(TopUpRejection.CREDENTIALS_REFUSED)
            else -> TopUpResult.Rejected(TopUpRejection.NOT_SUPPORTED)
        }
    }

    /**
     * FinoQ's lookups, by the provider's reference first and then ours. Only the payment wallet's prompt carries our
     * reference, so only its answers are checked against it.
     */
    suspend fun lookUp(credentials: CollectionCredentials, lookup: TopUpLookup): TopUpStatus {
        val asked = PayoutLookup(lookup.reference, lookup.providerReference)
        val checkEcho = lookup.wallet == TopUpWallet.PAYMENT
        return when (val status = payouts.lookUp(credentials, asked, TOPUP, checkEcho)) {
            is PayoutStatus.Paid -> TopUpStatus.Paid(status.providerReceipt, status.amount)
            is PayoutStatus.Failed -> TopUpStatus.Failed(status.reason)
            is PayoutStatus.NotFound -> TopUpStatus.NotFound
            PayoutStatus.Pending -> TopUpStatus.Pending
            PayoutStatus.Unknown -> TopUpStatus.Unknown
        }
    }

    private fun callFor(request: TopUpRequest): Call? {
        val amount = request.amount
        if (amount.currency != KES || !amount.isWholeUnits || amount.amountMinor <= 0) return null
        val phone = PayHeroCollections.localNumber(request.payer)
        return when (request.wallet) {
            TopUpWallet.SERVICE -> {
                val body = ServiceTopUpBody(amount.wholeUnits, phone)
                Call(
                    wallet = request.wallet,
                    hosts = serviceHosts,
                    path = SERVICE_PATH,
                    operation = "topup_service",
                    json = payHeroJson.encodeToString(ServiceTopUpBody.serializer(), body),
                )
            }

            TopUpWallet.PAYMENT -> {
                val accountId = request.accountId?.trim()?.toLongOrNull() ?: return null
                val body =
                    WalletTopUpBody(
                        amount = amount.wholeUnits,
                        phoneNumber = phone,
                        provider = WALLET_PROVIDER,
                        networkCode = MPESA_NETWORK,
                        accountId = accountId,
                        externalReference = request.reference,
                        callbackUrl = request.callbackUrl,
                    )
                Call(
                    wallet = request.wallet,
                    hosts = paymentHosts,
                    path = PAYMENT_PATH,
                    operation = "topup_payment",
                    json = payHeroJson.encodeToString(WalletTopUpBody.serializer(), body),
                )
            }
        }
    }

    private fun outcome(
        call: Call,
        host: Host,
        answer: Answer,
    ): Outcome =
        when (answer) {
            Answer.NotSent -> {
                Outcome.TryNext(PassedOver.UNREACHABLE)
            }

            Answer.Unclear -> {
                log.warn(
                    "payhero topup: wallet={} host={} outcome=unknown (no answer; a prompt may be out)",
                    call.wallet,
                    host.name,
                )
                Outcome.Final(TopUpResult.Unknown)
            }

            is Answer.Replied -> {
                shapes.note(call.operation, answer.status, answer.body)
                replied(call, host, answer)
            }
        }

    private fun replied(
        call: Call,
        host: Host,
        answer: Answer.Replied,
    ): Outcome {
        val status = answer.status
        val refusal = PayHeroPayouts.refusal(answer.body)
        val outcome =
            when {
                status in SUCCESS -> Outcome.Final(accepted(answer.body) ?: TopUpResult.Unknown)
                status == UNAUTHORIZED || status == FORBIDDEN -> Outcome.TryNext(PassedOver.CREDENTIALS)
                status in NO_SUCH_ROUTE && refusal.said.isBlank() -> Outcome.TryNext(PassedOver.NO_ROUTE)
                status == TOO_MANY -> Outcome.Final(TopUpResult.Unavailable)
                status == REQUEST_TIMEOUT || status !in CLIENT_ERROR -> Outcome.Final(TopUpResult.Unknown)
                else -> Outcome.Final(TopUpResult.Rejected(refused(refusal)))
            }
        val fields =
            refusal.invalidArguments
                ?.mapNotNull { it.field }
                ?.joinToString(",") { PayHeroPayouts.masked(it) } ?: "-"
        val line =
            arrayOf<Any>(call.wallet, host.name, status, outcome.label, PayHeroPayouts.masked(refusal.said), fields)
        if (status in SUCCESS) log.info(TOPUP_LINE, *line) else log.warn(TOPUP_LINE, *line)
        return outcome
    }

    /** A 2xx is a prompt sent only when it names it; `success: false` or no reference is "may have been sent". */
    private fun accepted(body: String): TopUpResult? {
        val reply =
            runCatching { payHeroJson.decodeFromString(TopUpReply.serializer(), body) }.getOrNull() ?: return null
        if (reply.success == false) return null
        val key = reply.lookupKey ?: reply.checkoutId ?: return null
        return TopUpResult.Accepted(TopUpReceipt(key, reply.checkoutId?.takeIf { it != key }))
    }

    private class Host(val name: String, val client: HttpClient)

    private class Call(
        val wallet: TopUpWallet,
        val hosts: List<Host>,
        val path: String,
        val operation: String,
        val json: String,
    )

    private enum class PassedOver { UNREACHABLE, CREDENTIALS, NO_ROUTE }

    private sealed interface Outcome {
        val label: String

        class Final(val result: TopUpResult) : Outcome {
            override val label: String
                get() =
                    when (result) {
                        is TopUpResult.Accepted -> "accepted"
                        is TopUpResult.Rejected -> "rejected:${result.reason}"
                        TopUpResult.Unknown -> "unknown"
                        TopUpResult.Unavailable -> "unavailable"
                    }
        }

        class TryNext(val why: PassedOver) : Outcome {
            override val label: String get() = "next_host:$why"
        }
    }

    companion object {
        const val SERVICE_PATH = "/api/v2/topup"
        const val PAYMENT_PATH = "/api/v2/payments"

        private const val TOPUP = "topup"
        private const val KES = "KES"
        private const val TOPUP_LINE = "payhero topup: wallet={} host={} status={} outcome={} reason={} fields={}"

        /** The wallet-based collection's provider and M-PESA's network code, as FinoQ's example names them. */
        private const val WALLET_PROVIDER = "sasapay"
        private const val MPESA_NETWORK = "63902"

        private val SUCCESS = 200..299
        private val CLIENT_ERROR = 400..499
        private val NO_SUCH_ROUTE = setOf(404, 405)
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val REQUEST_TIMEOUT = 408
        private const val TOO_MANY = 429

        private val LOW_SERVICE_BALANCE =
            Regex(
                """insufficient\s+(\w+\s+){0,3}(balance|funds?|float)|(service\s+)?wallet\s+balance\s+is\s+low""",
                RegexOption.IGNORE_CASE,
            )

        /** FinoQ's lower KYC tiers collect only from the account's own number; its wording is unknown, so this is wide. */
        private val NOT_OWN_PHONE =
            Regex(
                """own\s+(registered\s+)?(phone|number)|first[\s-]party|third[\s-]party|kyc\s+(level|tier)""",
                RegexOption.IGNORE_CASE,
            )

        /** A 4xx in our words, from FinoQ's `error_code` and `error_message`: it gives no finer code. */
        internal fun refused(refusal: PayoutRefusal): TopUpRejection {
            val said = refusal.said
            return when {
                NOT_OWN_PHONE.containsMatchIn(said) -> {
                    TopUpRejection.PAYER_NOT_ALLOWED
                }

                LOW_SERVICE_BALANCE.containsMatchIn(said) -> {
                    TopUpRejection.SERVICE_BALANCE_LOW
                }

                !refusal.invalidArguments.isNullOrEmpty() || refusal.errorCode.equals("invalid_argument", true) -> {
                    TopUpRejection.INVALID_REQUEST
                }

                else -> {
                    TopUpRejection.REFUSED
                }
            }
        }
    }
}
