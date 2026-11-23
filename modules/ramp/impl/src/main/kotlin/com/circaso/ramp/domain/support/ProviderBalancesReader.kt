package com.circaso.ramp.domain.support

import com.circaso.integrations.api.BalanceReading
import com.circaso.integrations.api.CollectionProviders
import com.circaso.integrations.api.IntegrationError
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.KesBalances
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.WalletBalance
import com.circaso.ramp.domain.ports.KesFloat
import com.circaso.ramp.domain.ports.PayoutJournal
import com.circaso.ramp.domain.ports.TradingSessions
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.shared.Resource
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant

/**
 * A merchant's three wallets at their provider, read **live** with their unlocked keys (decision 2026-11-23; three
 * wallets 2026-11-24): PayHero's service wallet (buying's prompt fees), FinoQ's service wallet (selling's payout fees)
 * and FinoQ's payment wallet (what payouts spend). It is the engine's [KesFloat] for a sale (the payment wallet), RM2's
 * wallets and the top-ups' and test payout's before-and-after.
 *
 * Reading needs the keys, so it works only on the node that holds them; otherwise, or when the provider can't say,
 * the answer is "unknown" (null), **never zero**, and one wallet is never read as another. Every reading is written
 * down for staff (each wallet with its source and time, a wallet unread this time keeping its last reading), since
 * the ops process holds nobody's keys. [read] answers only what was read now.
 */
class ProviderBalancesReader(
    private val sessions: TradingSessions,
    private val providers: CollectionProviders,
    private val merchants: MerchantRepository,
    private val clock: Clock,
    private val journal: PayoutJournal,
) : KesFloat {
    override suspend fun of(merchantId: MerchantId): Long? = merchants.find(merchantId)?.let { read(it)?.payoutMinor }

    /** The merchant's balances as the provider reads them now; null when nobody can ask (offline, provider down). */
    suspend fun read(merchant: Merchant): KesBalances? {
        val keys = sessions.of(merchant.merchantId) ?: return null
        val collections = providers.named(keys.provider) ?: return null
        val read =
            try {
                collections.balances(keys.credentials, merchant.payoutAccountId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Resource.error(IntegrationError.Unavailable(keys.provider), e)
            }
        val balances =
            when (read) {
                is Resource.Error -> {
                    journal.step("balances unread", merchant.merchantId.toString(), read.error.code)
                    return null
                }

                is Resource.Success -> {
                    val now = clock.instant()
                    KesBalances(
                        payheroService = read.data.collectionFees.at(now),
                        finoqService = read.data.payoutFees.at(now),
                        finoqPayment = read.data.payout.at(now),
                        readAt = now,
                    )
                }
            }
        merchants.recordBalances(merchant.merchantId, balances)
        journal.step("balances read", merchant.merchantId.toString(), sources(balances))
        return balances
    }

    /**
     * PayHero's service wallet as the on-ramp's "go online" check read it, written down so RM2 and staff see it too.
     */
    suspend fun recordPayheroService(merchantId: MerchantId, reading: BalanceReading) {
        val now = clock.instant()
        val balance = reading.at(now) ?: return
        merchants.recordBalances(merchantId, KesBalances(payheroService = balance, readAt = now))
    }

    private fun BalanceReading?.at(now: Instant): WalletBalance? {
        val amount = this?.amount?.takeIf { it.currency == RampSettings.FIAT } ?: return null
        return WalletBalance(amount.amountMinor, source, now)
    }

    private fun sources(balances: KesBalances): String =
        listOf(
            "payhero_service" to balances.payheroService,
            "finoq_service" to balances.finoqService,
            "finoq_payment" to balances.finoqPayment,
        ).joinToString(" ") { (name, balance) -> "$name=${balance?.source ?: "unread"}" }
}
