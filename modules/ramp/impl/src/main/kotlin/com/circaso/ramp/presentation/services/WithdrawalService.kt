package com.circaso.ramp.presentation.services

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.DestinationBook
import com.circaso.ramp.domain.models.DestinationCommand
import com.circaso.ramp.domain.models.SavedDestination
import com.circaso.ramp.domain.models.WithdrawCommand
import com.circaso.ramp.domain.models.Withdrawal
import com.circaso.ramp.domain.models.WithdrawalPage
import com.circaso.ramp.domain.usecases.AddWithdrawalDestination
import com.circaso.ramp.domain.usecases.GetWithdrawal
import com.circaso.ramp.domain.usecases.ListWithdrawalDestinations
import com.circaso.ramp.domain.usecases.ListWithdrawals
import com.circaso.ramp.domain.usecases.RemoveWithdrawalDestination
import com.circaso.ramp.domain.usecases.WithdrawFloat
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.util.UUID

/** The merchant portal's float withdrawals (RM20, RM21; 2026-11-24). */
class WithdrawalService(
    private val listDestinations: ListWithdrawalDestinations,
    private val addDestination: AddWithdrawalDestination,
    private val removeDestination: RemoveWithdrawalDestination,
    private val withdrawFloat: WithdrawFloat,
    private val listWithdrawals: ListWithdrawals,
    private val getWithdrawal: GetWithdrawal,
) {
    suspend fun destinations(merchantId: MerchantId, network: Network): Resource<DestinationBook> =
        listDestinations(merchantId, network)

    suspend fun add(
        merchantId: MerchantId,
        network: Network,
        command: DestinationCommand,
    ): Resource<SavedDestination> = addDestination(merchantId, network, command)

    suspend fun remove(
        merchantId: MerchantId,
        network: Network,
        destinationId: UUID,
        pin: String,
    ): Resource<Unit> = removeDestination(merchantId, network, destinationId, pin)

    suspend fun withdraw(
        merchantId: MerchantId,
        network: Network,
        command: WithdrawCommand,
    ): Resource<Withdrawal> = withdrawFloat(merchantId, network, command)

    suspend fun withdrawals(
        merchantId: MerchantId,
        network: Network,
        limit: Int,
        before: UUID?,
    ): Resource<WithdrawalPage> = listWithdrawals(merchantId, network, limit, before)

    suspend fun withdrawal(merchantId: MerchantId, withdrawalId: UUID): Resource<Withdrawal> =
        getWithdrawal(merchantId, withdrawalId)
}
