package com.circaso.ramp.domain.support

import com.circaso.integrations.api.TreasuryCustody
import com.circaso.integrations.api.TreasuryWallet
import com.circaso.integrations.api.TreasuryWalletRequest
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.MerchantWallet
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RevenueWallet
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.RevenueWalletRepository
import com.circaso.ramp.domain.repositories.SettingsRepository
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Clock
import java.util.UUID

/**
 * The ramp's wallets at the custodian, made by the **ops process** (it alone can sign): one wallet set per network for
 * the ramp, Circaso's own ramp wallet in it (`refId` `ramp-revenue`), and one float wallet per approved merchant
 * (`refId` the merchant's id). All are smart-contract accounts, so the custodian's gas sponsorship pays for releases
 * and sweeps and a merchant never has to keep the chain's own coin (decision 2026-11-22).
 *
 * Every create carries an idempotency key derived from what it makes, so asking again after a lost answer makes
 * nothing twice. A wallet is on the first chain of the network's settings.
 */
class FloatWallets(
    private val settings: SettingsBook,
    private val settingsRows: SettingsRepository,
    private val wallets: MerchantWalletRepository,
    private val revenue: RevenueWalletRepository,
    private val alerts: RampAlerts,
    private val clock: Clock,
) {
    /** Makes what is missing on [network]; how many wallets were made. Does nothing where this server can't sign. */
    suspend fun ensure(treasury: TreasuryCustody, network: Network): Int {
        if (!treasury.canSign(network)) return 0
        val current = settings.of(network)
        val blockchain = current.chains.firstOrNull() ?: return 0
        val walletSetId = walletSet(treasury, current) ?: return 0
        var made = 0
        if (revenue.find(network) == null) {
            create(treasury, network, walletSetId, blockchain, REVENUE_REF)?.let {
                if (revenue.insert(
                        RevenueWallet(network, it.blockchain, it.walletId, it.address),
                        clock.instant(),
                    )
                ) {
                    made++
                }
            }
        }
        wallets.approvedWithout(network, LIMIT).forEach { merchantId ->
            create(treasury, network, walletSetId, blockchain, merchantId.toString())?.let {
                if (wallets.insert(merchantWallet(merchantId, network, it), clock.instant())) made++
            }
        }
        return made
    }

    private suspend fun walletSet(treasury: TreasuryCustody, current: RampSettings): String? {
        current.walletSetId?.let { return it }
        val network = current.network
        val key = UUID.nameUUIDFromBytes("ramp:wallet-set:$network".toByteArray())
        return when (val created = treasury.createWalletSet(network, WALLET_SET_NAME, key)) {
            is Resource.Error -> {
                alerts.stuck("wallet set on $network", created.error.code)
                null
            }

            is Resource.Success -> {
                settingsRows.setWalletSet(network, created.data)
                settings.invalidate()
                created.data
            }
        }
    }

    private suspend fun create(
        treasury: TreasuryCustody,
        network: Network,
        walletSetId: String,
        blockchain: String,
        refId: String,
    ): TreasuryWallet? {
        val request =
            TreasuryWalletRequest(
                walletSetId = walletSetId,
                blockchains = listOf(blockchain),
                name = if (refId == REVENUE_REF) "Circaso ramp revenue" else "Ramp merchant float",
                refId = refId,
                idempotencyKey = UUID.nameUUIDFromBytes("ramp:wallet:$refId:$network".toByteArray()),
                accountType = TreasuryWalletRequest.ACCOUNT_SCA,
            )
        return when (val created = treasury.createWallets(network, request)) {
            is Resource.Error -> {
                alerts.stuck("float wallet on $network", created.error.code)
                null
            }

            is Resource.Success -> {
                created.data.firstOrNull { it.blockchain == blockchain } ?: created.data.firstOrNull()
            }
        }
    }

    private fun merchantWallet(
        merchantId: MerchantId,
        network: Network,
        wallet: TreasuryWallet,
    ) = MerchantWallet(merchantId, network, wallet.blockchain, wallet.walletId, wallet.address)

    companion object {
        const val REVENUE_REF = "ramp-revenue"
        const val WALLET_SET_NAME = "Circaso ramp"
        private const val LIMIT = 50
    }
}
