package com.circaso.wallet.presentation.controllers

import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.wallet.presentation.dto.RecipientResponse
import com.circaso.wallet.presentation.dto.RemindResponse
import com.circaso.wallet.presentation.mappers.recipientIdOf
import com.circaso.wallet.presentation.mappers.recipientQuery
import com.circaso.wallet.presentation.mappers.toResponse
import com.circaso.wallet.presentation.services.RecipientService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.principal
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Wallet"
private const val SCOPE = "Scope: `app`."
private const val MALFORMED = "request.malformed"

/** Paying a person: whether they can be paid (W9), and reminding one without a wallet (W10). */
fun Route.recipientController(recipients: RecipientService) {
    route("/wallet/recipients") {
        authenticate(Scope.APP) {
            get("{userId}") {
                val query =
                    recipientQuery(
                        call.principal().userId,
                        call.parameters["userId"],
                        call.request.queryParameters["blockchain"],
                        call.request.queryParameters["chatId"],
                    )
                call.respondDefault(query.flatMap { recipients.check(it) }.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, RecipientResponse>(
                "W9 · Whether a person can be paid, and on which chains",
                TAG,
                listOf(
                    "users.not_reachable",
                    "wallet.self_transfer",
                    "wallet.chain_unsupported",
                    "wallet.support_account_has_no_wallet",
                    MALFORMED,
                ),
                notes =
                    "$SCOPE Query `blockchain` (optional): the chain you mean to pay on; `chatId` (optional): " +
                        "the chat you pay from, where sharing it stands in for reach. `status` is `CAN_RECEIVE`, " +
                        "`NO_WALLET` (offer W10) or `NO_WALLET_ON_CHAIN` (they can't receive on that chain, or " +
                        "on any chain when none was named); `blockchains` lists where they can be paid now.",
            )

            post("{userId}/remind") {
                val from = call.principal().userId
                val result = recipientIdOf(call.parameters["userId"]).flatMap { recipients.remind(from, it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, RemindResponse>(
                "W10 · Remind a contact to set up a wallet",
                TAG,
                listOf(
                    "users.not_reachable",
                    "wallet.self_transfer",
                    "wallet.support_account_has_no_wallet",
                    MALFORMED,
                ),
                notes =
                    "$SCOPE Sends them a `wallet/setup.requested` frame and a push (\"Amina wants to send you " +
                        "money\"), once per pair per 24 hours: `SENT`, `ALREADY_SENT`, or `HAS_WALLET` when they " +
                        "can already be paid.",
            )
        }
    }
}
