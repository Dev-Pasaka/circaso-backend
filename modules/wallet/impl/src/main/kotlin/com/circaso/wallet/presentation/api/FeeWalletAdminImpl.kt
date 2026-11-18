package com.circaso.wallet.presentation.api

import com.circaso.shared.FieldError
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.map
import com.circaso.wallet.api.FeeWalletAdmin
import com.circaso.wallet.api.OpsFeeBalance
import com.circaso.wallet.api.OpsFeeDestination
import com.circaso.wallet.api.OpsFeeStaff
import com.circaso.wallet.api.OpsFeeWallet
import com.circaso.wallet.api.OpsFeeWallets
import com.circaso.wallet.api.OpsFeeWithdrawal
import com.circaso.wallet.api.OpsFeeWithdrawalPage
import com.circaso.wallet.api.OpsNewFeeDestination
import com.circaso.wallet.api.OpsNewFeeWithdrawal
import com.circaso.wallet.domain.models.FeeDestination
import com.circaso.wallet.domain.models.FeeStaff
import com.circaso.wallet.domain.models.FeeWithdrawal
import com.circaso.wallet.domain.models.WithdrawalOrder
import com.circaso.wallet.domain.support.FeeBalance
import com.circaso.wallet.presentation.services.FeeWalletService
import java.time.Duration
import java.util.UUID

/**
 * wallet:api's staff contract for the fee wallets (decision 2026-11-18), bound for the ops module only: the app
 * gateway never mounts it. Every call goes through [FeeWalletService].
 */
class FeeWalletAdminImpl(private val service: FeeWalletService) : FeeWalletAdmin {
    override suspend fun wallets(network: Network): OpsFeeWallets {
        val view = service.wallets(network)
        return OpsFeeWallets(
            network = view.network,
            status = view.status.name,
            reason = view.reason,
            wallets =
                view.wallets.map {
                    OpsFeeWallet(
                        blockchain = it.wallet.blockchain,
                        address = it.wallet.address,
                        balances = it.balances.map { balance -> balance.toOps() },
                        nativeBalance = it.nativeBalance?.toOps(),
                        balancesKnown = it.balancesKnown,
                        withdrawable = it.withdrawable,
                        unavailableReason = it.unavailable,
                    )
                },
            destinationDelayHours = view.destinationDelayHours,
        )
    }

    override suspend fun destinations(network: Network?): List<OpsFeeDestination> {
        val delay = service.destinationDelay()
        return service.destinations(network).map { it.toOps(delay) }
    }

    override suspend fun destination(id: UUID): OpsFeeDestination? =
        service.destination(id)?.toOps(service.destinationDelay())

    override suspend fun addDestination(
        destination: OpsNewFeeDestination,
        by: OpsFeeStaff,
    ): Resource<OpsFeeDestination> {
        val delay = service.destinationDelay()
        return service
            .addDestination(
                destination.network,
                destination.blockchain,
                destination.address,
                destination.label,
                by.toDomain(),
            ).map { it.toOps(delay) }
    }

    override suspend fun removeDestination(id: UUID, by: OpsFeeStaff): Resource<OpsFeeDestination> {
        val delay = service.destinationDelay()
        return service.removeDestination(id, by.toDomain()).map { it.toOps(delay) }
    }

    override suspend fun withdrawals(
        network: Network?,
        cursor: String?,
        limit: Int,
    ): Resource<OpsFeeWithdrawalPage> {
        val before =
            cursor?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
                    ?: return Resource.error(SharedError.Malformed(listOf(FieldError("cursor", "isn't a cursor"))))
            }
        val size = limit.coerceIn(1, MAX_PAGE)
        val rows = service.withdrawals(network, before, size + 1)
        val page = rows.take(size)
        return Resource.success(
            OpsFeeWithdrawalPage(
                items = page.map { it.toOps() },
                nextCursor = if (rows.size > size) page.last().id.toString() else null,
            ),
        )
    }

    override suspend fun withdraw(withdrawal: OpsNewFeeWithdrawal, by: OpsFeeStaff): Resource<OpsFeeWithdrawal> =
        service
            .withdraw(
                WithdrawalOrder(
                    network = withdrawal.network,
                    blockchain = withdrawal.blockchain,
                    asset = withdrawal.asset,
                    amountMinor = withdrawal.amountMinor,
                    destinationId = withdrawal.destinationId,
                    reason = withdrawal.reason,
                    idempotencyKey = withdrawal.idempotencyKey,
                ),
                by.toDomain(),
            ).map { it.toOps() }

    private fun OpsFeeStaff.toDomain() = FeeStaff(staffId, email)

    private fun FeeBalance.toOps() = OpsFeeBalance(symbol, amountMinor, decimals)

    private fun FeeDestination.toOps(delay: Duration) =
        OpsFeeDestination(
            id = id,
            network = network,
            blockchain = blockchain,
            address = address,
            label = label,
            createdBy = createdByEmail,
            createdById = createdBy,
            createdAt = createdAt,
            usableAt = usableAt(delay),
        )

    private fun FeeWithdrawal.toOps() =
        OpsFeeWithdrawal(
            id = id,
            network = network,
            blockchain = blockchain,
            asset = asset,
            amountMinor = amountMinor,
            decimals = decimals,
            destinationId = destinationId,
            destinationAddress = destinationAddress,
            destinationLabel = destinationLabel,
            state = state.name,
            txHash = txHash,
            reason = reason,
            failureReason = failureReason,
            by = requestedByEmail,
            byId = requestedBy,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private companion object {
        const val MAX_PAGE = 100
    }
}
