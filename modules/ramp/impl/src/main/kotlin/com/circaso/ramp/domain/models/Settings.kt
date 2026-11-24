package com.circaso.ramp.domain.models

import com.circaso.integrations.api.CollectionProviders
import com.circaso.integrations.api.KycEnvironment
import com.circaso.integrations.api.KycProviders
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
    /**
     * The least a merchant's prepaid service balance at the provider must hold for them to go online, in KES minor
     * units; 0 switches the check off. Below it the provider refuses their prompts.
     */
    val providerFeeFloorMinor: Long,
    val walletSetId: String?,
    val updatedBy: String?,
    val updatedAt: Instant,
    val version: Long,
    /** Which KYC provider checks this network's people; one of the names integrations offers. */
    val kycProvider: String = KycProviders.DIDIT,
    /**
     * Which side of the provider the checks run in: its sandbox (simulated) or live (real people). A verification
     * counts on this network only when it was made in this environment.
     */
    val kycEnvironment: KycEnvironment = environmentOf(network),
    /** The provider's workflow for a user ("Basic KYC"); empty means users can't be verified here yet. */
    val kycWorkflowUser: String = "",
    /** The provider's workflow for a merchant ("ID, selfie and AML"); empty means merchants can't be verified yet. */
    val kycWorkflowMerchant: String = "",
    /** The merchant portal's origin: where the provider's hosted page sends a merchant back to. */
    val merchantPortalUrl: String = DEFAULT_MERCHANT_PORTAL_URL,
    /** How many checks one person may start in a day (a declined or expired one may be tried again). */
    val kycAttemptsPerDay: Int = DEFAULT_KYC_ATTEMPTS_PER_DAY,
    /** How long a started check may stay unfinished before it is marked expired; also how long its link lasts. */
    val kycPendingHours: Int = DEFAULT_KYC_PENDING_HOURS,
    /** Selling's own switch (decision 2026-11-23): buying's [state] doesn't open it. */
    val offRampState: RampState = RampState.OFF,
    /** Selling's limits, in what the user receives (KES minor units). */
    val offRampMinMinor: Long = DEFAULT_OFFRAMP_MIN_MINOR,
    val offRampMaxMinor: Long = DEFAULT_OFFRAMP_MAX_MINOR,
    val offRampDailyLimitMinor: Long = DEFAULT_OFFRAMP_DAILY_MINOR,
    /** How long a sale waits for the user's USDC before it expires and the merchant's shillings are freed. */
    val transferWindowSeconds: Int = DEFAULT_TRANSFER_WINDOW_SECONDS,
    /**
     * How long a funded sale whose payout provably didn't go out (refused, no balance, provider out of reach, the
     * merchant offline) keeps being retried with its merchant before the USDC is refunded.
     */
    val payoutWindowSeconds: Int = DEFAULT_PAYOUT_WINDOW_SECONDS,
    /** Staff's payout fee table: used only when the provider's own published table can't be read. Empty: none. */
    val payoutFeeBands: List<PayoutFeeBand> = emptyList(),
    /** Below this payout balance a merchant's portal says `payout_balance_low` (KES minor units; 0: never). */
    val payoutBalanceLowMinor: Long = DEFAULT_PAYOUT_BALANCE_LOW_MINOR,
    /** How long a merchant's new withdrawal address waits before it can be withdrawn to (2026-11-24); 0 to 720. */
    val withdrawDestinationDelayHours: Int = DEFAULT_WITHDRAW_DESTINATION_DELAY_HOURS,
    /** The smallest float withdrawal, USDC minor units. */
    val withdrawMinMinor: Long = DEFAULT_WITHDRAW_MIN_MINOR,
    /** The most a merchant may withdraw in a Nairobi day, USDC minor units; 0: no cap. */
    val withdrawDailyMaxMinor: Long = 0,
) {
    /** The cooling-off delay of a new withdrawal address. */
    val withdrawDestinationDelay: Duration get() = Duration.ofHours(withdrawDestinationDelayHours.toLong())

    val quoteLifetime: Duration get() = Duration.ofSeconds(quoteSeconds.toLong())

    /** How long an unpaid prompt keeps its float. */
    val paymentWindow: Duration get() = Duration.ofSeconds(paymentWindowSeconds.toLong())

    val limits: RampLimits get() = RampLimits(FIAT, orderMinMinor, orderMaxMinor, dailyLimitMinor)

    /** Selling's limits, in what the user receives. */
    val offRampLimits: RampLimits get() = RampLimits(FIAT, offRampMinMinor, offRampMaxMinor, offRampDailyLimitMinor)

    val transferWindow: Duration get() = Duration.ofSeconds(transferWindowSeconds.toLong())

    val payoutWindow: Duration get() = Duration.ofSeconds(payoutWindowSeconds.toLong())

    /** The state that opens [direction] on this network. */
    fun stateOf(direction: Direction): RampState = if (direction == Direction.ON_RAMP) state else offRampState

    /** How long an order in [direction] needs its merchant online for: the payment window, or the transfer's. */
    fun windowOf(direction: Direction): Duration = if (direction == Direction.ON_RAMP) paymentWindow else transferWindow

    /** KYC is enforced on mainnet whatever the row says; the testnet may run without it while staff are testing. */
    val enforcesKyc: Boolean get() = kycRequired || network == Network.MAINNET

    /** How long a started check may stay unfinished, and how long its link is asked to last. */
    val kycPendingFor: Duration get() = Duration.ofHours(kycPendingHours.toLong())

    /** Where the provider's hosted page sends a merchant when they finish: a page of the merchant portal. */
    val merchantKycCallbackUrl: String get() = "$merchantPortalUrl$MERCHANT_KYC_CALLBACK_PATH"

    companion object {
        const val FIAT = "KES"
        const val ASSET = "USDC"
        const val ASSET_DECIMALS = 6
        const val MAX_FEE_BPS = 500
        const val ALL_BPS = 10_000

        /** KES 50: a few dozen prompts' worth of the provider's fees. */
        const val DEFAULT_PROVIDER_FEE_FLOOR_MINOR = 5_000L

        const val DEFAULT_MERCHANT_PORTAL_URL = "https://merchants.circaso.com"
        const val DEFAULT_KYC_ATTEMPTS_PER_DAY = 3
        const val DEFAULT_KYC_PENDING_HOURS = 24

        /** Selling's defaults: KES 10 to 5 000 a sale, 20 000 a day, 15 minutes to send, 30 to retry a payout. */
        const val DEFAULT_OFFRAMP_MIN_MINOR = 1_000L
        const val DEFAULT_OFFRAMP_MAX_MINOR = 500_000L
        const val DEFAULT_OFFRAMP_DAILY_MINOR = 2_000_000L
        const val DEFAULT_TRANSFER_WINDOW_SECONDS = 900
        const val DEFAULT_PAYOUT_WINDOW_SECONDS = 1_800

        /** KES 1 000: a few sales' worth. */
        const val DEFAULT_PAYOUT_BALANCE_LOW_MINOR = 100_000L

        /** A day: long enough for a merchant to notice an address they didn't add (the email, the portal). */
        const val DEFAULT_WITHDRAW_DESTINATION_DELAY_HOURS = 24
        const val MAX_WITHDRAW_DESTINATION_DELAY_HOURS = 720

        /** 1 USDC. */
        const val DEFAULT_WITHDRAW_MIN_MINOR = 1_000_000L

        /** The portal page a merchant lands on after the provider's hosted check; it reads RM2 for the outcome. */
        const val MERCHANT_KYC_CALLBACK_PATH = "/kyc/done"

        /**
         * Didit's sandbox workflows (read from the Didit account on 2026-11-22): "Basic KYC" for users (ID document,
         * liveness, face match) and "ID, selfie and AML" for merchants. The live application has none yet, so a
         * network that verifies live starts with them empty and staff fill them in the admin.
         */
        const val SANDBOX_WORKFLOW_USER = "a3cb5782-d5c1-4c35-832d-ed373d80a0ea"
        const val SANDBOX_WORKFLOW_MERCHANT = "dabdb52a-b4bc-435a-b2bf-a636bb17bad6"

        /** What a network verifies in until staff say otherwise: the testnet in the sandbox, mainnet live. */
        fun environmentOf(network: Network): KycEnvironment =
            if (network == Network.TESTNET) KycEnvironment.SANDBOX else KycEnvironment.LIVE

        /** How long after a step the sweep waits before asking the vendor itself (a lost callback). */
        val RECHECK_AFTER: Duration = Duration.ofSeconds(30)

        /** A payment the provider still can't settle after this goes to a person. */
        val REVIEW_AFTER: Duration = Duration.ofMinutes(10)

        /**
         * What a network starts as, written once when its row is missing: off (shillings are real on both networks, so
         * staff open it on purpose), a 1.5 % fee split 70/30, KYC required everywhere but the testnet, and PayHero
         * for the prompts (on a developer's machine the caller passes `log`, so an order completes end to end).
         * Identity checks are Didit's: the testnet in its sandbox with the two workflows that exist there, mainnet live with
         * the workflows still to be named (on a developer's machine the caller passes `log`, which approves at once).
         */
        fun defaults(
            network: Network,
            at: Instant,
            collectionsProvider: String = CollectionProviders.PAYHERO,
            kycProvider: String = KycProviders.DIDIT,
        ): RampSettings =
            RampSettings(
                network = network,
                state = RampState.OFF,
                chains = listOf(if (network == Network.TESTNET) "BASE-SEPOLIA" else "BASE"),
                orderMinMinor = 1_000,
                orderMaxMinor = 500_000,
                dailyLimitMinor = 2_000_000,
                promptsPerHour = 0,
                feeBps = 150,
                merchantShareBps = 7_000,
                feeSettleHourUtc = 21,
                quoteSeconds = 60,
                paymentWindowSeconds = 120,
                kycRequired = network == Network.MAINNET,
                callbackBaseUrl = "https://api.circaso.com",
                collectionsProvider = collectionsProvider,
                providerFeeFloorMinor = DEFAULT_PROVIDER_FEE_FLOOR_MINOR,
                walletSetId = null,
                updatedBy = null,
                updatedAt = at,
                version = 0,
                kycProvider = kycProvider,
                kycEnvironment = environmentOf(network),
                kycWorkflowUser = if (network == Network.TESTNET) SANDBOX_WORKFLOW_USER else "",
                kycWorkflowMerchant = if (network == Network.TESTNET) SANDBOX_WORKFLOW_MERCHANT else "",
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
