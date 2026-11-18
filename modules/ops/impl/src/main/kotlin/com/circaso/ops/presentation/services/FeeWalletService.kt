package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.usecases.AddFeeDestination
import com.circaso.ops.domain.usecases.GetFeeWallets
import com.circaso.ops.domain.usecases.ListFeeDestinations
import com.circaso.ops.domain.usecases.ListFeeWithdrawals
import com.circaso.ops.domain.usecases.RemoveFeeDestination
import com.circaso.ops.domain.usecases.WithdrawFees
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.wallet.api.OpsFeeDestination
import com.circaso.wallet.api.OpsFeeWallets
import com.circaso.wallet.api.OpsFeeWithdrawal
import com.circaso.wallet.api.OpsFeeWithdrawalPage
import com.circaso.wallet.api.OpsNewFeeDestination
import com.circaso.wallet.api.OpsNewFeeWithdrawal
import java.util.UUID

/** Circaso's fee wallets for staff (decision 2026-11-18): FINANCE and ADMIN read, ADMIN adds, removes and withdraws. */
class FeeWalletService(
    private val getFeeWallets: GetFeeWallets,
    private val listFeeDestinations: ListFeeDestinations,
    private val addFeeDestination: AddFeeDestination,
    private val removeFeeDestination: RemoveFeeDestination,
    private val listFeeWithdrawals: ListFeeWithdrawals,
    private val withdrawFees: WithdrawFees,
) {
    suspend fun wallets(network: Network): Resource<OpsFeeWallets> = getFeeWallets(network)

    suspend fun destinations(network: Network?): Resource<List<OpsFeeDestination>> = listFeeDestinations(network)

    suspend fun addDestination(
        actor: Actor,
        destination: OpsNewFeeDestination,
        reason: String,
    ): Resource<OpsFeeDestination> = addFeeDestination(actor, destination, reason)

    suspend fun removeDestination(
        actor: Actor,
        id: UUID,
        reason: String?,
    ): Resource<OpsFeeDestination> = removeFeeDestination(actor, id, reason)

    suspend fun withdrawals(
        network: Network?,
        cursor: String?,
        limit: Int,
    ): Resource<OpsFeeWithdrawalPage> = listFeeWithdrawals(network, cursor, limit)

    suspend fun withdraw(
        actor: Actor,
        withdrawal: OpsNewFeeWithdrawal,
        reason: String,
    ): Resource<OpsFeeWithdrawal> = withdrawFees(actor, withdrawal, reason)
}
