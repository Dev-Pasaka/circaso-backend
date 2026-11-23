package com.circaso.integrations.impl.payhero

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.infrastructure.api.HttpClients
import com.circaso.integrations.api.CollectionCredentials
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.util.Base64

/**
 * PayHero is becoming FinoQ and is served from three hosts, and its docs don't say which calls have moved (nor
 * whether one account's API username and password work on all of them). So every call family has an **order of
 * hosts to try**, written down here and nowhere else:
 *
 * - **Collect (STK push), its status, `verify`:** [LEGACY_URL] only. The on-ramp is unchanged.
 * - **Payout (`POST /api/v2/withdraw`):** [API_URL], then [LEGACY_URL]. It moves on only when the host **provably
 *   took nothing**: it couldn't be reached, refused the credentials, or has no such route.
 * - **Payout status:** [API_URL] `POST /api/global/transaction-status`, then [LEGACY_URL]
 *   `GET /api/v2/transaction-status`, when the first gives no status.
 * - **Fee balance (service wallet):** [LEGACY_URL] wallets, then [AUTH_URL] account, when the first gives no balance.
 * - **Payout balance:** [LEGACY_URL] wallets (`payment_wallet`), then [API_URL] account balance, likewise.
 *
 * The two payout clients are separate names so that they are **never retried**: the shared client repeats a POST on a
 * `429` or `503`, and a payout repeated is a payout paid twice.
 */
class PayHeroHosts(
    val legacy: HttpClient,
    val api: HttpClient,
    val auth: HttpClient,
    val payoutApi: HttpClient,
    val payoutLegacy: HttpClient,
) {
    companion object {
        /** PayHero's original API (docs.payhero.co.ke): what the on-ramp has always called. */
        const val LEGACY_URL = "https://backend.payhero.co.ke"

        /** "FinoQ API 2.0.0" (docs.payhero.africa): payments, payouts, status, balances. */
        const val API_URL = "https://api.finoq.africa"

        /** FinoQ's accounts ("teams") host. */
        const val AUTH_URL = "https://auth.payhero.africa"

        fun of(clients: HttpClients): PayHeroHosts =
            PayHeroHosts(
                legacy = clients.named("payhero") { baseUrl = LEGACY_URL },
                api = clients.named("finoq") { baseUrl = API_URL },
                auth = clients.named("finoq-auth") { baseUrl = AUTH_URL },
                payoutApi =
                    clients.named("finoq-payout") {
                        baseUrl = API_URL
                        maxAttempts = 1
                    },
                payoutLegacy =
                    clients.named("payhero-payout") {
                        baseUrl = LEGACY_URL
                        maxAttempts = 1
                    },
            )
    }
}

/** What came of one call: an answer to read, or one of the two ways there is none. */
internal sealed interface Answer {
    data class Replied(val status: Int, val body: String) : Answer {
        override fun toString(): String = "Replied($status, ***)"
    }

    /** The request provably never left (no connection, no route, the circuit is open). */
    data object NotSent : Answer

    /** It left, or may have, and nothing usable came back: a timeout, a connection that broke, a body cut short. */
    data object Unclear : Answer
}

/** Makes one call and says which of the three it was. The exception's class is logged, never its message. */
internal suspend fun Logger.ask(operation: String, send: suspend () -> HttpResponse): Answer =
    try {
        val response = send()
        Answer.Replied(response.status.value, response.bodyAsText())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val neverLeft = neverLeft(e)
        warn(
            "payhero call failed: operation={} sent={} error={}",
            operation,
            if (neverLeft) "no" else "maybe",
            e::class.simpleName,
        )
        if (neverLeft) Answer.NotSent else Answer.Unclear
    }

/**
 * True only for the failures that happen before a byte of the request is written. A timeout while waiting for the
 * answer is not one of them: by then the provider may hold the request.
 */
internal fun neverLeft(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }
        .take(CAUSE_DEPTH)
        .any {
            it is CircuitOpenException || it is ConnectTimeoutException || it is ConnectException ||
                it is UnknownHostException || it is UnresolvedAddressException || it is NoRouteToHostException
        }

internal fun HttpRequestBuilder.authorize(credentials: CollectionCredentials) {
    val basic = Base64.getEncoder().encodeToString("${credentials.username}:${credentials.password}".toByteArray())
    header(HttpHeaders.Authorization, "Basic $basic")
}

private const val CAUSE_DEPTH = 8
