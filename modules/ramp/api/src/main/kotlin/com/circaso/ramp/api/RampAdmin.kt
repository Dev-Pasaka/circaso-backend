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
 * A partner merchant as staff see it. [state] is `NEW`, `APPLIED`, `APPROVED` or `SUSPENDED`. [paymentsConnected] says
 * whether the merchant has stored a payment account; what it is stays sealed.
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
    val version: Long? = null,
    val walletSetId: String? = null,
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
    /** Read-only: the providers this server offers, to choose [collectionsProvider] from. */
    val collectionsProviders: List<String> = emptyList(),
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
 * merchant who hasn't applied), `ramp.settings_invalid` (400, the field named in the details) and
 * `ramp.settings_changed` (409).
 */
interface RampAdmin {
    /** Merchants, newest first; [before] is the last id of the previous page. */
    suspend fun merchants(before: MerchantId?, limit: Int): List<OpsMerchant>

    /** Partner approval: an `APPLIED` or `SUSPENDED` merchant becomes `APPROVED`. Approving twice changes nothing. */
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
}
