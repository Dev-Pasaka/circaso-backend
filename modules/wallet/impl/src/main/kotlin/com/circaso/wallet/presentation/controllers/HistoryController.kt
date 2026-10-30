package com.circaso.wallet.presentation.controllers

import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.wallet.presentation.dto.TransactionsResponse
import com.circaso.wallet.presentation.mappers.historyQuery
import com.circaso.wallet.presentation.mappers.toResponse
import com.circaso.wallet.presentation.services.HistoryService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.principal
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** The payment history (W6). */
fun Route.historyController(history: HistoryService) {
    route("/wallet") {
        authenticate(Scope.APP) {
            get("transactions") {
                val parameters = call.request.queryParameters
                val result =
                    historyQuery(
                        call.principal().userId,
                        parameters["month"],
                        parameters["cursor"],
                        parameters["limit"],
                    ).flatMap { history.transactions(it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, TransactionsResponse>(
                "W6 · Your payments, newest first",
                "Wallet",
                listOf("request.malformed", "request.rate_limited"),
                notes =
                    "Scope: `app`. Query: `month` (optional, `YYYY-MM`, a calendar month in UTC), `cursor`, " +
                        "`limit` (default 50, at most 200). Without `month` the list runs back through every " +
                        "month (the wallet tab's recent list); with it, only that month (`nextCursor` is null at the " +
                        "month's first payment). Pass `nextCursor` back as `cursor` with the same `month`. Only " +
                        "payments that were signed: `SUBMITTED`, `CONFIRMED`, `FAILED`. `counterparty` names the " +
                        "other person (`userId`, `displayName`, `handle`, `avatarUrl`) when they are on Circaso, and " +
                        "carries the on-chain `address`; `transferId` (open it with W5) only on payments you sent. " +
                        "60 requests per user per minute.",
            )
        }
    }
}
