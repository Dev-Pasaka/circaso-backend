package com.circaso.ramp.domain.models

import com.circaso.integrations.api.CollectionProviders
import com.circaso.shared.Network
import com.circaso.shared.UserId
import java.time.Duration
import java.time.Instant

/** Whether a network's ramp is open, and to whom. */
enum class RampState { OFF, PREVIEW, ON }

/**
 * One network's ramp settings (decision 2026-11-18: the environment holds keys and secrets only; these live in
 * `ramp.settings`, where staff edit them). A change applies to quotes made after it; an order keeps its quote's fee.
 *
 * TODO(decision: KYC tiers and limits) [orderMinMinor], [orderMaxMinor], [dailyLimitMinor] and [promptsPerHour] are
 * settings until the tiers are decided.
 */
data class RampSettings(
    val network: Network,
    val state: RampState,
    val chains: List<String>,
    val orderMinMinor: Long,
    val orderMaxMinor: Long,
    val dailyLimitMinor: Long,
    val promptsPerHour: Int,
    val feeBps: Int,
    val merchantShareBps: Int,
    val feeSettleHourUtc: Int,
    val quoteSeconds: Int,
    val paymentWindowSeconds: Int,
    val kycRequired: Boolean,
    val callbackBaseUrl: String,
    /** Which collections provider sends this network's prompts; one of the names integrations offers. */
    val collectionsProvider: String,
    val walletSetId: String?,
    val updatedBy: String?,
    val updatedAt: Instant,
    val version: Long,
) {
    val quoteLifetime: Duration get() = Duration.ofSeconds(quoteSeconds.toLong())

    /** How long an unpaid prompt keeps its float. */
    val paymentWindow: Duration get() = Duration.ofSeconds(paymentWindowSeconds.toLong())

    val limits: RampLimits get() = RampLimits(FIAT, orderMinMinor, orderMaxMinor, dailyLimitMinor)

    /** KYC is enforced on mainnet whatever the row says; the testnet may run without it until Didit is built. */
    val enforcesKyc: Boolean get() = kycRequired || network == Network.MAINNET

    companion object {
        const val FIAT = "KES"
        const val ASSET = "USDC"
        const val ASSET_DECIMALS = 6
        const val MAX_FEE_BPS = 500
        const val ALL_BPS = 10_000

        /** How long after a step the sweep waits before asking the vendor itself (a lost callback). */
        val RECHECK_AFTER: Duration = Duration.ofSeconds(30)

        /** A payment the provider still can't settle after this goes to a person. */
        val REVIEW_AFTER: Duration = Duration.ofMinutes(10)

        /**
         * What a network starts as, written once when its row is missing: off (shillings are real on both networks, so
         * staff open it on purpose), a 1.5 % fee split 70/30, KYC required everywhere but the testnet, and PayHero
         * for the prompts (on a developer's machine the caller passes `log`, so an order completes end to end).
         */
        fun defaults(
            network: Network,
            at: Instant,
            collectionsProvider: String = CollectionProviders.PAYHERO,
        ): RampSettings =
            RampSettings(
                network = network,
                state = RampState.OFF,
                chains = listOf(if (network == Network.TESTNET) "BASE-SEPOLIA" else "BASE"),
                orderMinMinor = 1_000,
                orderMaxMinor = 500_000,
                dailyLimitMinor = 2_000_000,
                promptsPerHour = 5,
                feeBps = 150,
                merchantShareBps = 7_000,
                feeSettleHourUtc = 21,
                quoteSeconds = 60,
                paymentWindowSeconds = 120,
                kycRequired = network == Network.MAINNET,
                callbackBaseUrl = "https://api.circaso.com",
                collectionsProvider = collectionsProvider,
                walletSetId = null,
                updatedBy = null,
                updatedAt = at,
                version = 0,
            )
    }
}

data class SettingsChange(val before: RampSettings, val after: RampSettings)

/** Someone allowed to trade on [network] while its ramp is `PREVIEW`. */
data class PreviewAccess(
    val userId: UserId,
    val network: Network,
    val grantedBy: String?,
    val at: Instant,
)
