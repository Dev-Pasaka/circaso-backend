package com.circaso.integrations.impl.payhero

import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.PayoutLookup
import com.circaso.integrations.api.PayoutReceipt
import com.circaso.integrations.api.PayoutReference
import com.circaso.integrations.api.PayoutRejection
import com.circaso.integrations.api.PayoutRequest
import com.circaso.integrations.api.PayoutResult
import com.circaso.integrations.api.PayoutStatus
import com.circaso.shared.FiatAmount
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.slf4j.LoggerFactory
import java.math.RoundingMode

/**
 * PayHero/FinoQ payouts to a phone: `POST /api/v2/withdraw` ("legacy V1, kept for backward compatibility", and the
 * only documented way to pay an M-Pesa number from a wallet) and the two status lookups. The host order is
 * [PayHeroHosts]'s.
 *
 * **Built on incomplete docs, so it assumes the worst.** Nothing says a repeated `external_reference` is paid once,
 * so a payout is sent once, on clients that never retry, and anything short of a clear answer is
 * [PayoutResult.Unknown]: a timeout, a 5xx ("may still have started the payment", PayHero's own words) and a 200 that
 * names no payout. A 4xx is "a permanent rejection": nothing was sent. The status answers' fields are undocumented,
 * so they are read by every name seen so far and a status word this class doesn't know is [PayoutStatus.Unknown],
 * never paid or failed.
 *
 * Bodies are never logged (they carry phone numbers and amounts). What is logged instead: the provider's own short
 * refusal with every digit masked, and through [ShapeLog] the layout of each answer the first time it is seen.
 */
class PayHeroPayouts(hosts: PayHeroHosts) {
    private val log = LoggerFactory.getLogger(PayHeroPayouts::class.java)
    private val shapes = ShapeLog(log)

    private val withdrawHosts = listOf(Host("finoq", hosts.payoutApi), Host("payhero", hosts.payoutLegacy))
    private val api = hosts.api
    private val legacy = hosts.legacy

    suspend fun send(credentials: CollectionCredentials, request: PayoutRequest): PayoutResult {
        val body = withdrawBody(request) ?: return PayoutResult.Rejected(PayoutRejection.INVALID_REQUEST)
        val json = payHeroJson.encodeToString(WithdrawBody.serializer(), body)
        val passedOver = mutableListOf<PassedOver>()
        for (host in withdrawHosts) {
            val answer =
                log.ask("withdraw") {
                    host.client.post(WITHDRAW_PATH) {
                        operation("withdraw")
                        authorize(credentials)
                        contentType(ContentType.Application.Json)
                        setBody(json)
                    }
                }
            when (val outcome = withdrawOutcome(host, answer)) {
                is WithdrawOutcome.Final -> return outcome.result
                is WithdrawOutcome.TryNext -> passedOver += outcome.why
            }
        }
        return whenNoHostTookIt(passedOver)
    }

    suspend fun lookUp(credentials: CollectionCredentials, lookup: PayoutLookup): PayoutStatus {
        val keys =
            listOfNotNull(
                lookup.providerReference?.takeIf { it.isNotBlank() }?.let { it to PayoutReference.PROVIDER },
                lookup.reference.takeIf { it.isNotBlank() }?.let { it to PayoutReference.OURS },
            )
        var unread = keys.isEmpty()
        for ((key, _) in keys) {
            for (source in LookupSource.entries) {
                when (val found = read(source, ask(source, credentials, key), lookup.reference)) {
                    is Looked.Found -> return found.status
                    Looked.NotKnown -> Unit
                    Looked.Unread -> unread = true
                }
            }
        }
        return if (unread) PayoutStatus.Unknown else PayoutStatus.NotFound(keys.first().second)
    }

    private fun withdrawOutcome(host: Host, answer: Answer): WithdrawOutcome =
        when (answer) {
            Answer.NotSent -> {
                WithdrawOutcome.TryNext(PassedOver.UNREACHABLE)
            }

            Answer.Unclear -> {
                log.warn("payhero payout: host={} outcome=unknown (no answer; it may have been sent)", host.name)
                WithdrawOutcome.Final(PayoutResult.Unknown)
            }

            is Answer.Replied -> {
                shapes.note("withdraw", answer.status, answer.body)
                replied(host, answer)
            }
        }

    private fun replied(host: Host, answer: Answer.Replied): WithdrawOutcome {
        val status = answer.status
        val refusal = refusal(answer.body)
        val outcome =
            when {
                status in SUCCESS -> WithdrawOutcome.Final(accepted(answer.body) ?: PayoutResult.Unknown)
                status == UNAUTHORIZED || status == FORBIDDEN -> WithdrawOutcome.TryNext(PassedOver.CREDENTIALS)
                status in NO_SUCH_ROUTE && refusal.said.isBlank() -> WithdrawOutcome.TryNext(PassedOver.NO_ROUTE)
                status == TOO_MANY -> WithdrawOutcome.Final(PayoutResult.Unavailable)
                status == REQUEST_TIMEOUT || status !in CLIENT_ERROR -> WithdrawOutcome.Final(PayoutResult.Unknown)
                else -> WithdrawOutcome.Final(refused(refusal))
            }
        val fields = refusal.invalidArguments?.mapNotNull { it.field }?.joinToString(",") { masked(it) } ?: "-"
        val line = arrayOf<Any>(host.name, status, outcome.label, masked(refusal.said), fields)
        if (status in SUCCESS) log.info(PAYOUT_LINE, *line) else log.warn(PAYOUT_LINE, *line)
        return outcome
    }

    /** No host gave a final answer, and every one of them provably took nothing. */
    private fun whenNoHostTookIt(passedOver: List<PassedOver>): PayoutResult =
        when {
            PassedOver.UNREACHABLE in passedOver -> PayoutResult.Unavailable
            PassedOver.CREDENTIALS in passedOver -> PayoutResult.Rejected(PayoutRejection.CREDENTIALS_REFUSED)
            else -> PayoutResult.Rejected(PayoutRejection.NOT_SUPPORTED)
        }

    private fun accepted(body: String): PayoutResult? {
        val reply =
            runCatching { payHeroJson.decodeFromString(WithdrawReply.serializer(), body) }.getOrNull() ?: return null
        val key = reply.lookupKey ?: reply.otherId ?: return null
        return PayoutResult.Accepted(PayoutReceipt(key, reply.otherId?.takeIf { it != key }))
    }

    private suspend fun ask(
        source: LookupSource,
        credentials: CollectionCredentials,
        key: String,
    ): Answer =
        when (source) {
            LookupSource.API -> {
                log.ask(source.operation) {
                    api.post(API_STATUS_PATH) {
                        operation(source.operation)
                        authorize(credentials)
                        contentType(ContentType.Application.Json)
                        setBody(payHeroJson.encodeToString(StatusRequest.serializer(), StatusRequest(key)))
                    }
                }
            }

            LookupSource.LEGACY -> {
                log.ask(source.operation) {
                    legacy.get(LEGACY_STATUS_PATH) {
                        operation(source.operation)
                        authorize(credentials)
                        parameter("reference", key)
                    }
                }
            }
        }

    private fun read(
        source: LookupSource,
        answer: Answer,
        ours: String,
    ): Looked {
        if (answer !is Answer.Replied) return Looked.Unread
        shapes.note(source.operation, answer.status, answer.body)
        val reply =
            runCatching {
                payHeroJson.decodeFromString(
                    PayoutLookupReply.serializer(),
                    answer.body,
                )
            }.getOrNull()
        val looked =
            when {
                answer.status in SUCCESS -> reply?.let { found(it, ours) } ?: Looked.Unread
                answer.status == NOT_FOUND -> Looked.NotKnown
                answer.status in CLIENT_ERROR && saysNotFound(refusal(answer.body).said) -> Looked.NotKnown
                else -> Looked.Unread
            }
        log.info("payhero payout status: source={} status={} read={}", source.operation, answer.status, looked.label)
        return looked
    }

    private fun found(reply: PayoutLookupReply, ours: String): Looked {
        for (candidate in reply.candidates) {
            val status = candidate.statusWords.firstNotNullOfOrNull { statusOf(it, candidate) } ?: continue
            val echoed = candidate.ours ?: reply.ours
            if (echoed != null && echoed != ours) {
                log.warn("payhero payout status: the answer is about another reference; not used")
                return Looked.Unread
            }
            return Looked.Found(if (status is PayoutStatus.Paid) status.copy(reference = echoed) else status)
        }
        if (saysNotFound(reply.said) || reply.candidates.any { saysNotFound(it.said) }) return Looked.NotKnown
        val words = reply.candidates.flatMap { it.statusWords }.joinToString(",") { word(it) }
        log.warn("payhero payout status: no status this client knows (words={})", words.ifEmpty { "-" })
        return Looked.Unread
    }

    private fun statusOf(raw: String, reply: PayoutLookupReply): PayoutStatus? =
        when (val word = word(raw)) {
            in PAID_WORDS -> PayoutStatus.Paid(reply.receipt, amountOf(reply.paidAmount, reply.currency), null)
            in FAILED_WORDS -> PayoutStatus.Failed(word)
            in PENDING_WORDS -> PayoutStatus.Pending
            else -> null
        }

    private fun withdrawBody(request: PayoutRequest): WithdrawBody? {
        if (request.amount.currency != KES || !request.amount.isWholeUnits || request.amount.amountMinor <= 0) {
            return null
        }
        val accountId = request.accountId.trim().toLongOrNull() ?: return null
        val number = PayHeroCollections.localNumber(request.payee)
        return WithdrawBody(
            externalReference = request.reference,
            accountId = accountId,
            amount = request.amount.wholeUnits,
            phoneNumber = number,
            accountNumber = number,
            networkCode = MPESA_NETWORK,
            channel = PHONE_CHANNEL,
            recipientType = MSISDN,
            callbackUrl = request.callbackUrl,
        )
    }

    private class Host(val name: String, val client: HttpClient)

    private enum class PassedOver { UNREACHABLE, CREDENTIALS, NO_ROUTE }

    private sealed interface WithdrawOutcome {
        val label: String

        class Final(val result: PayoutResult) : WithdrawOutcome {
            override val label: String
                get() =
                    when (result) {
                        is PayoutResult.Accepted -> "accepted"
                        is PayoutResult.Rejected -> "rejected:${result.reason}"
                        PayoutResult.InsufficientBalance -> "insufficient_balance"
                        PayoutResult.Unknown -> "unknown"
                        PayoutResult.Unavailable -> "unavailable"
                    }
        }

        class TryNext(val why: PassedOver) : WithdrawOutcome {
            override val label: String get() = "next_host:$why"
        }
    }

    /** The two documented lookups, in the order they are tried. */
    private enum class LookupSource(val operation: String) {
        API("payout_status"),
        LEGACY("payout_status_legacy"),
    }

    private sealed interface Looked {
        val label: String

        class Found(val status: PayoutStatus) : Looked {
            override val label: String get() = status::class.simpleName ?: "found"
        }

        data object NotKnown : Looked {
            override val label: String = "not_found"
        }

        data object Unread : Looked {
            override val label: String = "unread"
        }
    }

    companion object {
        const val WITHDRAW_PATH = "/api/v2/withdraw"
        const val API_STATUS_PATH = "/api/global/transaction-status"
        const val LEGACY_STATUS_PATH = "/api/v2/transaction-status"

        private const val KES = "KES"
        private const val PAYOUT_LINE = "payhero payout: host={} status={} outcome={} reason={} fields={}"

        /** M-PESA's network code in PayHero's examples. */
        private const val MPESA_NETWORK = "63902"
        private const val PHONE_CHANNEL = "phone"
        private const val MSISDN = "MSISDN"

        private val SUCCESS = 200..299
        private val CLIENT_ERROR = 400..499
        private val NO_SUCH_ROUTE = setOf(404, 405)
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val REQUEST_TIMEOUT = 408
        private const val TOO_MANY = 429

        private val PAID_WORDS = setOf("SUCCESS", "SUCCESSFUL", "SUCCEEDED", "COMPLETED", "COMPLETE", "PAID")
        private val FAILED_WORDS =
            setOf("FAILED", "FAILURE", "FAIL", "CANCELLED", "CANCELED", "REJECTED", "DECLINED", "REVERSED", "EXPIRED")
        private val PENDING_WORDS =
            setOf("QUEUED", "PENDING", "PROCESSING", "INITIATED", "IN_PROGRESS", "INPROGRESS", "SUBMITTED", "ACCEPTED")

        private val BELOW_COST = Regex("""cater\s+for\s+(the\s+)?cost""", RegexOption.IGNORE_CASE)
        private val COST = Regex("""\(\s*(\d+(?:\.\d+)?)\s*\)""")
        private val LOW_BALANCE =
            Regex(
                """insufficient\s+(\w+\s+){0,3}(balance|funds?|float)|""" +
                    """(balance|funds?|float)\s+(is\s+|are\s+)?(insufficient|too\s+low|not\s+enough)|""" +
                    """not\s+enough\s+(\w+\s+){0,3}(balance|funds?|float)|low\s+(\w+\s+)?balance""",
                RegexOption.IGNORE_CASE,
            )
        private val NOT_FOUND_WORDS =
            Regex(
                """not\s+found|no\s+(such\s+)?(transaction|record|payment|request)|does\s*(n.t|not)\s+exist|""" +
                    """unknown\s+(transaction|reference|request)""",
                RegexOption.IGNORE_CASE,
            )
        private val DIGITS = Regex("""\d+""")
        private val WHITESPACE = Regex("""\s+""")
        private val NOT_A_WORD = Regex("""[^A-Z_]""")
        private const val MAX_REASON = 160
        private const val MAX_WORD = 24

        internal fun refusal(body: String): PayoutRefusal =
            runCatching { payHeroJson.decodeFromString(PayoutRefusal.serializer(), body) }.getOrNull()
                ?: PayoutRefusal()

        /**
         * A 4xx in our words, read from PayHero's `error_code` and `error_message` because it gives no finer code.
         * The order matters: "the transaction amount is insufficient as it wont cater for cost" is about the amount,
         * not the merchant's balance.
         */
        internal fun refused(refusal: PayoutRefusal): PayoutResult {
            val said = refusal.said
            return when {
                BELOW_COST.containsMatchIn(said) -> {
                    PayoutResult.Rejected(PayoutRejection.AMOUNT_BELOW_COST, costIn(said))
                }

                LOW_BALANCE.containsMatchIn(said) -> {
                    PayoutResult.InsufficientBalance
                }

                !refusal.invalidArguments.isNullOrEmpty() || refusal.errorCode.equals("invalid_argument", true) -> {
                    PayoutResult.Rejected(PayoutRejection.INVALID_REQUEST)
                }

                else -> {
                    PayoutResult.Rejected(PayoutRejection.REFUSED)
                }
            }
        }

        /** PayHero names its charge in brackets, "cost: (4.62)": handed to the caller, never logged. */
        private fun costIn(said: String): FiatAmount? = amountOf(COST.find(said)?.groupValues?.get(1), KES)

        internal fun saysNotFound(said: String): Boolean = NOT_FOUND_WORDS.containsMatchIn(said)

        /** Shillings as PayHero writes them (a number or text, with or without cents), to exact minor units. */
        internal fun amountOf(text: String?, currency: String?): FiatAmount? =
            runCatching {
                val minor =
                    text!!
                        .trim()
                        .toBigDecimal()
                        .movePointRight(FiatAmount.MINOR_DIGITS)
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact()
                FiatAmount(minor, currency?.trim()?.uppercase()?.takeIf { it.length == CURRENCY_LENGTH } ?: KES)
            }.getOrNull()

        /** A provider's words about a request, safe for a log line: every digit masked, one line, cut short. */
        internal fun masked(said: String): String =
            said
                .replace(DIGITS, "#")
                .replace(WHITESPACE, " ")
                .trim()
                .take(MAX_REASON)
                .ifEmpty { "-" }

        /** A status word as a bare upper-case token, so it can be compared, and logged without carrying anything. */
        internal fun word(raw: String): String =
            raw
                .trim()
                .uppercase()
                .replace(' ', '_')
                .replace('-', '_')
                .replace(NOT_A_WORD, "")
                .take(MAX_WORD)

        private const val CURRENCY_LENGTH = 3
    }
}
