package com.circaso.ops.domain.usecases

import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.ops.domain.support.Reasons
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.wallet.api.FeeWalletAdmin
import com.circaso.wallet.api.OpsFeeDestination
import com.circaso.wallet.api.OpsFeeStaff
import com.circaso.wallet.api.OpsFeeWallets
import com.circaso.wallet.api.OpsFeeWithdrawal
import com.circaso.wallet.api.OpsFeeWithdrawalPage
import com.circaso.wallet.api.OpsNewFeeDestination
import com.circaso.wallet.api.OpsNewFeeWithdrawal
import java.util.UUID

// Circaso's fee wallets for staff (decision 2026-11-18). The wallets, destinations and withdrawals are the wallet
// module's (`FeeWalletAdmin`); what is the ops module's is who may, the reason, and the audit line of every write: who,
// why, the network, the chain, the amount and the destination.

/** A network's fee wallets with what they hold now. */
class GetFeeWallets(private val wallets: FeeWalletAdmin) {
    suspend operator fun invoke(network: Network): Resource<OpsFeeWallets> = Resource.success(wallets.wallets(network))
}

/** The destinations collected fees may be withdrawn to; [network] null for both. */
class ListFeeDestinations(private val wallets: FeeWalletAdmin) {
    suspend operator fun invoke(network: Network?): Resource<List<OpsFeeDestination>> =
        Resource.success(wallets.destinations(network))
}

/** Adds a destination. It can be withdrawn to only after the delay; a reason is required and audited. */
class AddFeeDestination(private val wallets: FeeWalletAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        destination: OpsNewFeeDestination,
        reason: String,
    ): Resource<OpsFeeDestination> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        val where = "${destination.network} ${destination.blockchain.trim().uppercase()} ${destination.address.trim()}"
        return trail.record<OpsFeeDestination>(
            actor,
            AuditAction.FEE_DESTINATION_ADD,
            targetId = null,
            reason = why,
            detail = "add $where",
            outcome = { "add ${it.network} ${it.blockchain} ${it.address} (${it.id}), usable ${it.usableAt}" },
        ) { wallets.addDestination(destination, actor.toFeeStaff()) }
    }
}

/** Removes a destination; audited. Withdrawals already made to it keep naming it. */
class RemoveFeeDestination(private val wallets: FeeWalletAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        id: UUID,
        reason: String?,
    ): Resource<OpsFeeDestination> =
        trail.record<OpsFeeDestination>(
            actor,
            AuditAction.FEE_DESTINATION_REMOVE,
            targetId = id.toString(),
            reason = reason?.let(Reasons::check),
            detail = "remove",
            outcome = { "remove ${it.network} ${it.blockchain} ${it.address}" },
        ) { wallets.removeDestination(id, actor.toFeeStaff()) }
}

class ListFeeWithdrawals(private val wallets: FeeWalletAdmin) {
    suspend operator fun invoke(
        network: Network?,
        cursor: String?,
        limit: Int,
    ): Resource<OpsFeeWithdrawalPage> = wallets.withdrawals(network, cursor, limit)
}

/**
 * Withdraws collected fees to a destination. A reason is required. The audit line is written before Circle is asked
 * and says what was asked (network, chain, coin, amount in minor units, destination); when it succeeds it names the
 * withdrawal and its state, and when the wallet refuses, the line fails with the wallet's code.
 */
class WithdrawFees(private val wallets: FeeWalletAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        withdrawal: OpsNewFeeWithdrawal,
        reason: String,
    ): Resource<OpsFeeWithdrawal> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        val asked =
            "${withdrawal.network} ${withdrawal.blockchain.trim().uppercase()} ${withdrawal.amountMinor} " +
                "${withdrawal.asset.trim().uppercase()} (minor units) to destination ${withdrawal.destinationId}"
        return trail.record<OpsFeeWithdrawal>(
            actor,
            AuditAction.FEE_WITHDRAWAL_CREATE,
            targetId = withdrawal.destinationId.toString(),
            reason = why,
            detail = asked,
            outcome = {
                "${it.network} ${it.blockchain} ${it.amountMinor} ${it.asset} (minor units, ${it.decimals} decimals) " +
                    "to ${it.destinationAddress} (${it.destinationLabel}); withdrawal ${it.id} ${it.state}"
            },
        ) { wallets.withdraw(withdrawal.copy(reason = why), actor.toFeeStaff()) }
    }
}

private fun Actor.toFeeStaff() = OpsFeeStaff(staffId, email)
