package com.circaso.integrations.impl.payhero

import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode

/**
 * PayHero/FinoQ's hosts for a test: each `host/path` answers what the test set for it ([answer], [fail]); anything
 * else is a bare `404`, as a host that has no such route would answer. [asked] is every `host/path` called, in order.
 */
internal class FakePayHero {
    private val answers = mutableMapOf<String, () -> Pair<Int, String>>()

    val clients =
        FakeHttpClients { request ->
            val (status, body) = (answers[request.where()] ?: { 404 to "" })()
            if (body.isEmpty()) respond("", HttpStatusCode.fromValue(status)) else json(body, status)
        }

    val hosts = PayHeroHosts.of(clients)

    val asked: List<String> get() = clients.requests.map { it.where() }

    fun answer(
        where: String,
        body: String,
        status: Int = 200,
    ) {
        answers[where] = { status to body }
    }

    fun fail(where: String, failure: () -> Throwable) {
        answers[where] = { throw failure() }
    }

    fun last(where: String): HttpRequestData = clients.requests.last { it.where() == where }

    /** The wallet query is one path for every wallet, so its `wallet_type` is part of where it went. */
    private fun HttpRequestData.where(): String =
        "${url.host}${url.encodedPath}" + url.parameters["wallet_type"]?.let { "?wallet_type=$it" }.orEmpty()

    companion object {
        const val API = "api.finoq.africa"
        const val LEGACY = "backend.payhero.co.ke"
        const val AUTH = "auth.payhero.africa"
        const val PRICING = "api.payhero.africa"
        const val WITHDRAW = "/api/v2/withdraw"
        const val API_STATUS = "/api/global/transaction-status"
        const val LEGACY_STATUS = "/api/v2/transaction-status"
        const val WALLETS = "/api/v2/wallets"
    }
}
