package com.circaso.ramp.presentation.controllers

import com.circaso.ramp.presentation.dto.DestinationRequest
import com.circaso.ramp.presentation.dto.DestinationResponse
import com.circaso.ramp.presentation.dto.DestinationsResponse
import com.circaso.ramp.presentation.dto.PinRequest
import com.circaso.ramp.presentation.dto.RemovedResponse
import com.circaso.ramp.presentation.dto.WithdrawRequest
import com.circaso.ramp.presentation.dto.WithdrawalResponse
import com.circaso.ramp.presentation.dto.WithdrawalsResponse
import com.circaso.ramp.presentation.mappers.idOf
import com.circaso.ramp.presentation.mappers.pinOf
import com.circaso.ramp.presentation.mappers.toAssertion
import com.circaso.ramp.presentation.mappers.toCommand
import com.circaso.ramp.presentation.mappers.toResponse
import com.circaso.ramp.presentation.services.WithdrawalService
import com.circaso.ramp.presentation.support.merchantId
import com.circaso.ramp.presentation.support.merchantNetwork
import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

private const val TAG = "Merchant portal"
private const val MONEY = "Needs `X-Merchant-Session`. Network: `X-Wallet-Network` (the testnet without it)."
private const val PIN_ERRORS = "ramp.pin_invalid, ramp.pin_locked, ramp.vault_unavailable"
private const val STEP_UP =
    "Once you have a passkey, also `passkey: { challengeId, credential }` from `POST /security/step-up`"
private const val DEFAULT_LIMIT = 20
private const val MAX_LIMIT = 100

/**
 * Float withdrawals (2026-11-24): RM20, the addresses a merchant may withdraw to (each usable only after a cooling-off
 * delay), and RM21, withdrawals of free float to one of them. Inside the merchant session group.
 */
fun Route.withdrawalController(withdrawals: WithdrawalService) {
    route("/withdrawal-addresses") {
        get {
            val result = withdrawals.destinations(call.merchantId(), call.merchantNetwork())
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<NoBody, DestinationsResponse>(
            "RM20 · Your withdrawal addresses",
            TAG,
            listOf("ramp.session_invalid"),
            notes =
                "$MONEY The addresses you saved on this network, newest first (removed ones are not listed), " +
                    "`delayHours` (how long a new one waits before it can be used), `blockchain` (your float " +
                    "wallet's chain; null before it exists) and `max` (how many you may keep).",
        )

        post {
            val request = call.receive<DestinationRequest>()
            val result =
                request.toCommand().flatMap { withdrawals.add(call.merchantId(), call.merchantNetwork(), it) }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<DestinationRequest, DestinationResponse>(
            "RM20 · Save a withdrawal address",
            TAG,
            listOf(
                "ramp.address_invalid",
                "ramp.destination_own_float",
                "ramp.destination_exists",
                "ramp.destinations_full",
                "ramp.merchant_not_approved",
                "ramp.merchant_setup_incomplete",
                "ramp.pin_invalid",
                "ramp.pin_locked",
                "ramp.vault_unavailable",
                "ramp.withdrawals_locked",
                "ramp.step_up_required",
                "ramp.step_up_invalid",
                "ramp.passkey_required",
                "request.malformed",
            ),
            notes =
                "$MONEY $STEP_UP (`ADD_ADDRESS`; 2026-11-24, as is the email each change sends). " +
                    "Addresses can't be edited, not even their label: remove one and add it again. " +
                    "`{ address, label, pin }`: an EVM address on your float wallet's chain (mixed case must " +
                    "match its EIP-55 checksum; never the zero address or your float wallet's own), a label of 1 to " +
                    "40 characters, and your PIN. It can be withdrawn to from `usableAt`: the network's cooling-off " +
                    "delay after now (24 hours unless Circaso changed it), so an address you didn't add can't be " +
                    "used before you see it. Needs an approved account with a float wallet and a PIN (RM5).",
        )

        post("{destinationId}/remove") {
            val request = call.receive<PinRequest>()
            val result =
                idOf(call.parameters["destinationId"], "destinationId").flatMap { id ->
                    pinOf(request.pin).flatMap { pin ->
                        val proof = request.passkey?.toAssertion()
                        withdrawals.remove(call.merchantId(), call.merchantNetwork(), id, pin, proof)
                    }
                }
            call.respondDefault(result.toDefaultResponse { RemovedResponse(true) })
        }.docs<PinRequest, RemovedResponse>(
            "RM20 · Remove a withdrawal address",
            TAG,
            listOf(
                "ramp.destination_not_found",
                "ramp.pin_invalid",
                "ramp.pin_locked",
                "ramp.step_up_required",
                "ramp.step_up_invalid",
                "ramp.passkey_required",
                "request.malformed",
            ),
            notes =
                "$MONEY `{ pin }`; $STEP_UP (`REMOVE_ADDRESS`). A withdrawal to it not yet being sent is " +
                    "cancelled (`CANCELLED`, `address_removed`) and its amount freed. " +
                    "Withdrawals already made to it keep their record. Adding it again restarts " +
                    "the delay.",
        )
    }

    route("/withdrawals") {
        post {
            val request = call.receive<WithdrawRequest>()
            val key =
                call.request
                    .header("Idempotency-Key")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val result =
                request.toCommand(key).flatMap {
                    withdrawals.withdraw(call.merchantId(), call.merchantNetwork(), it)
                }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<WithdrawRequest, WithdrawalResponse>(
            "RM21 · Withdraw float",
            TAG,
            listOf(
                "ramp.amount_invalid",
                "ramp.insufficient_float",
                "ramp.limit_exceeded",
                "ramp.destination_not_found",
                "ramp.destination_not_ready",
                "ramp.custody_unavailable",
                "ramp.merchant_not_approved",
                "ramp.merchant_setup_incomplete",
                "ramp.pin_invalid",
                "ramp.pin_locked",
                "ramp.vault_unavailable",
                "ramp.withdrawals_locked",
                "ramp.step_up_required",
                "ramp.step_up_invalid",
                "ramp.passkey_required",
                "request.malformed",
            ),
            notes =
                "$MONEY $STEP_UP (`WITHDRAW`, bound to this address and amount). " +
                    "`{ amountMinor, destinationId, pin }` with an `Idempotency-Key` (the same key is " +
                    "the same withdrawal). Only your **free** float: never what open orders or withdrawals " +
                    "hold, nor Circaso's share held until the daily sweep. At least the network's minimum " +
                    "(1 USDC), within the daily cap when one is set. Online or offline. The amount is reserved " +
                    "at once (RM2's `free` drops and `withdrawing` rises); the ops server sends it within " +
                    "seconds (`SENT`) and it is `COMPLETED` when the chain confirms it, or `FAILED` (the amount " +
                    "is free again). Read it with `GET /withdrawals/{withdrawalId}`. PIN errors: $PIN_ERRORS.",
        )

        get {
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
            val cursor = call.request.queryParameters["cursor"]?.takeIf { it.isNotBlank() }
            val before: Resource<UUID?> =
                when (val id = cursor?.let { idOf(it, "cursor") }) {
                    null -> Resource.success(null)
                    is Resource.Error -> id
                    is Resource.Success -> Resource.success(id.data)
                }
            val result =
                before.flatMap { withdrawals.withdrawals(call.merchantId(), call.merchantNetwork(), limit, it) }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<NoBody, WithdrawalsResponse>(
            "RM21 · Your withdrawals",
            TAG,
            listOf("ramp.session_invalid", "request.malformed"),
            notes =
                "$MONEY Newest first, `?limit=` (20, at most 100) and `?cursor=` (the last answer's `next`). " +
                    "`limits`: the minimum, the daily cap (null: none) and what you withdrew today (Nairobi day).",
        )

        get("{withdrawalId}") {
            val result =
                idOf(call.parameters["withdrawalId"], "withdrawalId").flatMap {
                    withdrawals.withdrawal(call.merchantId(), it)
                }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<NoBody, WithdrawalResponse>(
            "RM21 · One withdrawal",
            TAG,
            listOf("ramp.withdrawal_not_found", "request.malformed"),
            notes =
                "$MONEY Poll it while `REQUESTED` or `SENT`; `explorerUrl` links the transaction once it has " +
                    "a hash.",
        )
    }
}
