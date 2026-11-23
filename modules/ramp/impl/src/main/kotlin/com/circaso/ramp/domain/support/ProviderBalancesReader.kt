package com.circaso.ramp.domain.support

import com.circaso.integrations.api.CollectionProviders
import com.circaso.integrations.api.IntegrationError
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.KesBalances
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.ports.KesFloat
import com.circaso.ramp.domain.ports.TradingSessions
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.shared.Resource
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * A merchant's balances at their payout provider, read **live** with their unlocked keys (decision 2026-11-23): what
 * payouts can spend and what the provider's fees are taken from. It is the engine's [KesFloat] for a sale, RM2's
 * off-ramp card and the test payout's before-and-after.
 *
 * Reading needs the keys, so it works only on the node that holds them while the merchant is online; otherwise, or
 * when the provider can't say, the answer is "unknown" (null), **never zero**. Every reading is written down for staff
 * (`merchants.payout_balance_minor` and its source, with the time), since the ops process holds nobody's keys.
 */
class ProviderBalancesReader(
    private val sessions: TradingSessions,
    private val providers: CollectionProviders,
    private val merchants: MerchantRepository,
    private val clock: Clock,
) : KesFloat {
    private val log = LoggerFactory.getLogger(ProviderBalancesReader::class.java)

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
                    log.info("ramp balances unread: merchant={} error={}", merchant.merchantId, read.error.code)
                    return null
                }

                is Resource.Success -> {
                    KesBalances(
                        payoutMinor =
                            read.data.payout
                                ?.amount
                                ?.takeIf { it.currency == RampSettings.FIAT }
                                ?.amountMinor,
                        payoutSource = read.data.payout?.source,
                        feeMinor =
                            read.data.fees
                                ?.amount
                                ?.takeIf { it.currency == RampSettings.FIAT }
                                ?.amountMinor,
                        feeSource = read.data.fees?.source,
                        readAt = clock.instant(),
                    )
                }
            }
        merchants.recordBalances(merchant.merchantId, balances)
        return balances
    }
}
