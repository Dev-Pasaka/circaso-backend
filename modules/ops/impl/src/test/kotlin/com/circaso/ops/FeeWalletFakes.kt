package com.circaso.ops

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
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
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private data class FeeWalletRefusal(override val code: String, override val kind: ErrorKind) : DomainError {
    override val message: String = code
}

/**
 * The wallet's fee-wallet contract in memory: it takes what it is given and says what it was given, so the ops
 * routes' own work (who may, the shapes, the reason, the audit line) is what a test sees. [refusal] makes the next
 * write fail with that code, as the wallet would.
 */
class FakeFeeWallets : FeeWalletAdmin {
    val destinations = CopyOnWriteArrayList<OpsFeeDestination>()
    val withdrawals = CopyOnWriteArrayList<OpsFeeWithdrawal>()
    val asked = CopyOnWriteArrayList<OpsNewFeeWithdrawal>()
    val walletReads = CopyOnWriteArrayList<Network>()

    @Volatile
    var refusal: String? = null

    private fun refused(): Resource.Error? = refusal?.let { Resource.Error(FeeWalletRefusal(it, ErrorKind.CONFLICT)) }

    override suspend fun wallets(network: Network): OpsFeeWallets {
        walletReads += network
        return OpsFeeWallets(
            network = network,
            status = "READY",
            reason = null,
            wallets =
                listOf(
                    OpsFeeWallet(
                        blockchain = if (network == Network.MAINNET) "BASE" else "BASE-SEPOLIA",
                        address = FEE_ADDRESS,
                        balances =
                            listOf(
                                OpsFeeBalance("USDC", BigInteger("340282366920938463463374607431768211455"), 6),
                            ),
                        nativeBalance = OpsFeeBalance("ETH", BigInteger("1000000000000000"), 18),
                        balancesKnown = true,
                        withdrawable = true,
                        unavailableReason = null,
                    ),
                    OpsFeeWallet("MATIC", FEE_ADDRESS, emptyList(), null, false, false, "no Circle wallet behind it"),
                ),
            destinationDelayHours = 24,
        )
    }

    override suspend fun destinations(network: Network?): List<OpsFeeDestination> =
        destinations.filter { network == null || it.network == network }

    override suspend fun destination(id: UUID): OpsFeeDestination? = destinations.firstOrNull { it.id == id }

    override suspend fun addDestination(
        destination: OpsNewFeeDestination,
        by: OpsFeeStaff,
    ): Resource<OpsFeeDestination> {
        refused()?.let { return it }
        val added =
            OpsFeeDestination(
                id = Uuid7.generate(EPOCH),
                network = destination.network,
                blockchain = destination.blockchain,
                address = destination.address,
                label = destination.label,
                createdBy = by.email,
                createdById = by.staffId,
                createdAt = EPOCH,
                usableAt = EPOCH.plus(Duration.ofHours(24)),
            )
        destinations += added
        return Resource.success(added)
    }

    override suspend fun removeDestination(id: UUID, by: OpsFeeStaff): Resource<OpsFeeDestination> {
        val found =
            destinations.firstOrNull { it.id == id }
                ?: return Resource.Error(FeeWalletRefusal("fees.destination_not_found", ErrorKind.NOT_FOUND))
        destinations -= found
        return Resource.success(found)
    }

    override suspend fun withdrawals(
        network: Network?,
        cursor: String?,
        limit: Int,
    ): Resource<OpsFeeWithdrawalPage> =
        Resource.success(OpsFeeWithdrawalPage(withdrawals.filter { network == null || it.network == network }, null))

    override suspend fun withdraw(withdrawal: OpsNewFeeWithdrawal, by: OpsFeeStaff): Resource<OpsFeeWithdrawal> {
        asked += withdrawal
        refused()?.let { return it }
        val made =
            OpsFeeWithdrawal(
                id = Uuid7.generate(EPOCH),
                network = withdrawal.network,
                blockchain = withdrawal.blockchain,
                asset = withdrawal.asset,
                amountMinor = withdrawal.amountMinor,
                decimals = 6,
                destinationId = withdrawal.destinationId,
                destinationAddress = TREASURY,
                destinationLabel = "Treasury",
                state = "SENT",
                txHash = null,
                reason = withdrawal.reason,
                failureReason = null,
                by = by.email,
                byId = by.staffId,
                createdAt = EPOCH,
                updatedAt = EPOCH,
            )
        withdrawals += made
        return Resource.success(made)
    }

    companion object {
        const val FEE_ADDRESS = "0xfee0000000000000000000000000000000000fee"
        const val TREASURY = "0x00000000000000000000000000000000000000aa"
        val EPOCH: Instant = Instant.parse("2026-11-19T09:00:00Z")
    }
}
