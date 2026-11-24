package com.circaso.ramp.domain.support

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.MerchantFloat
import com.circaso.integrations.api.TreasuryCustody
import com.circaso.integrations.api.TreasuryTransactionState
import com.circaso.integrations.api.TreasuryTransfer
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.DestinationBook
import com.circaso.ramp.domain.models.DestinationCommand
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.MerchantWallet
import com.circaso.ramp.domain.models.SavedDestination
import com.circaso.ramp.domain.models.WithdrawCommand
import com.circaso.ramp.domain.models.Withdrawal
import com.circaso.ramp.domain.models.WithdrawalDestination
import com.circaso.ramp.domain.models.WithdrawalPage
import com.circaso.ramp.domain.models.WithdrawalState
import com.circaso.ramp.domain.ports.AddressCheck
import com.circaso.ramp.domain.ports.EvmAddresses
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.ports.Transactor
import com.circaso.ramp.domain.repositories.CommissionRepository
import com.circaso.ramp.domain.repositories.CredentialRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.ReservationRepository
import com.circaso.ramp.domain.repositories.WithdrawalDestinationRepository
import com.circaso.ramp.domain.repositories.WithdrawalRepository
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import java.math.BigInteger
import java.time.Clock
import java.util.UUID

/**
 * A merchant taking USDC out of their float (2026-11-24), in the **app's process**: saving the addresses it may go to,
 * and asking for a withdrawal. Nothing here sends: the ops process does ([WithdrawalSends]).
 *
 * **Addresses first, with a cooling-off delay.** A withdrawal goes only to an address the merchant saved with their PIN
 * at least `withdraw_destination_delay_hours` (24) earlier. That delay is the defence against a stolen portal session:
 * a thief who also knows the PIN still has to wait a day, while the merchant can see (and remove) the address.
 *
 * **Only the free float.** A withdrawal is checked and written under the merchant's row lock, the lock every order's
 * reservation takes, against the custodian's balance less what open orders and withdrawals hold and Circaso's unswept
 * share. Its amount is reserved in the same transaction, so routing sees less free float at once. It is allowed online
 * or offline.
 */
class FloatWithdrawals(
    private val merchants: MerchantRepository,
    private val wallets: MerchantWalletRepository,
    private val credentials: CredentialRepository,
    private val pins: PinGuard,
    private val destinations: WithdrawalDestinationRepository,
    private val withdrawals: WithdrawalRepository,
    private val reservations: ReservationRepository,
    private val commissions: CommissionRepository,
    private val float: MerchantFloat,
    private val settings: SettingsBook,
    private val addresses: EvmAddresses,
    private val transactor: Transactor,
    private val clock: Clock,
) {
    // ---- addresses ----

    suspend fun book(merchantId: MerchantId, network: Network): DestinationBook =
        DestinationBook(
            destinations = destinations.live(merchantId, network),
            delayHours = settings.of(network).withdrawDestinationDelayHours,
            blockchain = wallets.find(merchantId, network)?.blockchain,
            max = MAX_DESTINATIONS,
            now = clock.instant(),
        )

    suspend fun addDestination(
        merchantId: MerchantId,
        network: Network,
        command: DestinationCommand,
    ): Resource<SavedDestination> {
        val wallet =
            when (val ready = readyWallet(merchantId, network)) {
                is Resource.Error -> return ready
                is Resource.Success -> ready.data
            }
        val address =
            when (val checked = addresses.check(command.address)) {
                is AddressCheck.Invalid -> return Resource.error(RampError.AddressInvalid(checked.problem))
                is AddressCheck.Valid -> checked.checksummed
            }
        if (wallets.ofMerchant(merchantId).any { it.address.equals(address, ignoreCase = true) }) {
            return Resource.error(RampError.DestinationOwnFloat)
        }
        val live = destinations.live(merchantId, network)
        if (live.any {
                it.address.equals(
                    address,
                    ignoreCase = true,
                )
            }
        ) {
            return Resource.error(RampError.DestinationExists)
        }
        if (live.size >= MAX_DESTINATIONS) return Resource.error(RampError.DestinationsFull(MAX_DESTINATIONS))
        checkPin(merchantId, command.pin)?.let { return it }
        val now = clock.instant()
        val destination =
            WithdrawalDestination(
                destinationId = Uuid7.generate(now),
                merchantId = merchantId,
                network = network,
                blockchain = wallet.blockchain,
                address = address,
                label = command.label,
                createdAt = now,
                usableAt = now.plus(settings.of(network).withdrawDestinationDelay),
            )
        if (!destinations.insert(destination)) return Resource.error(RampError.DestinationExists)
        return Resource.success(SavedDestination(destination, destination.isUsableAt(now)))
    }

    suspend fun removeDestination(
        merchantId: MerchantId,
        network: Network,
        destinationId: UUID,
        pin: String,
    ): Resource<Unit> {
        val destination =
            destinations.find(destinationId)?.takeIf {
                it.merchantId == merchantId && it.network == network && it.removedAt == null
            } ?: return Resource.error(RampError.DestinationNotFound)
        checkPin(merchantId, pin)?.let { return it }
        destinations.remove(destination.destinationId, clock.instant())
        return Resource.success(Unit)
    }

    // ---- withdrawals ----

    suspend fun withdraw(
        merchantId: MerchantId,
        network: Network,
        command: WithdrawCommand,
    ): Resource<Withdrawal> {
        val wallet =
            when (val ready = readyWallet(merchantId, network)) {
                is Resource.Error -> return ready
                is Resource.Success -> ready.data
            }
        val current = settings.of(network)
        if (command.amountMinor < current.withdrawMinMinor) {
            return Resource.error(
                RampError.WithdrawAmountInvalid(
                    current.withdrawMinMinor,
                    Amounts.usdc(current.withdrawMinMinor).amount,
                ),
            )
        }
        val now = clock.instant()
        val destination =
            destinations.find(command.destinationId)?.takeIf {
                it.merchantId == merchantId && it.network == network && it.removedAt == null
            } ?: return Resource.error(RampError.DestinationNotFound)
        if (!destination.isUsableAt(
                now,
            )
        ) {
            return Resource.error(RampError.DestinationNotReady(destination.usableAt.toString()))
        }
        checkPin(merchantId, command.pin)?.let { return it }
        command.idempotencyKey?.let { key -> withdrawals.byKey(merchantId, key)?.let { return Resource.success(it) } }

        // The custodian is read outside the transaction, which holds database work only.
        val balance =
            when (val held = float.balance(network, wallet.custodyWalletId)) {
                is Resource.Error -> {
                    return Resource.error(RampError.CustodyUnavailable)
                }

                is Resource.Success -> {
                    held.data.amountMinor
                        .min(Long.MAX_VALUE.toBigInteger())
                        .toLong()
                }
            }
        val withdrawal =
            Withdrawal(
                withdrawalId = Uuid7.generate(now),
                merchantId = merchantId,
                network = network,
                blockchain = wallet.blockchain,
                destinationId = destination.destinationId,
                destinationAddress = destination.address,
                destinationLabel = destination.label,
                amountMinor = command.amountMinor,
                state = WithdrawalState.REQUESTED,
                idempotencyKey = command.idempotencyKey,
                createdAt = now,
                updatedAt = now,
            )
        val dayStart = Periods.merchantDay(now).atStartOfDay().toInstant(Periods.MERCHANT_ZONE)
        val written =
            transactor.attempt("withdrawals.request") {
                merchants.lock(merchantId) ?: return@attempt Resource.error(RampError.MerchantNotFound)
                val spoken = reservations.openTotal(merchantId, network) + commissions.owedTotal(merchantId, network)
                val free = (balance - spoken).coerceAtLeast(0)
                val cap = current.withdrawDailyMaxMinor
                val left =
                    if (cap >
                        0
                    ) {
                        (cap - withdrawals.totalSince(merchantId, network, dayStart))
                    } else {
                        Long.MAX_VALUE
                    }
                when {
                    command.amountMinor > free -> {
                        Resource.error(RampError.InsufficientFloat(free, Amounts.usdc(free).amount))
                    }

                    command.amountMinor > left -> {
                        val shown = left.coerceAtLeast(0)
                        Resource.error(RampError.WithdrawLimitExceeded(shown, Amounts.usdc(shown).amount))
                    }

                    !withdrawals.insert(withdrawal) -> {
                        Resource.success(null)
                    }

                    else -> {
                        reservations.reserveWithdrawal(
                            withdrawal.withdrawalId,
                            merchantId,
                            network,
                            withdrawal.amountMinor,
                            now,
                        )
                        Resource.success(withdrawal)
                    }
                }
            }
        return when (written) {
            is Resource.Error -> written
            is Resource.Success -> written.data?.let { Resource.success(it) } ?: repeated(merchantId, command)
        }
    }

    /** The insert met the same idempotency key: another request with it won; its withdrawal is the answer. */
    private suspend fun repeated(merchantId: MerchantId, command: WithdrawCommand): Resource<Withdrawal> =
        command.idempotencyKey
            ?.let { withdrawals.byKey(merchantId, it) }
            ?.let { Resource.success(it) }
            ?: Resource.error(RampError.WithdrawalNotFound)

    suspend fun page(
        merchantId: MerchantId,
        network: Network,
        limit: Int,
        before: UUID?,
    ): WithdrawalPage {
        val bounded = limit.coerceIn(1, MAX_PAGE)
        val rows = withdrawals.page(merchantId, network, bounded + 1, before)
        val current = settings.of(network)
        val now = clock.instant()
        val dayStart = Periods.merchantDay(now).atStartOfDay().toInstant(Periods.MERCHANT_ZONE)
        return WithdrawalPage(
            withdrawals = rows.take(bounded),
            minMinor = current.withdrawMinMinor,
            dailyMaxMinor = current.withdrawDailyMaxMinor.takeIf { it > 0 },
            usedTodayMinor = withdrawals.totalSince(merchantId, network, dayStart),
            next = if (rows.size > bounded) rows[bounded - 1] else null,
        )
    }

    suspend fun one(merchantId: MerchantId, withdrawalId: UUID): Resource<Withdrawal> =
        withdrawals.find(withdrawalId)?.takeIf { it.merchantId == merchantId }?.let { Resource.success(it) }
            ?: Resource.error(RampError.WithdrawalNotFound)

    /** An approved merchant with a float wallet on [network]: the only one with anything to withdraw. */
    private suspend fun readyWallet(merchantId: MerchantId, network: Network): Resource<MerchantWallet> {
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        if (merchant.state != MerchantState.APPROVED) return Resource.error(RampError.MerchantNotApproved)
        val wallet = wallets.find(merchantId, network) ?: return Resource.error(RampError.MerchantSetupIncomplete)
        return Resource.success(wallet)
    }

    /** The PIN opens the merchant's sealed account: the same check, and the same count of wrong tries, as everywhere. */
    private suspend fun checkPin(merchantId: MerchantId, pin: String): Resource.Error? {
        val sealed = credentials.find(merchantId) ?: return Resource.Error(RampError.MerchantSetupIncomplete)
        return pins.open(merchantId, sealed, pin) as? Resource.Error
    }

    companion object {
        const val MAX_DESTINATIONS = 10
        private const val MAX_PAGE = 100
    }
}

/**
 * The send of each `REQUESTED` withdrawal and the following of each `SENT` one, in the **ops process only**, on
 * `ramp.release`'s run (every 5 seconds). The idempotency key is derived from the withdrawal id, so asking again after a
 * lost answer sends nothing twice. Circle out of reach: the withdrawal stays `REQUESTED` and is asked again with the
 * same key. Only a definitive answer fails it: Circle refusing the transfer, or the chain failing it. A failure gives
 * the reserved amount back to the float; a confirmation consumes it.
 */
class WithdrawalSends(
    private val withdrawals: WithdrawalRepository,
    private val wallets: MerchantWalletRepository,
    private val reservations: ReservationRepository,
    private val releases: Releases,
    private val transactor: Transactor,
    private val alerts: RampAlerts,
    private val clock: Clock,
) {
    /** One run on [network]; how many withdrawals it touched. */
    suspend fun run(treasury: TreasuryCustody, network: Network): Int {
        val open = withdrawals.open(network, LIMIT)
        open.forEach { advance(treasury, it) }
        return open.size
    }

    private suspend fun advance(treasury: TreasuryCustody, withdrawal: Withdrawal) {
        val transactionId =
            when (withdrawal.state) {
                WithdrawalState.REQUESTED -> send(treasury, withdrawal) ?: return
                WithdrawalState.SENT -> withdrawal.custodyTxId ?: return fail(withdrawal, "untracked")
                else -> return
            }
        // While the custodian can't be read, or the transfer is on its way, the withdrawal stays as it is.
        val transaction = (treasury.transaction(withdrawal.network, transactionId) as? Resource.Success)?.data ?: return
        val hash = transaction.txHash.ifEmpty { null }
        when (transaction.state) {
            TreasuryTransactionState.CONFIRMED -> {
                complete(withdrawal, hash)
            }

            TreasuryTransactionState.FAILED -> {
                fail(withdrawal, "failed_on_chain")
            }

            TreasuryTransactionState.IN_FLIGHT -> {
                hash?.let {
                    withdrawals.noteHash(
                        withdrawal.withdrawalId,
                        it,
                        clock.instant(),
                    )
                }
            }
        }
    }

    /** Asks the custodian to send; its transaction id, or null when there is nothing to follow yet. */
    private suspend fun send(treasury: TreasuryCustody, withdrawal: Withdrawal): String? {
        val wallet = wallets.find(withdrawal.merchantId, withdrawal.network)
        if (wallet == null) {
            fail(withdrawal, "wallet_missing")
            return null
        }
        val token =
            when (
                val found =
                    releases.usdcIn(
                        treasury,
                        withdrawal.network,
                        wallet.custodyWalletId,
                        wallet.blockchain,
                    )
            ) {
                is Releases.Token.Found -> {
                    found.balance
                }

                Releases.Token.Unknown -> {
                    return null
                }

                Releases.Token.Absent -> {
                    fail(withdrawal, "float_short")
                    return null
                }
            }
        val transfer =
            TreasuryTransfer(
                walletId = wallet.custodyWalletId,
                blockchain = wallet.blockchain,
                tokenId = token.tokenId,
                decimals = token.decimals,
                amountMinor = BigInteger.valueOf(withdrawal.amountMinor),
                destinationAddress = withdrawal.destinationAddress,
                refId = withdrawal.withdrawalId.toString(),
                idempotencyKey = withdrawalKey(withdrawal.withdrawalId),
            )
        return when (val sent = treasury.send(withdrawal.network, transfer)) {
            is Resource.Error -> {
                // Unavailable: Circle may have it, so it stays REQUESTED and the same key is asked again.
                if (sent.error !is IntegrationError.Unavailable) fail(withdrawal, "refused")
                null
            }

            is Resource.Success -> {
                withdrawals.sent(withdrawal.withdrawalId, sent.data.transactionId, clock.instant())
                sent.data.transactionId
            }
        }
    }

    private suspend fun complete(withdrawal: Withdrawal, txHash: String?) {
        val now = clock.instant()
        transactor.run("withdrawals.complete") {
            if (withdrawals.complete(withdrawal.withdrawalId, txHash, now)) {
                reservations.closeWithdrawal(withdrawal.withdrawalId, consumed = true, at = now)
            }
        }
    }

    private suspend fun fail(withdrawal: Withdrawal, reason: String) {
        val now = clock.instant()
        val failed =
            transactor.run("withdrawals.fail") {
                withdrawals.fail(withdrawal.withdrawalId, reason, now).also { moved ->
                    if (moved) reservations.closeWithdrawal(withdrawal.withdrawalId, consumed = false, at = now)
                }
            }
        if (failed) alerts.stuck("float withdrawal ${withdrawal.withdrawalId}", reason)
    }

    companion object {
        private const val LIMIT = 100

        /** Circle wants a UUID v4 and ours are v7: a name-based one, as the release's. */
        fun withdrawalKey(withdrawalId: UUID): UUID =
            UUID.nameUUIDFromBytes("ramp:withdrawal:$withdrawalId".toByteArray())
    }
}

/** The explorer page of a transaction on the chains the ramp delivers on; null without a hash or for another chain. */
object WithdrawalExplorer {
    fun url(blockchain: String, txHash: String?): String? {
        val hash = txHash?.takeIf { it.isNotBlank() } ?: return null
        return when (blockchain) {
            "BASE" -> "https://basescan.org/tx/$hash"
            "BASE-SEPOLIA" -> "https://sepolia.basescan.org/tx/$hash"
            else -> null
        }
    }
}
