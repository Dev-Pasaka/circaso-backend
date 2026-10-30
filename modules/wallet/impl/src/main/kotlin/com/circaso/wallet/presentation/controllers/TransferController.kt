package com.circaso.wallet.presentation.controllers

import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.wallet.presentation.dto.PrepareTransferRequest
import com.circaso.wallet.presentation.dto.PrepareTransferResponse
import com.circaso.wallet.presentation.dto.TransferResponse
import com.circaso.wallet.presentation.dto.ValidateAddressRequest
import com.circaso.wallet.presentation.dto.ValidateAddressResponse
import com.circaso.wallet.presentation.mappers.toCommand
import com.circaso.wallet.presentation.mappers.toQuery
import com.circaso.wallet.presentation.mappers.toResponse
import com.circaso.wallet.presentation.mappers.transferIdOf
import com.circaso.wallet.presentation.services.TransferService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.principal
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Wallet"
private const val SCOPE = "Scope: `app`."
private const val CUSTODY = "wallet.custody_unavailable"
private const val MALFORMED = "request.malformed"

/** Sending money (W4, W5) and checking an outside address first (W11). */
fun Route.transferController(transfers: TransferService) {
    route("/wallet") {
        authenticate(Scope.APP) {
            route("/transfers") {
                post {
                    val userId = call.principal().userId
                    val key = call.idempotencyKey()
                    val result =
                        call.receive<PrepareTransferRequest>().toCommand(userId, key).flatMap { transfers.prepare(it) }
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<PrepareTransferRequest, PrepareTransferResponse>(
                    "W4 · Prepare a payment: the challenge you sign with your PIN",
                    TAG,
                    listOf(
                        "wallet.account_restricted",
                        "wallet.chain_unsupported",
                        "wallet.not_found",
                        "wallet.not_ready",
                        "wallet.not_on_chain",
                        "wallet.on_hold",
                        "wallet.amount_invalid",
                        "wallet.limit_exceeded",
                        "wallet.self_transfer",
                        "wallet.support_account_has_no_wallet",
                        "users.not_reachable",
                        "wallet.recipient_no_wallet",
                        "wallet.recipient_not_on_chain",
                        "wallet.address_invalid",
                        "wallet.insufficient_funds",
                        "wallet.pin_locked",
                        CUSTODY,
                        MALFORMED,
                    ),
                    notes =
                        "$SCOPE `blockchain` is the chain to send on: the payment leaves from your wallet's " +
                            "address there. `to` is `{ userId }` (they need a wallet on that chain, and you must " +
                            "reach them, or share `chatId` with them) or `{ address }` (valid on that chain). " +
                            "`amount` is `{ asset: \"USDC\", amountMinor: \"20000000\" }` or `{ asset, amount: " +
                            "\"20\" }`. The checks run in this order: account, wallet on the chain, hold, amount and " +
                            "limits, payee, balance. Run `challengeId` in the Circle SDK with `sdk` before " +
                            "`expiresAt` (15 minutes); updates arrive as `wallet/transfer.updated` frames. The same " +
                            "`Idempotency-Key` always answers the same transfer.",
                )

                get("{transferId}") {
                    val userId = call.principal().userId
                    val result = transferIdOf(call.parameters["transferId"]).flatMap { transfers.transfer(userId, it) }
                    call.respondDefault(result.toDefaultResponse { it.toResponse(userId) })
                }.docs<NoBody, TransferResponse>(
                    "W5 · One payment and its state",
                    TAG,
                    listOf("wallet.transfer_not_found", MALFORMED),
                    notes =
                        "$SCOPE For the sender (`direction: OUT`) and the person paid (`IN`). States: " +
                            "`AWAITING_SIGNATURE`, `SUBMITTED`, `CONFIRMED`, `FAILED`, `EXPIRED`.",
                )
            }

            post("addresses/validate") {
                val result =
                    call.receive<ValidateAddressRequest>().toQuery().flatMap { (blockchain, address) ->
                        transfers.validate(blockchain, address)
                    }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<ValidateAddressRequest, ValidateAddressResponse>(
                "W11 · Check an outside address on a chain",
                TAG,
                listOf("wallet.chain_unsupported", CUSTODY, MALFORMED),
                notes =
                    "$SCOPE `{ blockchain, address }`. `valid: false` is an answer, not an error. A payment to an " +
                        "address needs it to be valid on the payment's own chain. Payments on chain can't be " +
                        "reversed: show a warning before W4.",
            )
        }
    }
}
