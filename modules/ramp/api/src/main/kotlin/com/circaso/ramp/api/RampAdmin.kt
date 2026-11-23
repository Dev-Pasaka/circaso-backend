package com.circaso.ramp.api

import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

// The ramp's staff contract (decision 2026-11-22), for the ops module behind the ops gateway only: partner approval,
// each network's ramp settings (which live in the database, not the environment), who may trade while the ramp is in
// PREVIEW, and the orders waiting for a person. Nothing here ever carries a merchant's credential or a payer's number.

/** A merchant's float wallet on one network. */
data class OpsMerchantWallet(val network: Network, val blockchain: String, val address: String)

/**
 * A subject's identity verification in one provider environment ([environment]: `SANDBOX` or `LIVE`; a network's
 * settings name the one it trusts). [status]: `PENDING`, `IN_REVIEW`, `VERIFIED`, `DECLINED` or `EXPIRED`; someone who
 * never started has none. [reason] is the ramp's own code (`document`, `identity_in_use`, `blocked`, …).
 * [hasIdentity] says an identity key is held (a live approval); the key itself, like the document, never leaves the ramp.
 */
data class OpsRampVerification(
    val environment: String,
    val status: String,
    val reason: String?,
    val provider: String,
    val attempts: Int,
    val hasIdentity: Boolean,
    val startedAt: Instant?,
    val verifiedAt: Instant?,
    val updatedAt: Instant,
)

/** A user barred from the ramp. [reason]: `OPS`, `REVERSAL`, or `IDENTITY` (they verified as a barred identity). */
data class OpsRampBlock(
    val blockId: UUID,
    val userId: UserId,
    val reason: String,
    val note: String,
    val createdBy: String,
    val createdAt: Instant,
)

/** Where a page of blocks continues: after the block made at [at] with [blockId]. */
data class OpsRampBlockCursor(val at: Instant, val blockId: UUID)

/** A user's ramp verification in each environment they have one, and their block when they are barred. */
data class OpsRampUserVerification(
    val userId: UserId,
    val verifications: List<OpsRampVerification>,
    val block: OpsRampBlock?,
)

/**
 * A partner merchant as staff see it. [state] is `NEW`, `APPLIED`, `APPROVED` or `SUSPENDED`. [paymentsConnected] says
 * whether the merchant has stored a payment account; what it is stays sealed. [verifications] is their identity
 * verification in each provider environment they have one.
 */
data class OpsMerchant(
    val merchantId: MerchantId,
    val email: String,
    val displayName: String,
    val contactPhone: String,
    val state: String,
    val onlineUntil: Instant?,
    val paymentsConnected: Boolean,
    val wallets: List<OpsMerchantWallet>,
    val approvedBy: String?,
    val approvedAt: Instant?,
    val createdAt: Instant,
    val verifications: List<OpsRampVerification> = emptyList(),
) {
    override fun toString(): String = "OpsMerchant($merchantId, $state)"
}

/**
 * One network's ramp settings. [state] is `OFF`, `PREVIEW` or `ON`. Amounts are KES minor units. [version] rises on
 * each change: sent back with an update, a change made meanwhile is refused instead of overwritten (null: save over
 * whatever is there). [walletSetId], [updatedBy], [updatedAt] and [collectionsProviders] are read-only.
 */
data class OpsRampSettings(
    val network: Network,
    val state: String,
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
    /** Which collections provider sends this network's M-Pesa prompts: one of [collectionsProviders]. */
    val collectionsProvider: String,
    /**
     * The least a merchant's prepaid service balance at the provider must hold for them to go online, in KES minor
     * units; 0 switches the check off. Always set on a read; null on an update leaves it as it is.
     */
    val providerFeeFloorMinor: Long? = null,
    val version: Long? = null,
    val walletSetId: String? = null,
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
    /** Read-only: the providers this server offers, to choose [collectionsProvider] from. */
    val collectionsProviders: List<String> = emptyList(),
    // The identity settings. Always set on a read; null on an update leaves that one as it is.
    /** Who runs the identity checks: one of [kycProviders]. */
    val kycProvider: String? = null,
    /** `SANDBOX` or `LIVE`: which side of the provider this network's checks run in, and whose approvals it trusts. */
    val kycEnvironment: String? = null,
    /** The provider's workflow for a user; empty: users can't be verified on this network yet. */
    val kycWorkflowUser: String? = null,
    /** The provider's workflow for a merchant; empty: merchants can't be verified on this network yet. */
    val kycWorkflowMerchant: String? = null,
    /** The merchant portal's origin: where the provider's hosted page sends a merchant back to. */
    val merchantPortalUrl: String? = null,
    /** How many checks one person may start in a day (1 to 20). */
    val kycAttemptsPerDay: Int? = null,
    /** Hours a started check may stay unfinished before it is marked expired (1 to 672). */
    val kycPendingHours: Int? = null,
    /** Read-only: the KYC providers this server offers, to choose [kycProvider] from. */
    val kycProviders: List<String> = emptyList(),
)

data class OpsRampSettingsChange(val before: OpsRampSettings, val after: OpsRampSettings)

/** Someone who may trade on [network] while its ramp is in `PREVIEW`. */
data class OpsRampPreviewAccess(
    val userId: UserId,
    val network: Network,
    val grantedBy: String?,
    val at: Instant,
)

/** An order as staff see it: no payer number, no receipt. Amounts are minor units. */
data class OpsRampOrder(
    val orderId: RampOrderId,
    val network: Network,
    val userId: UserId,
    val merchantId: MerchantId,
    val merchantName: String,
    val state: String,
    val blockchain: String,
    val fiatCurrency: String,
    val fiatAmountMinor: Long,
    val assetAmountMinor: Long,
    val feeMinor: Long,
    val failureReason: String?,
    val custodyTxId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String = "OpsRampOrder($orderId, $state)"
}

/**
 * Staff's view of the ramp. Errors: `ramp.merchant_not_found` (404), `ramp.merchant_not_ready` (409: approving a
 * merchant who hasn't applied), `ramp.merchant_kyc_required` (409: approving one whose identity isn't verified),
 * `ramp.settings_invalid` (400, the field named in the details) and `ramp.settings_changed` (409).
 */
interface RampAdmin {
    /** Merchants, newest first; [before] is the last id of the previous page. */
    suspend fun merchants(before: MerchantId?, limit: Int): List<OpsMerchant>

    /**
     * Partner approval: an `APPLIED` or `SUSPENDED` merchant becomes `APPROVED`. Approving twice changes nothing.
     * Refused with `ramp.merchant_kyc_required` while the merchant's identity isn't verified on a network whose ramp
     * is open and requires KYC.
     */
    suspend fun approve(merchantId: MerchantId, by: UUID): Resource<OpsMerchant>

    /** Suspends the merchant and takes them offline at once; their open orders still finish. */
    suspend fun suspend(merchantId: MerchantId, by: UUID): Resource<OpsMerchant>

    suspend fun settings(network: Network): OpsRampSettings

    /**
     * Replaces one network's settings. A chain must belong to the network; the fee is 0 to 500 basis points, the
     * merchant's share 0 to 10 000; KYC can't be switched off on mainnet; the collections provider must be one this
     * server offers. In force everywhere within ten seconds, with no restart.
     */
    suspend fun updateSettings(settings: OpsRampSettings, by: UUID): Resource<OpsRampSettingsChange>

    suspend fun previewAccess(network: Network): List<OpsRampPreviewAccess>

    /** Lets [userId] trade on [network] while its ramp is in `PREVIEW`, or takes that away; true when it changed. */
    suspend fun setPreviewAccess(
        userId: UserId,
        network: Network,
        enabled: Boolean,
        by: UUID,
    ): Boolean

    /** Orders in `REVIEW` on [network] (both when null), oldest first; [after] is the last id of the previous page. */
    suspend fun ordersInReview(
        network: Network?,
        after: RampOrderId?,
        limit: Int,
    ): List<OpsRampOrder>

    /**
     * What every merchant did on [network] in [period] (this Nairobi month when null): the approved merchants, and
     * any other with an order in the period. The totals are the sum of the rows.
     */
    suspend fun overview(network: Network, period: OpsRampPeriod?): OpsRampOverview

    /**
     * One merchant on [network] in [period] (this Nairobi month when null), with the same figures their own earnings
     * overview shows. `ramp.merchant_not_found` when there is no such merchant.
     */
    suspend fun merchantSummary(
        merchantId: MerchantId,
        network: Network,
        period: OpsRampPeriod?,
    ): Resource<OpsRampMerchantSummary>

    /**
     * The merchant's orders on [network], newest first: made in [period] (any day when null), in [state] (any when
     * null; an [RampOrderStates] name). [after] is the last order of the previous page.
     */
    suspend fun merchantOrders(
        merchantId: MerchantId,
        network: Network,
        period: OpsRampPeriod?,
        state: String?,
        after: OpsRampOrderCursor?,
        limit: Int,
    ): Resource<List<OpsRampMerchantOrder>>

    /** A user's ramp verification in each provider environment, and their block. Someone unknown has neither. */
    suspend fun userVerification(userId: UserId): OpsRampUserVerification

    /** The users barred from the ramp, newest first; [after] is the last block of the previous page. */
    suspend fun blocks(after: OpsRampBlockCursor?, limit: Int): List<OpsRampBlock>

    /**
     * Bars [userId] from the ramp, and with them the identity they verified with, so the same person can't come back
     * on a new account. Barring someone already barred changes nothing and answers the block there is.
     */
    suspend fun block(
        userId: UserId,
        note: String,
        by: UUID,
    ): Resource<OpsRampBlock>

    /** Lifts the user's block and the identity blocks that came from it; true when there was one. */
    suspend fun unblock(userId: UserId, by: UUID): Boolean

    /**
     * Removes a user's verification in [environment] (`SANDBOX` or `LIVE`), freeing the identity it holds, so the
     * person can verify again on this account or another; true when there was one.
     */
    suspend fun resetUserVerification(
        userId: UserId,
        environment: String,
        by: UUID,
    ): Resource<Boolean>

    /** The same for a merchant. `ramp.merchant_not_found` when there is no such merchant. */
    suspend fun resetMerchantVerification(
        merchantId: MerchantId,
        environment: String,
        by: UUID,
    ): Resource<Boolean>
}

/** The provider environments a verification can be in, as [RampAdmin.resetUserVerification] takes them. */
object RampKycEnvironments {
    val ALL: List<String> = listOf("SANDBOX", "LIVE")
}

/** The states an order can be in, as [RampAdmin.merchantOrders] takes them. */
object RampOrderStates {
    val ALL: List<String> =
        listOf(
            "RESERVED",
            "PAYMENT_REQUESTED",
            "PAID",
            "RELEASING",
            "COMPLETED",
            "EXPIRED",
            "CANCELLED",
            "FAILED",
            "REVIEW",
        )
}
