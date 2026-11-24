package com.circaso.ramp.domain.usecases

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.DestinationBook
import com.circaso.ramp.domain.models.DestinationCommand
import com.circaso.ramp.domain.models.SavedDestination
import com.circaso.ramp.domain.models.StaffWithdrawals
import com.circaso.ramp.domain.models.WithdrawCommand
import com.circaso.ramp.domain.models.Withdrawal
import com.circaso.ramp.domain.models.WithdrawalPage
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.WithdrawalDestinationRepository
import com.circaso.ramp.domain.repositories.WithdrawalRepository
import com.circaso.ramp.domain.support.FloatWithdrawals
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.util.UUID

/** RM20: the merchant's saved withdrawal addresses on a network, and the delay a new one waits. */
class ListWithdrawalDestinations(private val withdrawals: FloatWithdrawals) {
    suspend operator fun invoke(merchantId: MerchantId, network: Network): Resource<DestinationBook> =
        Resource.success(withdrawals.book(merchantId, network))
}

/** RM20: saves an address, with the PIN; usable after the network's cooling-off delay. */
class AddWithdrawalDestination(private val withdrawals: FloatWithdrawals) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        command: DestinationCommand,
    ): Resource<SavedDestination> = withdrawals.addDestination(merchantId, network, command)
}

/** RM20: removes a saved address, with the PIN. */
class RemoveWithdrawalDestination(private val withdrawals: FloatWithdrawals) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        destinationId: UUID,
        pin: String,
    ): Resource<Unit> = withdrawals.removeDestination(merchantId, network, destinationId, pin)
}

/** RM21: withdraws free float to a saved, usable address, with the PIN; the ops process sends it. */
class WithdrawFloat(private val withdrawals: FloatWithdrawals) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        command: WithdrawCommand,
    ): Resource<Withdrawal> = withdrawals.withdraw(merchantId, network, command)
}

/** RM21: the merchant's withdrawals on a network, newest first, and the limits. */
class ListWithdrawals(private val withdrawals: FloatWithdrawals) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        limit: Int,
        before: UUID?,
    ): Resource<WithdrawalPage> = Resource.success(withdrawals.page(merchantId, network, limit, before))
}

/** RM21: one of the merchant's withdrawals. */
class GetWithdrawal(private val withdrawals: FloatWithdrawals) {
    suspend operator fun invoke(merchantId: MerchantId, withdrawalId: UUID): Resource<Withdrawal> =
        withdrawals.one(merchantId, withdrawalId)
}

/** Staff: one merchant's withdrawals and every address they saved on a network, newest first. */
class ListStaffWithdrawals(
    private val merchants: MerchantRepository,
    private val withdrawals: WithdrawalRepository,
    private val destinations: WithdrawalDestinationRepository,
) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        limit: Int,
    ): Resource<StaffWithdrawals> {
        merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        val bounded = limit.coerceIn(1, MAX_LIMIT)
        return Resource.success(
            StaffWithdrawals(
                withdrawals = withdrawals.page(merchantId, network, bounded, null),
                destinations = destinations.all(merchantId, network, MAX_LIMIT),
            ),
        )
    }

    private companion object {
        const val MAX_LIMIT = 200
    }
}
