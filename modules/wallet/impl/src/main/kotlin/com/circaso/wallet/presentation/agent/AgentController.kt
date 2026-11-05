package com.circaso.wallet.presentation.agent

import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.wallet.api.AgentAssetValuesResponse
import com.circaso.wallet.api.AgentFeeQuoteResponse
import com.circaso.wallet.api.AgentSummaryResponse
import com.circaso.wallet.api.AgentTransactionResponse
import com.circaso.wallet.api.AgentTransactionsResponse
import com.circaso.wallet.api.AgentWalletResponse
import com.circaso.wallet.api.AgentWalletsResponse
import com.circaso.wallet.presentation.mappers.AgentFeeParams
import com.circaso.wallet.presentation.mappers.AgentHistoryParams
import com.circaso.wallet.presentation.mappers.assetValueQuery
import com.circaso.wallet.presentation.mappers.currencyCode
import com.circaso.wallet.presentation.mappers.toAgentResponse
import com.circaso.wallet.presentation.mappers.toCommand
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
                val query = call.request.queryParameters
                val result =
                    walletQuery(call.userId(), query["currency"], query["walletId"])
                        .flatMap { reads.getWallet(it) }
                call.respondDefault(result.toDefaultResponse { it.toAgentResponse() })
            }.docs<NoBody, AgentWalletResponse>(
                "The user's balances per chain and asset, and their worth in a currency",
                AGENT,
                listOf("wallet.custody_unavailable", "wallet.not_found", "request.malformed"),
                notes =
                    "$SCOPE Query: `currency` (ISO 4217, optional), `walletId` (optional, added 2026-11-05: a " +
                        "group wallet's id from `/agent/wallet/wallets`; members only).",
            )

            get("wallets") {
                val result = reads.listWallets(call.userId())
                call.respondDefault(result.toDefaultResponse { it.toAgentResponse() })
            }.docs<NoBody, AgentWalletsResponse>(
                "Every wallet the user can see: their own, then their groups'",
                AGENT,
                notes = "$SCOPE Added 2026-11-05. W16's list without space wallets (Spaces is archived).",
            )

            get("values") {
                val query = call.request.queryParameters
                val result =
                    assetValueQuery(query["assets"], query["amount"], query["currency"])
                        .flatMap { reads.getAssetValues(it) }
                call.respondDefault(result.toDefaultResponse { it.toAgentResponse() })
            }.docs<NoBody, AgentAssetValuesResponse>(
                "What the supported assets are worth, at the rate the wallet itself uses",
                AGENT,
                listOf("request.malformed"),
                notes =
                    "$SCOPE Added 2026-11-05. Query: `assets` (comma-separated, default every supported " +
                        "asset), `amount` (whole units, optional), `currency` (ISO 4217, optional; USD without). " +
                        "The same rate W1 values balances with and W6 and W17 value payments and fees with, so " +
                        "Asha's numbers match the app. An asset the wallet doesn't hold is `priced: false`.",
            )

            get("fee") {
                val params = call.request.queryParameters.toFeeParams()
                val result =
                    params.toCommand(call.userId()).flatMap { command ->
                        params.currencyCode().flatMap { reads.getFeeEstimate(command, it) }
                    }
                call.respondDefault(result.toDefaultResponse { it.toAgentResponse() })
            }.docs<NoBody, AgentFeeQuoteResponse>(
                "The fee, total and limits for a payment the user is thinking of, without holding a quote",
                AGENT,
                listOf(
                    "wallet.not_found",
                    "wallet.not_ready",
                    "wallet.not_on_chain",
                    "wallet.chain_unsupported",
                    "wallet.amount_invalid",
                    "wallet.self_transfer",
                    "wallet.recipient_no_wallet",
                    "wallet.recipient_not_on_chain",
                    "wallet.address_invalid",
                    "wallet.group_not_found",
                    "wallet.custody_unavailable",
                    "request.malformed",
                ),
                notes =
                    "$SCOPE Added 2026-11-05. Query: `amount` (whole units), `asset` (default USDC), " +
                        "`blockchain`, exactly one of `toUserId`, `toAddress`, `toGroupId`, and optional " +
                        "`walletId` (a group wallet) and `currency`. W17's numbers; no quote is held and nothing " +
                        "is written. The network estimate is cached a minute per wallet and payee.",
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

private fun Parameters.toFeeParams() =
    AgentFeeParams(
        asset = this["asset"],
        amount = this["amount"],
        blockchain = this["blockchain"],
        toUserId = this["toUserId"],
        toAddress = this["toAddress"],
        toGroupId = this["toGroupId"],
        walletId = this["walletId"],
        currency = this["currency"],
    )

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
