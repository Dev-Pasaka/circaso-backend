package com.circaso.wallet.presentation.controllers

import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.wallet.presentation.dto.PrepareTransferRequest
import com.circaso.wallet.presentation.dto.PrepareTransferResponse
import com.circaso.wallet.presentation.dto.QuoteTransferResponse
import com.circaso.wallet.presentation.dto.TransferResponse
import com.circaso.wallet.presentation.dto.ValidateAddressRequest
import com.circaso.wallet.presentation.dto.ValidateAddressResponse
import com.circaso.wallet.presentation.mappers.toCommand
import com.circaso.wallet.presentation.mappers.toQuery
import com.circaso.wallet.presentation.mappers.toQuoteCommand
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
                        "wallet.asset_not_held",
                        "wallet.asset_unpriced",
                        "wallet.asset_not_supported",
                        "wallet.limit_exceeded",
                        "wallet.self_transfer",
                        "wallet.support_account_has_no_wallet",
                        "users.not_reachable",
                        "wallet.recipient_no_wallet",
                        "wallet.recipient_not_on_chain",
                        "wallet.address_invalid",
                        "wallet.insufficient_funds",
                        "wallet.pin_locked",
                        "wallet.not_signer",
                        "wallet.signer_left",
                        "wallet.payout_required",
                        "wallet.payout_not_approved",
                        "wallet.payout_mismatch",
                        "wallet.payout_in_progress",
                        "wallet.group_not_found",
                        "wallet.fee_changed",
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
                            "`Idempotency-Key` always answers the same transfer. Group wallets (added 2026-11-03): " +
                            "`to: { groupId }` pays a group's wallet (members only; a contribution when it has a " +
                            "target); `walletId` pays from a group wallet you sign for, and then `payoutRequestId` " +
                            "must name a payout the admins approved, to exactly its payee and for exactly its " +
                            "amount. Fees and limits (phase 2F): pass W17's `quoteId`; its fee is charged on top " +
                            "and paid in the same signature, and `total` is what leaves the wallet. A fee above " +
                            "the one shown answers `wallet.fee_changed` with a new `quoteId` in `details.fields`: " +
                            "show it, then send again with that id and a new key. `wallet.limit_exceeded` and " +
                            "`wallet.insufficient_funds` carry their numbers in `details.fields` too. Any held coin " +
                            "(added 2026-11-07): `asset` is any coin your wallet holds on that chain (`EURC`, " +
                            "`USDT`, `ETH`, `POL`, `SOL`, …) with `amountMinor` in its own decimals; the fee is the " +
                            "dollar fee converted into that coin at the price feed's price, rounded up, and the " +
                            "limits count the payment in dollars at that price. A coin you don't hold there is " +
                            "`wallet.asset_not_held`; one with no price right now `wallet.asset_unpriced`.",
                )

                post("quote") {
                    val userId = call.principal().userId
                    val result =
                        call.receive<PrepareTransferRequest>().toQuoteCommand(userId).flatMap { transfers.quote(it) }
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<PrepareTransferRequest, QuoteTransferResponse>(
                    "W17 · The fee for a payment before you sign it",
                    TAG,
                    listOf(
                        "wallet.account_restricted",
                        "wallet.chain_unsupported",
                        "wallet.not_found",
                        "wallet.not_ready",
                        "wallet.not_on_chain",
                        "wallet.on_hold",
                        "wallet.amount_invalid",
                        "wallet.asset_not_held",
                        "wallet.asset_unpriced",
                        "wallet.self_transfer",
                        "wallet.support_account_has_no_wallet",
                        "users.not_reachable",
                        "wallet.recipient_no_wallet",
                        "wallet.recipient_not_on_chain",
                        "wallet.address_invalid",
                        "wallet.group_not_found",
                        CUSTODY,
                        MALFORMED,
                    ),
                    notes =
                        "$SCOPE W4's body (`quoteId` ignored). The one Circaso fee for exactly this payment, " +
                            "network cost included (`fee`, with `rateBps` and `minimum`), the `total`, the " +
                            "`balance`, the most that can be sent with its fee (`maxAmount`) and the wallet's " +
                            "`limit`. A short balance or a limit is reported in `problem` (`INSUFFICIENT_FUNDS`, " +
                            "`PER_PAYMENT_LIMIT`, `DAILY_LIMIT`, with `fitsAt`), not refused. Nothing moves. For the " +
                            "person who signs for the wallet the fee is held until `expiresAt` (15 minutes): pass " +
                            "`quoteId` to W4. A group's members may quote its wallet (no `quoteId`). Any held coin " +
                            "(added 2026-11-07): the fee is in the coin, converted from dollars at `usdPrice` and " +
                            "rounded up; the quote holds that price with the fee. `limit` is in the coin for USDC " +
                            "and USDT and in `USD` for any other coin; `totalUsd` is what the payment counts " +
                            "against the limits.",
                )

                get("{transferId}") {
                    val userId = call.principal().userId
                    val result = transferIdOf(call.parameters["transferId"]).flatMap { transfers.transfer(userId, it) }
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<NoBody, TransferResponse>(
                    "W5 · One payment and its state",
                    TAG,
                    listOf("wallet.transfer_not_found", MALFORMED),
                    notes =
                        "$SCOPE For the sender (`direction: OUT`) and the person paid (`IN`), and for every member " +
                            "of a group whose wallet is on either side, from the wallet's side. States: " +
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
