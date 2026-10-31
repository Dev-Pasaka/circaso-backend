package com.circaso.wallet.presentation.agent

import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.wallet.api.AgentSummaryResponse
import com.circaso.wallet.api.AgentTransactionResponse
import com.circaso.wallet.api.AgentTransactionsResponse
import com.circaso.wallet.api.AgentWalletResponse
import com.circaso.wallet.presentation.mappers.AgentHistoryParams
import com.circaso.wallet.presentation.mappers.toAgentResponse
import com.circaso.wallet.presentation.mappers.toHistoryQuery
import com.circaso.wallet.presentation.mappers.toSummaryQuery
import com.circaso.wallet.presentation.mappers.transactionQuery
import com.circaso.wallet.presentation.mappers.walletQuery
import com.circaso.wallet.presentation.services.WalletAgentService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.http.Parameters
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private const val AGENT = "Agent routes"
private const val SCOPE = "Scope: `agent` (read-only), the caller's own wallet only."
private const val FILTERS =
    "Query: `from`, `to` (dates `YYYY-MM-DD`, UTC, both inclusive), `month` (`YYYY-MM`), `direction` (`IN`, " +
        "`OUT`), `counterparty` (a user id, or an address), `asset` (`USDC`, `USDT`), `blockchain`, `currency` " +
        "(ISO 4217: adds `fiat` at today's rate)."

/**
 * Read-only routes for the assistant (`/v1/agent/wallet/…`, agent token only, decision 2026-10-31), in their own
 * smaller shape. They call service methods that read and nothing else; the gateway also refuses anything but `GET`.
 */
fun Route.agentController(reads: WalletAgentService) {
    route("/agent/wallet") {
        authenticate(Scope.AGENT) {
            get {
                val result =
                    walletQuery(call.userId(), call.request.queryParameters["currency"])
                        .flatMap { reads.getWallet(it) }
                call.respondDefault(result.toDefaultResponse { it.toAgentResponse() })
            }.docs<NoBody, AgentWalletResponse>(
                "The user's balances per chain and asset, and their worth in a currency",
                AGENT,
                listOf("wallet.custody_unavailable", "request.malformed"),
                notes = "$SCOPE Query: `currency` (ISO 4217, optional).",
            )

            route("/transactions") {
                get {
                    val result =
                        call.request.queryParameters
                            .toParams()
                            .toHistoryQuery(call.userId())
                            .flatMap { reads.listTransactions(it) }
                    call.respondDefault(result.toDefaultResponse { it.toAgentResponse() })
                }.docs<NoBody, AgentTransactionsResponse>(
                    "The user's payments, newest first, narrowed by date, direction, person, asset or chain",
                    AGENT,
                    listOf("request.malformed"),
                    notes = "$SCOPE $FILTERS Also `cursor`, `limit` (default 20, at most 50).",
                )

                get("summary") {
                    val result =
                        call.request.queryParameters
                            .toParams()
                            .toSummaryQuery(call.userId())
                            .flatMap { reads.summariseTransactions(it) }
                    call.respondDefault(result.toDefaultResponse { it.toAgentResponse() })
                }.docs<NoBody, AgentSummaryResponse>(
                    "Totals in and out per asset, the biggest counterparties and month by month",
                    AGENT,
                    listOf("request.malformed"),
                    notes =
                        "$SCOPE $FILTERS Also `top` (counterparties to name, default 5, at most 20). Counts " +
                            "`SUBMITTED` and `CONFIRMED` payments; `truncated` when more than 5,000 matched.",
                )

                get("{transactionId}") {
                    val result =
                        transactionQuery(
                            call.userId(),
                            call.parameters["transactionId"],
                            call.request.queryParameters["currency"],
                        ).flatMap { reads.getTransaction(it) }
                    call.respondDefault(result.toDefaultResponse { it.toAgentResponse() })
                }.docs<NoBody, AgentTransactionResponse>(
                    "One of the user's payments by its id from the list",
                    AGENT,
                    listOf("wallet.transfer_not_found", "request.malformed"),
                    notes = "$SCOPE Query: `currency` (optional).",
                )
            }
        }
    }
}

private fun Parameters.toParams() =
    AgentHistoryParams(
        from = this["from"],
        to = this["to"],
        month = this["month"],
        direction = this["direction"],
        counterparty = this["counterparty"],
        asset = this["asset"],
        blockchain = this["blockchain"],
        currency = this["currency"],
        cursor = this["cursor"],
        limit = this["limit"],
        top = this["top"],
    )
