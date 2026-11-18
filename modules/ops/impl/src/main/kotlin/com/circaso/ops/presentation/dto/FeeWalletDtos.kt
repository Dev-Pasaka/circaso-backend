package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

/** A coin a fee wallet holds. `amountMinor` is an integer in the coin's smallest unit, as text (it can pass 2^53). */
@Serializable
data class FeeWalletBalanceDto(val symbol: String, val amountMinor: String, val decimals: Int)

/**
 * One chain's fee wallet. `nativeBalance` is the chain's own coin, which pays a withdrawal's network fee. When
 * `balancesKnown` is false Circle couldn't be asked: `balances` is empty and `nativeBalance` null, not zero.
 * `withdrawable` false comes with `unavailableReason`.
 */
@Serializable
data class FeeWalletDto(
    val blockchain: String,
    val address: String,
    val balances: List<FeeWalletBalanceDto>,
    val nativeBalance: FeeWalletBalanceDto? = null,
    val balancesKnown: Boolean = true,
    val withdrawable: Boolean = true,
    val unavailableReason: String? = null,
)

/** `GET /ops/fee-wallets?network=`. `status`: READY | NOT_CREATED | FAILED. */
@Serializable
data class FeeWalletsResponse(
    val network: String,
    val status: String,
    val reason: String? = null,
    val wallets: List<FeeWalletDto>,
    /** How long a new destination waits before it can be withdrawn to; read-only here (a database setting). */
    val destinationDelayHours: Int,
)

@Serializable
data class FeeDestinationDto(
    val id: String,
    val network: String,
    val blockchain: String,
    val address: String,
    val label: String,
    /** The email of the staff member who added it. */
    val createdBy: String,
    val createdById: String,
    val createdAt: String,
    /** From when it may be withdrawn to: `createdAt` plus the delay. */
    val usableAt: String,
    /** Whether `usableAt` has passed on the server's clock. */
    val usable: Boolean,
)

@Serializable
data class FeeDestinationsResponse(val destinations: List<FeeDestinationDto>)

/** `POST /ops/fee-wallets/destinations`. */
@Serializable
data class AddFeeDestinationRequest(
    val network: String,
    val blockchain: String,
    val address: String,
    val label: String,
    val reason: String = "",
)

@Serializable
data class FeeWithdrawalDestinationDto(val id: String, val address: String, val label: String)

/** `state`: PENDING | SENT | CONFIRMED | FAILED. `reason` is why staff withdrew; `failureReason` why it failed. */
@Serializable
data class FeeWithdrawalDto(
    val id: String,
    val network: String,
    val blockchain: String,
    val asset: String,
    val amountMinor: String,
    val decimals: Int,
    val destination: FeeWithdrawalDestinationDto,
    val state: String,
    val txHash: String? = null,
    val reason: String,
    val failureReason: String? = null,
    /** The email of the staff member who asked for it. */
    val by: String,
    val byId: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class FeeWithdrawalsResponse(val items: List<FeeWithdrawalDto>, val nextCursor: String? = null)

/**
 * `POST /ops/fee-wallets/withdrawals`. `amountMinor` is the integer amount in the coin's smallest unit as text
 * (`"2500000"` is 2.5 USDC), like every amount of the wallet's (decision 2026-10-28); never a decimal.
 */
@Serializable
data class CreateFeeWithdrawalRequest(
    val network: String,
    val blockchain: String,
    val asset: String,
    val amountMinor: String,
    val destinationId: String,
    val reason: String = "",
)
