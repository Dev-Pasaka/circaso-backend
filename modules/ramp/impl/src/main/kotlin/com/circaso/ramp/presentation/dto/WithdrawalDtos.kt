package com.circaso.ramp.presentation.dto

import com.circaso.ramp.api.RampAmount
import kotlinx.serialization.Serializable

/**
 * RM20 request: an EVM address on the float's chain, a label of 1 to 40 characters, the 6-digit PIN, and once you have
 * a passkey its confirmation (`passkey`, from RM-P6 with `ADD_ADDRESS`).
 */
@Serializable
data class DestinationRequest(
    val address: String = "",
    val label: String = "",
    val pin: String = "",
    val passkey: PasskeyProofDto? = null,
) {
    override fun toString(): String = "DestinationRequest($address, $label, ***)"
}

/** RM20's remove and anything else that only needs the PIN. */
@Serializable
data class PinRequest(val pin: String = "", val passkey: PasskeyProofDto? = null) {
    override fun toString(): String = "PinRequest(***)"
}

/** One saved withdrawal address. `usable` is whether it can be withdrawn to now (`usableAt` has passed). */
@Serializable
data class DestinationResponse(
    val destinationId: String,
    val label: String,
    val address: String,
    val blockchain: String,
    val network: String,
    val createdAt: String,
    val usableAt: String,
    val usable: Boolean,
    /** Added while still cooling off when withdrawals were locked: never usable; remove it and add it again. */
    val frozen: Boolean = false,
)

@Serializable
data class DestinationsResponse(
    val addresses: List<DestinationResponse>,
    val delayHours: Int,
    val blockchain: String?,
    val max: Int,
)

@Serializable
data class RemovedResponse(val removed: Boolean)

/** RM21 request: USDC minor units as digits, a saved usable address, and the PIN. */
@Serializable
data class WithdrawRequest(
    val amountMinor: String = "",
    val destinationId: String = "",
    val pin: String = "",
    val passkey: PasskeyProofDto? = null,
) {
    override fun toString(): String = "WithdrawRequest($amountMinor, $destinationId, ***)"
}

@Serializable
data class WithdrawalDestinationDto(val destinationId: String, val label: String, val address: String)

/**
 * One withdrawal (RM21). `state`: `REQUESTED` (reserved, waiting for the ops server to send it, seconds), `SENT` (on its
 * way: `txHash` once the chain has it), `COMPLETED` or `FAILED` (`failureReason`: `refused`, `failed_on_chain`,
 * `float_short`, `wallet_missing`; the amount is back in your free float), or `CANCELLED` before it was sent
 * (`failureReason`: `locked` after "This wasn't me", `address_removed` when its address was removed; the amount is back).
 */
@Serializable
data class WithdrawalResponse(
    val withdrawalId: String,
    val state: String,
    val amount: RampAmount,
    val network: String,
    val blockchain: String,
    val destination: WithdrawalDestinationDto,
    val txHash: String?,
    val explorerUrl: String?,
    val failureReason: String?,
    val createdAt: String,
    val updatedAt: String,
    val sentAt: String?,
    val closedAt: String?,
)

@Serializable
data class WithdrawalLimitsDto(val min: RampAmount, val dailyMax: RampAmount?, val usedToday: RampAmount)

@Serializable
data class WithdrawalsResponse(
    val withdrawals: List<WithdrawalResponse>,
    val limits: WithdrawalLimitsDto,
    val next: String?,
)
