package com.circaso.wallet.domain.support

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.TreasuryBalance
import com.circaso.integrations.api.TreasuryCustody
import com.circaso.integrations.api.TreasuryTransaction
import com.circaso.integrations.api.TreasuryTransactionState
import com.circaso.integrations.api.TreasuryTransfer
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import com.circaso.wallet.domain.errors.FeeWalletError
import com.circaso.wallet.domain.models.FeeDestination
import com.circaso.wallet.domain.models.FeeStaff
import com.circaso.wallet.domain.models.FeeWallet
import com.circaso.wallet.domain.models.FeeWithdrawal
import com.circaso.wallet.domain.models.WithdrawalChange
import com.circaso.wallet.domain.models.WithdrawalState
import com.circaso.wallet.domain.repositories.FeeDestinationRepository
import com.circaso.wallet.domain.repositories.FeeWalletRepository
import com.circaso.wallet.domain.repositories.FeeWithdrawalRepository
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** What staff ask for: [amountMinor] of [asset] from [blockchain]'s fee wallet to a destination, and why. */
data class WithdrawalRequest(
    val network: Network,
    val blockchain: String,
    val asset: String,
    val amountMinor: BigInteger,
    val destinationId: UUID,
    val reason: String,
    val idempotencyKey: String,
)

/**
 * Withdrawals of collected fees (decision 2026-11-18): from a fee wallet to a destination added at least the delay
 * ago, signed by Circle with the entity secret, so only on the ops server.
 *
 * `PENDING → SENT → CONFIRMED | FAILED`. The row is written `PENDING` before Circle is asked, with the idempotency key
 * every attempt will carry ([FeeWithdrawal.circleKey]). Then:
 * - Circle accepts: `SENT`, with Circle's transaction id.
 * - Circle refuses: `FAILED`, and staff get `fees.withdrawal_refused`. Nothing was sent.
 * - Circle can't be reached, or its answer can't be read: it **stays `PENDING`**, because Circle may have it. [follow]
 *   asks again with the same key, which sends nothing twice, until Circle says which it was.
 *
 * A withdrawal is therefore never shown as sent unless Circle gave it a transaction id (the table refuses a `SENT` row
 * without one). [follow] then reads the transaction until the chain confirms it or it fails. Amounts are minor units
 * in `BigInteger` throughout; the one decimal string is made exactly, at Circle's door.
 */
class FeeWithdrawals(
    private val treasury: TreasuryCustody?,
    private val withdrawals: FeeWithdrawalRepository,
    private val destinations: FeeDestinationRepository,
    private val wallets: FeeWalletRepository,
    private val balances: FeeWalletBalances,
    private val feeDestinations: FeeDestinations,
    private val clock: Clock,
) {
    suspend fun page(
        network: Network?,
        before: UUID?,
        limit: Int,
    ): List<FeeWithdrawal> = withdrawals.page(network, before, limit)

    suspend fun request(request: WithdrawalRequest, by: FeeStaff): Resource<FeeWithdrawal> {
        // The same request again (a retry after a lost answer) is the same withdrawal, never a second one.
        withdrawals.byIdempotencyKey(by.staffId, request.idempotencyKey)?.let { return Resource.success(it) }
        val custody = treasury ?: return unavailable("this server's custody provider isn't Circle.")
        val chain = request.blockchain.trim().uppercase()
        if (chain.isEmpty() || Network.ofChain(chain) != request.network) {
            return invalid("blockchain", "isn't a chain on ${request.network}")
        }
        if (request.amountMinor.signum() <= 0) return invalid("amountMinor", "must be above zero")
        val wallet =
            wallets.wallets(request.network).firstOrNull { it.blockchain == chain }
                ?: return unavailable("there is no fee wallet on $chain.")
        balances.unavailable(wallet)?.let { return unavailable(it) }
        val walletId = wallet.circleWalletId ?: return unavailable("there is no fee wallet on $chain.")

        val destination =
            when (val found = usable(request.destinationId, chain)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val held =
            when (val read = custody.balances(request.network, walletId)) {
                is Resource.Success -> read.data
                is Resource.Error -> return Resource.error(FeeWalletError.CustodyUnavailable, read.cause)
            }
        val coin =
            when (val found = coinOf(request, chain, held)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val open = withdrawals.openOn(chain)
        val promised = open.filter { it.tokenId == coin.tokenId }.sumOf { it.amountMinor }
        if (request.amountMinor > coin.amountMinor - promised) return Resource.error(FeeWalletError.InsufficientBalance)

        val transfer =
            TreasuryTransfer(
                walletId = walletId,
                blockchain = chain,
                tokenId = coin.tokenId,
                decimals = coin.decimals,
                amountMinor = request.amountMinor,
                destinationAddress = destination.address,
                refId = "",
                idempotencyKey = UUID.randomUUID(),
            )
        checkGas(custody, request.network, wallet, transfer, coin, held, open)?.let { return it }

        val now = clock.instant()
        val id = Uuid7.generate(now)
        val row =
            FeeWithdrawal(
                id = id,
                blockchain = chain,
                asset = coin.symbol,
                tokenAddress = coin.tokenAddress,
                decimals = coin.decimals,
                amountMinor = request.amountMinor,
                destinationId = destination.id,
                destinationAddress = destination.address,
                destinationLabel = destination.label,
                circleWalletId = walletId,
                tokenId = coin.tokenId,
                circleKey = transfer.idempotencyKey,
                state = WithdrawalState.PENDING,
                circleTxId = null,
                txHash = null,
                failureReason = null,
                reason = request.reason,
                requestedBy = by.staffId,
                requestedByEmail = by.email,
                idempotencyKey = request.idempotencyKey,
                createdAt = now,
                updatedAt = now,
            )
        if (!withdrawals.insert(row)) {
            // The same key arrived twice at once: the other request's row is the withdrawal.
            return withdrawals.byIdempotencyKey(by.staffId, request.idempotencyKey)?.let { Resource.success(it) }
                ?: Resource.error(FeeWalletError.CustodyUnavailable)
        }
        return submit(custody, row)
    }

    /**
     * The ops server's job: sends again any withdrawal still `PENDING` after [RESEND_AFTER] (Circle answers the same
     * key with the first transaction), and reads every `SENT` one from Circle. How many it moved.
     */
    suspend fun follow(limit: Int = FOLLOW_BATCH): Int {
        val custody = treasury ?: return 0
        var moved = 0
        for (row in withdrawals.open(limit)) {
            if (!custody.canRead(row.network)) continue
            val before = row.state
            val after =
                when (row.state) {
                    WithdrawalState.PENDING -> {
                        if (!custody.canSign(row.network)) continue
                        if (row.updatedAt.plus(RESEND_AFTER).isAfter(clock.instant())) continue
                        when (val sent = submit(custody, row)) {
                            is Resource.Success -> sent.data.state
                            is Resource.Error -> WithdrawalState.FAILED
                        }
                    }

                    WithdrawalState.SENT -> {
                        read(custody, row)
                    }

                    else -> {
                        null
                    }
                }
            if (after != null && after != before) moved++
        }
        return moved
    }

    /** Asks Circle to send [row]; the withdrawal as it stands afterwards, or the refusal. */
    private suspend fun submit(custody: TreasuryCustody, row: FeeWithdrawal): Resource<FeeWithdrawal> {
        val transfer =
            TreasuryTransfer(
                walletId = row.circleWalletId,
                blockchain = row.blockchain,
                tokenId = row.tokenId,
                decimals = row.decimals,
                amountMinor = row.amountMinor,
                destinationAddress = row.destinationAddress,
                refId = "$REF_PREFIX${row.id}",
                idempotencyKey = row.circleKey,
            )
        return when (val sent = custody.send(row.network, transfer)) {
            is Resource.Success -> {
                accepted(row, sent.data)
                Resource.success(withdrawals.find(row.id) ?: row)
            }

            is Resource.Error -> {
                // Unknown outcome: Circle may have it. It stays PENDING and the job asks again with the same key.
                if (sent.error is IntegrationError.Unavailable) return Resource.success(row)
                val why = sent.error.message.take(REASON_LENGTH)
                move(row, WithdrawalState.PENDING, WithdrawalState.FAILED, WithdrawalChange(failureReason = why))
                Resource.error(FeeWalletError.WithdrawalRefused(why))
            }
        }
    }

    private suspend fun accepted(row: FeeWithdrawal, transaction: TreasuryTransaction) {
        val change = WithdrawalChange(circleTxId = transaction.transactionId, txHash = transaction.txHash.ifEmpty { null })
        if (!move(row, WithdrawalState.PENDING, WithdrawalState.SENT, change)) return
        settle(row.id, transaction)
    }

    private suspend fun read(custody: TreasuryCustody, row: FeeWithdrawal): WithdrawalState? {
        val transactionId = row.circleTxId ?: return null
        val transaction = (custody.transaction(row.network, transactionId) as? Resource.Success)?.data ?: return null
        return settle(row.id, transaction, knownHash = row.txHash)
    }

    /** A `SENT` withdrawal's next state from Circle's word on its transaction. */
    private suspend fun settle(
        id: UUID,
        transaction: TreasuryTransaction,
        knownHash: String? = transaction.txHash.ifEmpty { null },
    ): WithdrawalState {
        val hash = transaction.txHash.ifEmpty { null }
        val sent = WithdrawalState.SENT
        return when (transaction.state) {
            TreasuryTransactionState.CONFIRMED -> {
                if (move(id, sent, WithdrawalState.CONFIRMED, WithdrawalChange(txHash = hash))) {
                    WithdrawalState.CONFIRMED
                } else {
                    sent
                }
            }

            TreasuryTransactionState.FAILED -> {
                val why = transaction.failureReason.ifEmpty { "FAILED" }.take(REASON_LENGTH)
                if (move(id, sent, WithdrawalState.FAILED, WithdrawalChange(txHash = hash, failureReason = why))) {
                    WithdrawalState.FAILED
                } else {
                    sent
                }
            }

            TreasuryTransactionState.IN_FLIGHT -> {
                if (hash != null && hash != knownHash) withdrawals.noteHash(id, hash, clock.instant())
                sent
            }
        }
    }

    private suspend fun move(
        row: FeeWithdrawal,
        from: WithdrawalState,
        to: WithdrawalState,
        change: WithdrawalChange,
    ): Boolean = move(row.id, from, to, change)

    private suspend fun move(
        id: UUID,
        from: WithdrawalState,
        to: WithdrawalState,
        change: WithdrawalChange,
    ): Boolean = from.mayBecome(to) && withdrawals.move(id, from, to, change, clock.instant())

    /** The destination, if it is on [chain], still in use, past its delay, and not a fee wallet. */
    private suspend fun usable(id: UUID, chain: String): Resource<FeeDestination> {
        val destination =
            destinations.find(id)?.takeIf { it.removedAt == null && it.blockchain == chain }
                ?: return Resource.error(FeeWalletError.DestinationNotFound)
        val usableAt = destination.usableAt(feeDestinations.delay())
        if (clock.instant().isBefore(usableAt)) return Resource.error(FeeWalletError.DestinationNotReady(usableAt))
        if (feeDestinations.isOwn(destination.address)) return Resource.error(FeeWalletError.DestinationIsFeeWallet)
        return Resource.success(destination)
    }

    /**
     * The coin [WithdrawalRequest.asset] names in the wallet: the chain's own coin by its symbol, or a coin offered on
     * the chain, matched by its contract (anyone can send the wallet a token that calls itself USDC). A coin offered
     * but not held is a balance of zero.
     */
    private suspend fun coinOf(
        request: WithdrawalRequest,
        chain: String,
        held: List<TreasuryBalance>,
    ): Resource<TreasuryBalance> {
        val symbol = request.asset.trim().uppercase()
        if (symbol == FeeChains.nativeSymbol(chain)) {
            return held.firstOrNull { it.native }?.let { Resource.success(it) }
                ?: Resource.error(FeeWalletError.InsufficientBalance)
        }
        val offered =
            balances.offered(request.network, chain).firstOrNull { it.symbol == symbol && it.tokenAddress.isNotBlank() }
                ?: return Resource.error(FeeWalletError.AssetUnsupported)
        return held
            .firstOrNull { !it.native && FeeWalletBalances.sameToken(it.tokenAddress, offered.tokenAddress) }
            ?.let { Resource.success(it.copy(symbol = offered.symbol)) }
            ?: Resource.error(FeeWalletError.InsufficientBalance)
    }

    /**
     * The fee wallet is an externally owned account: it pays the network fee itself, in the chain's own coin. Circle
     * estimates it; when Circle won't, [FeeChains.gasFloor] stands in. Null when the wallet can pay.
     */
    private suspend fun checkGas(
        custody: TreasuryCustody,
        network: Network,
        wallet: FeeWallet,
        transfer: TreasuryTransfer,
        coin: TreasuryBalance,
        held: List<TreasuryBalance>,
        open: List<FeeWithdrawal>,
    ): Resource<FeeWithdrawal>? {
        val native = held.firstOrNull { it.native }
        val decimals = native?.decimals ?: FeeChains.NATIVE_DECIMALS
        val fee =
            when (val estimate = custody.estimateTransfer(network, transfer)) {
                is Resource.Success -> estimate.data
                is Resource.Error -> {
                    if (estimate.error is IntegrationError.Unavailable) {
                        return Resource.error(FeeWalletError.CustodyUnavailable, estimate.cause)
                    }
                    FeeChains.gasFloor(wallet.blockchain)
                }
            }
        val feeMinor = fee.movePointRight(decimals).setScale(0, RoundingMode.CEILING).toBigIntegerExact()
        // Native coin already promised to withdrawals on their way, and this one's own amount when it is the coin.
        val nativeOut =
            open.filter { it.tokenAddress.isEmpty() }.sumOf { it.amountMinor } +
                if (coin.native) transfer.amountMinor else BigInteger.ZERO
        val left = (native?.amountMinor ?: BigInteger.ZERO) - nativeOut
        if (left < feeMinor) {
            return Resource.error(FeeWalletError.InsufficientGas(FeeChains.nativeSymbol(wallet.blockchain), wallet.blockchain))
        }
        return null
    }

    private fun <T> invalid(field: String, problem: String): Resource<T> =
        Resource.error(FeeWalletError.Invalid(field, problem))

    private fun <T> unavailable(why: String): Resource<T> = Resource.error(FeeWalletError.WithdrawalsUnavailable(why))

    companion object {
        const val REF_PREFIX = "circaso-fee-withdrawal:"
        const val FOLLOW_BATCH = 100
        const val REASON_LENGTH = 300

        /** Longer than any call to Circle takes, so the job never races the request that is still sending. */
        val RESEND_AFTER: Duration = Duration.ofMinutes(2)
    }
}
