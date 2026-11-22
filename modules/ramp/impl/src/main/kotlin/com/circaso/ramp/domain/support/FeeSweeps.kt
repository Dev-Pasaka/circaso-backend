package com.circaso.ramp.domain.support

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.TreasuryCustody
import com.circaso.integrations.api.TreasuryTransactionState
import com.circaso.integrations.api.TreasuryTransfer
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.RevenueWallet
import com.circaso.ramp.domain.models.Settlement
import com.circaso.ramp.domain.models.SettlementState
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.ports.Transactor
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.RevenueWalletRepository
import com.circaso.ramp.domain.repositories.SettlementRepository
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The daily sweep of Circaso's share (decision 2026-11-22), in the **ops process only**. Each completed order's
 * commission says what the merchant's float owes Circaso in USDC; the float holds it (it is subtracted from free
 * float) until, once a day at the settings' hour, one transfer per merchant sends the whole owed total to Circaso's
 * ramp wallet. There is no threshold.
 *
 * A sweep is a `fee_settlements` row, `PENDING → CONFIRMED | FAILED`. Opening it names it on every commission no sweep
 * had taken over, in the same transaction, so two runs can never collect one commission twice; the transfer's
 * idempotency key is derived from the sweep's id, so asking again sends nothing twice. While the custodian can't be
 * reached the sweep stays `PENDING` and every run tries again. When the custodian refuses or the chain fails it, the
 * sweep is `FAILED`, its commissions are free again (still held in the float), and a later run opens a new one.
 */
class FeeSweeps(
    private val settlements: SettlementRepository,
    private val wallets: MerchantWalletRepository,
    private val revenue: RevenueWalletRepository,
    private val releases: Releases,
    private val settings: SettingsBook,
    private val transactor: Transactor,
    private val alerts: RampAlerts,
    private val clock: Clock,
) {
    /** One run on [network]: moves every pending sweep on, then opens the ones that fell due. How many it touched. */
    suspend fun run(treasury: TreasuryCustody, network: Network): Int {
        if (!treasury.canSign(network)) return 0
        val to = revenue.find(network) ?: return 0
        var handled = 0
        settlements.pending(network, LIMIT).forEach { pending ->
            advance(treasury, pending, to)
            handled++
        }
        val now = clock.instant()
        val due = Periods.lastSweepDue(now, settings.of(network).feeSettleHourUtc)
        settlements.merchantsOwing(network, LIMIT).forEach { merchantId ->
            if (settlements.hasRecent(merchantId, network, since = due, failedSince = now.minus(RETRY_AFTER))) {
                return@forEach
            }
            open(merchantId, network, due, now)?.let { opened ->
                advance(treasury, opened, to)
                handled++
            }
        }
        return handled
    }

    private suspend fun open(
        merchantId: MerchantId,
        network: Network,
        due: Instant,
        now: Instant,
    ): Settlement? =
        transactor.run("fees.open-sweep") {
            val id = Uuid7.generate(now)
            val stamped = settlements.stamp(merchantId, network, id)
            if (stamped.totalMinor <= 0) {
                null
            } else {
                Settlement(
                    settlementId = id,
                    merchantId = merchantId,
                    network = network,
                    day = Periods.sweepDay(due),
                    amountMinor = stamped.totalMinor,
                    orders = stamped.orders,
                    state = SettlementState.PENDING,
                    custodyTxId = null,
                    txHash = null,
                    failureReason = null,
                    createdAt = now,
                ).also { settlements.insert(it) }
            }
        }

    private suspend fun advance(
        treasury: TreasuryCustody,
        sweep: Settlement,
        to: RevenueWallet,
    ) {
        val transactionId = sweep.custodyTxId ?: send(treasury, sweep, to) ?: return
        // While the custodian can't be read, or the transfer is still on its way, the sweep stays pending.
        val transaction = (treasury.transaction(sweep.network, transactionId) as? Resource.Success)?.data ?: return
        if (transaction.state == TreasuryTransactionState.CONFIRMED) {
            settlements.confirm(sweep.settlementId, transaction.txHash.ifEmpty { null }, clock.instant())
        } else if (transaction.state == TreasuryTransactionState.FAILED) {
            fail(sweep, "failed_on_chain")
        }
    }

    /** Asks the custodian to send the sweep; its transaction id, or null when there is nothing to follow yet. */
    private suspend fun send(
        treasury: TreasuryCustody,
        sweep: Settlement,
        to: RevenueWallet,
    ): String? {
        val wallet = wallets.find(sweep.merchantId, sweep.network)
        if (wallet == null) {
            fail(sweep, "merchant_wallet_missing")
            return null
        }
        val token =
            when (val found = releases.usdcIn(treasury, sweep.network, wallet.custodyWalletId, wallet.blockchain)) {
                is Releases.Token.Found -> {
                    found.balance
                }

                Releases.Token.Unknown -> {
                    return null
                }

                Releases.Token.Absent -> {
                    fail(sweep, "float_short")
                    return null
                }
            }
        val transfer =
            TreasuryTransfer(
                walletId = wallet.custodyWalletId,
                blockchain = wallet.blockchain,
                tokenId = token.tokenId,
                decimals = token.decimals,
                amountMinor = BigInteger.valueOf(sweep.amountMinor),
                destinationAddress = to.address,
                refId = sweep.settlementId.toString(),
                idempotencyKey = sweepKey(sweep.settlementId),
            )
        return when (val sent = treasury.send(sweep.network, transfer)) {
            is Resource.Error -> {
                // Unavailable: the custodian may have it, so the sweep stays pending and the same key is asked again.
                if (sent.error !is IntegrationError.Unavailable) fail(sweep, "refused")
                null
            }

            is Resource.Success -> {
                settlements.sent(sweep.settlementId, sent.data.transactionId, clock.instant())
                sent.data.transactionId
            }
        }
    }

    private suspend fun fail(sweep: Settlement, reason: String) {
        if (settlements.fail(sweep.settlementId, reason, clock.instant())) {
            alerts.stuck("fee sweep ${sweep.settlementId}", reason)
        }
    }

    companion object {
        private const val LIMIT = 100

        /** How long after a refused sweep the next one may be opened for the same merchant. */
        val RETRY_AFTER: Duration = Duration.ofHours(1)

        fun sweepKey(settlementId: UUID): UUID = UUID.nameUUIDFromBytes("ramp:sweep:$settlementId".toByteArray())
    }
}
