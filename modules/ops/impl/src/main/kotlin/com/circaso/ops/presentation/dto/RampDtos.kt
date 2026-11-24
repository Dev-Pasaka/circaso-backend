package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

@Serializable
data class RampMerchantWalletDto(val network: String, val blockchain: String, val address: String)

/**
 * An identity verification in one provider environment (`environment`: SANDBOX | LIVE; a network's settings name the
 * one it trusts). `status`: PENDING | IN_REVIEW | VERIFIED | DECLINED | EXPIRED. `reason` is the ramp's own code
 * (`document`, `document_expired`, `document_unreadable`, `liveness`, `face_match`, `not_approved`, `declined`,
 * `identity_in_use`, `blocked`, `session_expired`, `resubmission_requested`). `hasIdentity` says an identity key is
 * held (a live approval); no document, name or key is ever shown.
 */
@Serializable
data class RampVerificationDto(
    val environment: String,
    val status: String,
    val reason: String? = null,
    val provider: String,
    val attempts: Int,
    val hasIdentity: Boolean,
    val startedAt: String? = null,
    val verifiedAt: String? = null,
    val updatedAt: String,
)

/** A user barred from the ramp. `reason`: OPS | REVERSAL | IDENTITY (they verified as a barred identity). */
@Serializable
data class RampBlockDto(
    val blockId: String,
    val userId: String,
    val reason: String,
    val note: String,
    val createdBy: String,
    val createdAt: String,
)

@Serializable
data class RampBlocksResponse(val blocks: List<RampBlockDto>, val nextCursor: String? = null)

/** A user's ramp verification in each environment they have one (none: never started), and their block if barred. */
@Serializable
data class RampUserVerificationResponse(
    val userId: String,
    val verifications: List<RampVerificationDto>,
    val blocked: Boolean,
    val block: RampBlockDto? = null,
)

/** `POST /ops/ramp/blocks`: who to bar and why (3 to 500 characters; audited and kept on the block). */
@Serializable
data class RampBlockRequest(val userId: String = "", val reason: String = "")

/** Resetting a verification: which environment (SANDBOX | LIVE) and why. */
@Serializable
data class RampVerificationResetRequest(val environment: String = "", val reason: String = "")

@Serializable
data class RampVerificationResetResponse(val removed: Boolean)

/**
 * A ramp merchant. `state`: NEW | APPLIED | APPROVED | SUSPENDED. Their payment credentials are never shown.
 * `verifications` is their identity verification in each provider environment they have one.
 */
@Serializable
data class RampMerchantDto(
    val merchantId: String,
    val email: String,
    val displayName: String,
    val contactPhone: String,
    val state: String,
    val online: Boolean,
    val onlineUntil: String? = null,
    val paymentsConnected: Boolean,
    val wallets: List<RampMerchantWalletDto>,
    val approvedBy: String? = null,
    val approvedAt: String? = null,
    val createdAt: String,
    val verifications: List<RampVerificationDto> = emptyList(),
    /** INDIVIDUAL | BUSINESS, set by staff: it decides the order limit the merchant starts with. */
    val merchantType: String = "INDIVIDUAL",
    /** Staff's own limit for this merchant, KES cents; null: their type's default from the engine settings. */
    val orderLimitMinor: String? = null,
    /** The merchant's own switches on the portal: which directions they take orders in. */
    val offersOnRamp: Boolean = true,
    val offersOffRamp: Boolean = false,
    /** Online until the merchant stops it (no `onlineUntil`); a server restart still ends it. */
    val onlineOpenEnded: Boolean = false,
    /** Why the last session ended: STOPPED | SIGNED_OUT | SUSPENDED | PROVIDER_BALANCE | WINDOW_ENDED | RESTART. */
    val offlineReason: String? = null,
)

@Serializable
data class RampMerchantsResponse(val merchants: List<RampMerchantDto>, val nextCursor: String? = null)

/**
 * One network's ramp settings. `state`: OFF | PREVIEW | ON. The three limits are KES minor units (cents) as strings.
 * `feeBps` is what a buyer pays (0 to 500), `merchantShareBps` the merchant's part of it (0 to 10000).
 * `feeSettleHourUtc` is the hour of the daily sweep of Circaso's share. `collectionsProvider` is who sends the M-Pesa
 * prompts, one of `collectionsProviders` (read-only: what this server offers). `providerFeeFloorMinor` is the least a
 * merchant's prepaid service balance at the provider must hold for them to go online (KES cents as a string; `"0"`
 * switches the check off). `version` rises on each change; sent back on an update, a change made meanwhile answers
 * `ramp.settings_changed`. `walletSetId`, `updatedBy` and `updatedAt` are read-only too.
 *
 * Identity: `kycRequired` (never false on MAINNET), `kycProvider` (one of `kycProviders`, read-only: what this server
 * offers), `kycEnvironment` (SANDBOX | LIVE: which side of the provider this network's checks run in and whose
 * approvals it trusts), `kycWorkflowUser` and `kycWorkflowMerchant` (the provider's workflow ids; empty: that kind
 * can't be verified here yet), `merchantPortalUrl` (where the provider's hosted page sends a merchant back to),
 * `kycAttemptsPerDay` (1 to 20) and `kycPendingHours` (1 to 672: how long a started check may stay unfinished).
 */
@Serializable
data class RampSettingsDto(
    val network: String,
    val state: String,
    val chains: List<String>,
    val orderMinMinor: String,
    val orderMaxMinor: String,
    val dailyLimitMinor: String,
    val promptsPerHour: Int,
    val feeBps: Int,
    val merchantShareBps: Int,
    val feeSettleHourUtc: Int,
    val quoteSeconds: Int,
    val paymentWindowSeconds: Int,
    val kycRequired: Boolean,
    val callbackBaseUrl: String,
    val collectionsProvider: String,
    val providerFeeFloorMinor: String,
    val version: Long? = null,
    val walletSetId: String? = null,
    val updatedBy: String? = null,
    val updatedAt: String? = null,
    val collectionsProviders: List<String> = emptyList(),
    val kycProvider: String = "",
    val kycEnvironment: String = "",
    val kycWorkflowUser: String = "",
    val kycWorkflowMerchant: String = "",
    val merchantPortalUrl: String = "",
    val kycAttemptsPerDay: Int = 0,
    val kycPendingHours: Int = 0,
    val kycProviders: List<String> = emptyList(),
    /**
     * Selling (2026-11-23): `offRampState` (OFF | PREVIEW | ON, its own switch; PREVIEW uses the same list),
     * `offRampMinMinor`, `offRampMaxMinor`, `offRampDailyLimitMinor` (KES cents the user receives),
     * `transferWindowSeconds` (how long a sale waits for the user's USDC, 60 to 3600), `payoutWindowSeconds` (how long a
     * payout nothing went out for is retried with the merchant before the USDC is refunded, 60 to 86400),
     * `payoutFeeBands` (the fallback payout fee table, used only when the provider's published one can't be read;
     * empty: none, and a sale whose fee can't be found isn't quoted) and `payoutBalanceLowMinor` (below it a
     * merchant's portal flags `payout_balance_low`; 0: never).
     */
    val offRampState: String = "OFF",
    val offRampMinMinor: String = "0",
    val offRampMaxMinor: String = "0",
    val offRampDailyLimitMinor: String = "0",
    val transferWindowSeconds: Int = 0,
    val payoutWindowSeconds: Int = 0,
    val payoutFeeBands: List<RampPayoutFeeBandDto> = emptyList(),
    val payoutBalanceLowMinor: String = "0",
    /**
     * Float withdrawals (2026-11-24): hours before a merchant's new withdrawal address can be used (0 to 720), the
     * smallest withdrawal and the daily cap per merchant ("0": none), both USDC minor units (6 decimals).
     */
    val withdrawDestinationDelayHours: Int = 24,
    val withdrawMinMinor: String = "1000000",
    val withdrawDailyMaxMinor: String = "0",
    /**
     * Days an open-ended merchant session survives server restarts and deploys without the merchant's PIN (1 to 90,
     * default 30): how long the server keeps its encrypted working copy of their unlocked payment account.
     */
    val onlineSessionMaxDays: Int = 30,
    /**
     * The fee by amount (2026-11-24, "the band sets the rate"): buying's bands by the KES paid, selling's by the KES
     * value of the USDC sold. The band an order falls in sets its rate for the whole amount. Always at least one band;
     * `feeBps` is buying's first band.
     */
    val onRampFeeBands: List<RampFeeBandDto> = emptyList(),
    val offRampFeeBands: List<RampFeeBandDto> = emptyList(),
)

/**
 * One band of a ramp fee: from `fromMinor` up to (not including) `toMinor` KES cents (null: and up), `feeBps` of the
 * whole amount. From "0" upward with no gaps or overlaps, the last open-ended, each rate 0 to 500.
 */
@Serializable
data class RampFeeBandDto(val fromMinor: String, val toMinor: String? = null, val feeBps: Int)

/** One band of the fallback payout fee table: a payout up to `upToMinor` KES cents (inclusive) costs `feeMinor`. */
@Serializable
data class RampPayoutFeeBandDto(val upToMinor: String, val feeMinor: String)

/**
 * `PUT /ops/ramp/settings?network=`: the settings and why they change. Read-only fields are ignored when sent.
 * `providerFeeFloorMinor` and each identity setting (`kycProvider`, `kycEnvironment`, `kycWorkflowUser`,
 * `kycWorkflowMerchant`, `merchantPortalUrl`, `kycAttemptsPerDay`, `kycPendingHours`) left out keeps that one as it
 * is (a form from before the setting existed).
 */
@Serializable
data class UpdateRampSettingsRequest(
    val state: String,
    val chains: List<String>,
    val orderMinMinor: String,
    val orderMaxMinor: String,
    val dailyLimitMinor: String,
    val promptsPerHour: Int,
    val feeBps: Int,
    val merchantShareBps: Int,
    val feeSettleHourUtc: Int,
    val quoteSeconds: Int,
    val paymentWindowSeconds: Int,
    val kycRequired: Boolean,
    val callbackBaseUrl: String,
    val collectionsProvider: String,
    val providerFeeFloorMinor: String? = null,
    val version: Long? = null,
    val reason: String = "",
    val network: String? = null,
    val walletSetId: String? = null,
    val updatedBy: String? = null,
    val updatedAt: String? = null,
    val collectionsProviders: List<String>? = null,
    val kycProvider: String? = null,
    val kycEnvironment: String? = null,
    val kycWorkflowUser: String? = null,
    val kycWorkflowMerchant: String? = null,
    val merchantPortalUrl: String? = null,
    val kycAttemptsPerDay: Int? = null,
    val kycPendingHours: Int? = null,
    val kycProviders: List<String>? = null,
    // Selling (2026-11-23): each one left out keeps its current value.
    val offRampState: String? = null,
    val offRampMinMinor: String? = null,
    val offRampMaxMinor: String? = null,
    val offRampDailyLimitMinor: String? = null,
    val transferWindowSeconds: Int? = null,
    val payoutWindowSeconds: Int? = null,
    val payoutFeeBands: List<RampPayoutFeeBandDto>? = null,
    val payoutBalanceLowMinor: String? = null,
    val withdrawDestinationDelayHours: Int? = null,
    val withdrawMinMinor: String? = null,
    val withdrawDailyMaxMinor: String? = null,
    val onlineSessionMaxDays: Int? = null,
    /**
     * Fee bands (2026-11-24); each left out keeps what is set, unless `feeBps` changed (an older form): then both
     * directions become one band at it.
     */
    val onRampFeeBands: List<RampFeeBandDto>? = null,
    val offRampFeeBands: List<RampFeeBandDto>? = null,
)

/**
 * `POST /ops/ramp/orders/{orderId}/resolve` (RO4). `resolution`: `MARK_PAID` (with `receipt`, the M-Pesa receipt you
 * saw), `REFUND` or `RECHECK`. `reason` is required and audited.
 */
@Serializable
data class RampResolveRequest(val resolution: String = "", val receipt: String? = null, val reason: String = "")

/** RO4's answer: the order as it stands now, and what the provider said (`queued` for a re-check). */
@Serializable
data class RampResolveResponse(val order: RampOrderDto, val providerSaid: String? = null)

@Serializable
data class RampPreviewAccessDto(
    val userId: String,
    val network: String,
    val grantedBy: String? = null,
    val at: String,
)

@Serializable
data class RampPreviewAccessResponse(val network: String, val people: List<RampPreviewAccessDto>)

/** `POST /ops/ramp/preview-access`. */
@Serializable
data class RampPreviewAccessRequest(
    val userId: String,
    val network: String,
    val enabled: Boolean,
    val reason: String = "",
)

/** A ramp order waiting for a person. Amounts are minor units as strings; no payer number is ever shown. */
@Serializable
data class RampOrderDto(
    val orderId: String,
    val network: String,
    val userId: String,
    val merchantId: String,
    val merchantName: String,
    val state: String,
    val blockchain: String,
    val fiatCurrency: String,
    val fiatAmountMinor: String,
    val assetAmountMinor: String,
    val feeMinor: String,
    val failureReason: String? = null,
    val custodyTxId: String? = null,
    val createdAt: String,
    val updatedAt: String,
    /** `ON_RAMP` or `OFF_RAMP` (a sale; 2026-11-23). */
    val direction: String = "ON_RAMP",
    /** A sale: the provider's payout fee the user paid, what the merchant's float received, and the payouts. */
    val providerFeeMinor: String = "0",
    val fundedMinor: String? = null,
    val payoutAttempts: Int = 0,
    /** A sale's latest payout: `SENDING`, `ACCEPTED`, `UNKNOWN`, `NOT_SENT:…`, `FAILED:…` or `PAID`. */
    val payoutOutcome: String? = null,
    val hasReceipt: Boolean = false,
    /** The rate (KES per USDC) and how it was made: `REFERENCE_SPREAD` or `MANUAL`, the reference then, the margin. */
    val rate: String? = null,
    val rateSource: String? = null,
    val referenceRate: String? = null,
    val spreadBps: Int? = null,
)

@Serializable
data class RampOrdersResponse(val orders: List<RampOrderDto>, val nextCursor: String? = null)

/** An amount of the ramp's: `unit` is `KES` or `USDC`, `amountMinor` exact, `amount` for display. */
@Serializable
data class RampAmountDto(val unit: String, val amountMinor: String, val amount: String)

/**
 * A network's totals for a period: the sums of the merchant rows. `orders` is all of them, `completed` those that
 * finished; `fees` is inside `collected`, `merchantShare` and `circasoShare` add up to `fees`, and `swept` and `held`
 * add up to `circasoShareUsdc`.
 */
@Serializable
data class RampOverviewTotalsDto(
    val orders: Int,
    val completed: Int,
    val failed: Int,
    val inReview: Int,
    val collected: RampAmountDto,
    val sold: RampAmountDto,
    val fees: RampAmountDto,
    val merchantShare: RampAmountDto,
    val circasoShare: RampAmountDto,
    val circasoShareUsdc: RampAmountDto,
    val swept: RampAmountDto,
    val held: RampAmountDto,
    /** Selling (2026-11-23): shillings paid out to sellers, the USDC received for them, and sales refunded. */
    val paidOut: RampAmountDto? = null,
    val bought: RampAmountDto? = null,
    val refunded: Int = 0,
)

/** One merchant's row. `online` is the trading window; `successRatePercent` is completed out of all orders. */
@Serializable
data class RampOverviewMerchantDto(
    val merchantId: String,
    val displayName: String,
    val state: String,
    val online: Boolean,
    val orders: Int,
    val completed: Int,
    val successRatePercent: String,
    val collected: RampAmountDto,
    val sold: RampAmountDto,
    val fees: RampAmountDto,
    val merchantShare: RampAmountDto,
    val circasoShare: RampAmountDto,
    val circasoShareUsdc: RampAmountDto,
    val held: RampAmountDto,
    val lastOrderAt: String? = null,
    val paidOut: RampAmountDto? = null,
    val bought: RampAmountDto? = null,
    /** The merchant's three wallets at their provider as last read (2026-11-24), buying's first. */
    val providerWallets: List<RampProviderWalletDto> = emptyList(),
)

/**
 * One of a merchant's three wallets at their provider as last read (2026-11-24; the ops server holds nobody's keys, so
 * this is the app's latest reading of it). `wallet`: `PAYHERO_SERVICE` (PayHero's service wallet: buying's M-Pesa
 * prompt fees), `FINOQ_SERVICE` (FinoQ's service wallet: selling's payout fees), `FINOQ_PAYMENT` (FinoQ's payment
 * wallet: what payouts spend); `name` is what to call it, `provider` `PAYHERO` | `FINOQ`, `direction` `ON_RAMP` |
 * `OFF_RAMP`. **`balance` null is "never read", never zero**; `readAt` is when this wallet was read. `low`: under the
 * network's line, or the provider refused a prompt or payout for it.
 */
@Serializable
data class RampProviderWalletDto(
    val wallet: String,
    val name: String,
    val provider: String,
    val direction: String,
    val balance: RampAmountDto? = null,
    val source: String? = null,
    val readAt: String? = null,
    val low: Boolean = false,
)

/** `GET /ops/ramp/overview`. `from` and `to` are Nairobi calendar days, inclusive. */
@Serializable
data class RampOverviewResponse(
    val network: String,
    val from: String,
    val to: String,
    val totals: RampOverviewTotalsDto,
    val merchants: List<RampOverviewMerchantDto>,
)

/** Something at the merchant's payment provider only they can fix. `code`: `SERVICE_BALANCE`. */
@Serializable
data class RampPaymentsIssueDto(val code: String, val since: String? = null)

/** The merchant's account. `collectionsProvider` is where their stored payment account is, null when none is. */
@Serializable
data class RampSummaryMerchantDto(
    val merchantId: String,
    val displayName: String,
    val email: String,
    val contactPhone: String,
    val state: String,
    val createdAt: String,
    val approvedAt: String? = null,
    val online: Boolean,
    val onlineUntil: String? = null,
    val paymentsConnected: Boolean,
    val collectionsProvider: String? = null,
    val paymentsIssue: RampPaymentsIssueDto? = null,
    /** Their identity verification in the environment this network trusts; null when they never started. */
    val kyc: RampVerificationDto? = null,
    /** Selling: whether they offer it, their FinoQ account id, and a payout refusal they must fix. */
    val offersOffRamp: Boolean = false,
    val payoutAccountId: String? = null,
    val payoutIssue: RampPaymentsIssueDto? = null,
    /** Online until the merchant stops it (no `onlineUntil`); a server restart still ends it. */
    val onlineOpenEnded: Boolean = false,
    /** Why the last session ended: STOPPED | SIGNED_OUT | SUSPENDED | PROVIDER_BALANCE | WINDOW_ENDED | RESTART. */
    val offlineReason: String? = null,
)

/**
 * A merchant's KES at the payout provider as last read (the ops server holds nobody's keys, so this is the app's
 * latest reading, from `readAt`). `payout` is FinoQ's payment wallet (what payouts spend), `fees` FinoQ's service
 * wallet (a payout's fee): **null is "couldn't be read", never zero**. `held` is what their open sales hold of
 * `payout`. `wallets` has all three wallets with their names and read times, PayHero's service wallet (buying's
 * prompt fees) too (2026-11-24).
 */
@Serializable
data class RampKesBalancesDto(
    val payout: RampAmountDto? = null,
    val payoutSource: String? = null,
    val fees: RampAmountDto? = null,
    val feesSource: String? = null,
    val readAt: String? = null,
    val held: RampAmountDto,
    val wallets: List<RampProviderWalletDto> = emptyList(),
)

@Serializable
data class RampSummaryWalletDto(val blockchain: String, val address: String)

/** The float wallet's USDC: `free` is `balance` less what open orders hold and what is held for Circaso. */
@Serializable
data class RampFloatDto(
    val balance: RampAmountDto,
    val reserved: RampAmountDto,
    val owedToCircaso: RampAmountDto,
    val free: RampAmountDto,
)

/** `completed` are counted on the day they completed, the others on the day they were made; `total` is all. */
@Serializable
data class RampOrderCountsDto(
    val total: Int,
    val completed: Int,
    val failed: Int,
    val expired: Int,
    val cancelled: Int,
    val inReview: Int,
    val open: Int,
    /** Sales whose USDC went back to the user. */
    val refunded: Int = 0,
)

@Serializable
data class RampDayDto(
    val day: String,
    val orders: Int,
    val completed: Int,
    val collected: RampAmountDto,
    val sold: RampAmountDto,
    val fees: RampAmountDto,
    val merchantShare: RampAmountDto,
    val circasoShare: RampAmountDto,
)

/** One daily sweep of Circaso's share. `state`: PENDING | CONFIRMED | FAILED. */
@Serializable
data class RampSweepDto(
    val settlementId: String,
    val day: String,
    val amount: RampAmountDto,
    val state: String,
    val orders: Int,
    val txHash: String? = null,
)

/**
 * `GET /ops/ramp/merchants/{merchantId}/summary`. `wallet` is null until the ops server has made it on this network;
 * `float` is null without a wallet or when the custodian can't be read. `daily` has one row per Nairobi day with
 * activity, oldest first. The money is what the merchant's own earnings page shows for the same period.
 */
@Serializable
data class RampMerchantSummaryResponse(
    val merchant: RampSummaryMerchantDto,
    val network: String,
    val from: String,
    val to: String,
    val wallet: RampSummaryWalletDto? = null,
    val float: RampFloatDto? = null,
    val orders: RampOrderCountsDto,
    val successRatePercent: String,
    val collected: RampAmountDto,
    val sold: RampAmountDto,
    val fees: RampAmountDto,
    val merchantShare: RampAmountDto,
    val circasoShare: RampAmountDto,
    val circasoShareUsdc: RampAmountDto,
    val swept: RampAmountDto,
    val held: RampAmountDto,
    val daily: List<RampDayDto>,
    val sweeps: List<RampSweepDto>,
    /** Selling: the shillings paid out to sellers, and the USDC received for them. */
    val paidOut: RampAmountDto? = null,
    val bought: RampAmountDto? = null,
    val kesBalances: RampKesBalancesDto? = null,
)

/**
 * One of a merchant's orders. The payer's number is masked. `released` is the USDC the order is for (it reached the
 * buyer only when `state` is COMPLETED). `completedAt`, the three shares and `settlementId` are null until the order
 * completes; `settlementId` stays null until the sweep that took Circaso's share is confirmed.
 */
@Serializable
data class RampMerchantOrderDto(
    val orderId: String,
    val createdAt: String,
    val completedAt: String? = null,
    val state: String,
    val direction: String,
    val blockchain: String,
    val maskedPhone: String,
    val paid: RampAmountDto,
    val released: RampAmountDto,
    val rate: String,
    val fee: RampAmountDto,
    val merchantShare: RampAmountDto? = null,
    val circasoShare: RampAmountDto? = null,
    val circasoShareUsdc: RampAmountDto? = null,
    val failureReason: String? = null,
    val settlementId: String? = null,
    val txHash: String? = null,
    val collectionsProvider: String,
    /** How `rate` was made: `REFERENCE_SPREAD` or `MANUAL`, the reference then, the margin (null before 2026-11-24). */
    val rateSource: String? = null,
    val referenceRate: String? = null,
    val spreadBps: Int? = null,
)

@Serializable
data class RampMerchantOrdersResponse(val orders: List<RampMerchantOrderDto>, val nextCursor: String? = null)

/** A float withdrawal for staff (2026-11-24); amounts as everywhere: `{ unit, amountMinor, amount }`. */
@Serializable
data class RampWithdrawalDto(
    val withdrawalId: String,
    val state: String,
    val amount: RampAmountDto,
    val blockchain: String,
    val destinationAddress: String,
    val destinationLabel: String,
    val txHash: String?,
    val explorerUrl: String?,
    val failureReason: String?,
    val createdAt: String,
    val updatedAt: String,
    val closedAt: String?,
)

@Serializable
data class RampWithdrawalDestinationDto(
    val destinationId: String,
    val label: String,
    val address: String,
    val blockchain: String,
    val createdAt: String,
    val usableAt: String,
    val removedAt: String?,
    /** Frozen by the merchant's "This wasn't me" lock while cooling off (2026-11-24). */
    val frozenAt: String? = null,
)

@Serializable
data class RampMerchantWithdrawalsResponse(
    val withdrawals: List<RampWithdrawalDto>,
    val destinations: List<RampWithdrawalDestinationDto>,
)
