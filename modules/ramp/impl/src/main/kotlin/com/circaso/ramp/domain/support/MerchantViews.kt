package com.circaso.ramp.domain.support

import com.circaso.integrations.api.MerchantFloat
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.FloatView
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantOverview
import com.circaso.ramp.domain.models.MerchantWallet
import com.circaso.ramp.domain.models.OffRampView
import com.circaso.ramp.domain.models.OfflineReason
import com.circaso.ramp.domain.models.OnlineView
import com.circaso.ramp.domain.models.PaymentsIssue
import com.circaso.ramp.domain.models.PaymentsView
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.ports.Pricing
import com.circaso.ramp.domain.ports.TradingSessions
import com.circaso.ramp.domain.repositories.CommissionRepository
import com.circaso.ramp.domain.repositories.CredentialRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.PayoutHoldRepository
import com.circaso.ramp.domain.repositories.ReservationRepository
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Duration

/**
 * The merchant as the portal shows them (RM2): what is set up, the float wallet to fund and what it holds, and what
 * still stands between them and going online. Online means inside a session (a timed window, or open-ended until they
 * stop it) **and** unlocked on this node. A node that restarted restores its merchants' keys before it starts its jobs
 * (2026-11-24); one whose session couldn't be restored reads as offline, with the reason `RESTART`, until they unlock
 * again.
 */
class MerchantViews(
    private val wallets: MerchantWalletRepository,
    private val credentials: CredentialRepository,
    private val reservations: ReservationRepository,
    private val commissions: CommissionRepository,
    private val float: MerchantFloat,
    private val sessions: TradingSessions,
    private val settings: SettingsBook,
    private val pricing: Pricing,
    private val verifications: Verifications,
    private val clock: Clock,
    private val merchants: MerchantRepository,
    private val payoutHolds: PayoutHoldRepository,
    private val balances: ProviderBalancesReader,
) {
    suspend fun overview(merchant: Merchant, network: Network): MerchantOverview {
        val current = settings.of(network)
        val wallet = wallets.find(merchant.merchantId, network)
        val sealed = credentials.find(merchant.merchantId)
        val payments =
            sealed?.let {
                val callbackUrl = merchant.callbackKey?.let { key -> Callbacks.url(current.callbackBaseUrl, key) }
                PaymentsView(it.provider, it.channelId, callbackUrl)
            }
        val offRamp = offRamp(merchant, current)
        return MerchantOverview(
            merchant = merchant,
            network = network,
            wallet = wallet,
            float = wallet?.let { floatOf(it) },
            payments = payments,
            paymentsCurrent = sealed?.provider == current.collectionsProvider,
            presence = presenceOf(merchant),
            floatFloorMinor = floatFloor(current),
            kyc = verifications.view(Subject.merchant(merchant.merchantId), current),
            offRamp = offRamp,
            providerWallets = ProviderWallets.of(merchant, offRamp.balances, current),
        )
    }

    /**
     * The off-ramp card. The balances are read from the provider while the merchant is online on this node and the
     * last reading is older than [FRESH_FOR] (so a portal polling RM2 doesn't call the provider each time); otherwise
     * the last reading is shown with its time.
     */
    suspend fun offRamp(merchant: Merchant, current: RampSettings): OffRampView {
        val kept = merchants.balances(merchant.merchantId)
        val stale = kept.readAt?.plus(FRESH_FOR)?.isBefore(clock.instant()) ?: true
        val fresh = if (stale && sessions.of(merchant.merchantId) != null) balances.read(merchant) else null
        val shown = fresh?.over(kept) ?: kept
        val live = fresh != null || (!stale && sessions.of(merchant.merchantId) != null)
        // Selling's wallets are FinoQ's: its payment wallet and its service wallet (PayHero's is buying's).
        val flags =
            listOfNotNull(
                OffRampView.PAYOUT_ACCOUNT_MISSING.takeIf { merchant.payoutAccountId == null },
                OffRampView.PAYOUT_BALANCE_LOW.takeIf {
                    ProviderWallets.below(shown.finoqPayment, current.payoutBalanceLowMinor)
                },
                OffRampView.SERVICE_BALANCE_LOW.takeIf {
                    ProviderWallets.below(shown.finoqService, current.providerFeeFloorMinor)
                },
                merchant.payoutIssue?.name?.lowercase(),
            )
        return OffRampView(
            offered = merchant.offersOfframp,
            accountId = merchant.payoutAccountId,
            balances = shown,
            live = live,
            heldMinor = payoutHolds.openTotal(merchant.merchantId),
            payoutIssue = merchant.payoutIssue,
            payoutIssueAt = merchant.payoutIssueAt,
            flags = flags,
        )
    }

    /**
     * The session as the portal shows it. A session the database still holds but whose keys this node doesn't hold
     * (its working copy couldn't be restored) reads as offline for `RESTART` at once, before `ramp.end-sessions` ends
     * it in the database within the minute.
     */
    fun presenceOf(merchant: Merchant): OnlineView {
        val now = clock.instant()
        val keysHeld = sessions.of(merchant.merchantId) != null
        val inSession = merchant.isOnlineAt(now)
        if (inSession && keysHeld) {
            return OnlineView(
                online = true,
                until = merchant.onlineUntil,
                openEnded = merchant.onlineOpenEnded,
                keysHeld = true,
                since = merchant.onlineSince,
            )
        }
        val lost = inSession && !keysHeld
        return OnlineView(
            online = false,
            until = null,
            keysHeld = keysHeld,
            offlineReason = if (lost) OfflineReason.RESTART else merchant.offlineReason,
            offlineAt = if (lost) null else merchant.offlineAt,
        )
    }

    /** The wallet's USDC and what is spoken for; null when the custodian can't be asked. */
    suspend fun floatOf(wallet: MerchantWallet): FloatView? {
        val balance =
            when (val held = float.balance(wallet.network, wallet.custodyWalletId)) {
                is Resource.Error -> {
                    return null
                }

                is Resource.Success -> {
                    held.data.amountMinor
                        .min(Long.MAX_VALUE.toBigInteger())
                        .toLong()
                }
            }
        val reserved =
            reservations.openTotal(wallet.merchantId, wallet.network) +
                commissions.owedTotal(wallet.merchantId, wallet.network)
        return FloatView(balance, reserved, reservations.withdrawingTotal(wallet.merchantId, wallet.network))
    }

    private suspend fun floatFloor(current: RampSettings): Long =
        when (
            val priced =
                pricing.price(
                    current.network,
                    Direction.ON_RAMP,
                    current.orderMinMinor,
                    null,
                    current.feeBandsOf(Direction.ON_RAMP),
                )
        ) {
            is Resource.Success -> priced.data.assetMinor
            is Resource.Error -> 0
        }

    private companion object {
        val FRESH_FOR: Duration = Duration.ofSeconds(30)
    }
}
