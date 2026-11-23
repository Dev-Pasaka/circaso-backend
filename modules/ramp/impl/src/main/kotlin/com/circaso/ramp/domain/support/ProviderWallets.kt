package com.circaso.ramp.domain.support

import com.circaso.ramp.domain.models.KesBalances
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.PaymentsIssue
import com.circaso.ramp.domain.models.PayoutIssue
import com.circaso.ramp.domain.models.ProviderWalletView
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.TopUpTarget
import com.circaso.ramp.domain.models.WalletBalance

/**
 * A merchant's three wallets at their provider as the portal (RM2) and staff (RO7) show them (2026-11-24), each with
 * its last reading and whether it is low:
 *
 * - PayHero's service wallet (buying's prompt fees): under the network's provider-fee floor, or since the provider
 *   refused a prompt for it (`paymentsIssue` `SERVICE_BALANCE`).
 * - FinoQ's service wallet (selling's payout fees): under the same floor.
 *   TODO(decision: a floor of its own for FinoQ's service wallet, or the one setting for both service wallets)
 * - FinoQ's payment wallet (payouts): under the payout line, or since a payout was refused for it.
 *
 * A wallet never read is not low: it is unknown.
 */
object ProviderWallets {
    fun of(
        merchant: Merchant,
        balances: KesBalances,
        current: RampSettings,
    ): List<ProviderWalletView> {
        val feeFloor = current.providerFeeFloorMinor
        val payoutLine = current.payoutBalanceLowMinor
        val promptRefused = merchant.paymentsIssue == PaymentsIssue.SERVICE_BALANCE
        val payoutRefused = merchant.payoutIssue == PayoutIssue.PAYOUT_BALANCE
        return listOf(
            ProviderWalletView(
                target = TopUpTarget.PAYHERO_SERVICE,
                balance = balances.payheroService,
                low = promptRefused || below(balances.payheroService, feeFloor),
                lowBelowMinor = feeFloor.takeIf { it > 0 },
            ),
            ProviderWalletView(
                target = TopUpTarget.FINOQ_SERVICE,
                balance = balances.finoqService,
                low = below(balances.finoqService, feeFloor),
                lowBelowMinor = feeFloor.takeIf { it > 0 },
            ),
            ProviderWalletView(
                target = TopUpTarget.FINOQ_PAYMENT,
                balance = balances.finoqPayment,
                low = payoutRefused || below(balances.finoqPayment, payoutLine),
                lowBelowMinor = payoutLine.takeIf { it > 0 },
            ),
        )
    }

    /** Read, and under a line that is set (0 is "never"). */
    fun below(balance: WalletBalance?, lineMinor: Long): Boolean =
        balance != null && lineMinor > 0 && balance.minor < lineMinor
}
