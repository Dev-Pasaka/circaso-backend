package com.circaso.ops

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsMerchant
import com.circaso.ramp.api.OpsMerchantWallet
import com.circaso.ramp.api.OpsRampOrder
import com.circaso.ramp.api.OpsRampPreviewAccess
import com.circaso.ramp.api.OpsRampSettings
import com.circaso.ramp.api.OpsRampSettingsChange
import com.circaso.ramp.api.RampAdmin
import com.circaso.ramp.api.RampOrderId
import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private data class RampRefusal(override val code: String, override val kind: ErrorKind) : DomainError {
    override val message: String = code
}

/**
 * The ramp's staff contract in memory: it takes what it is given and says what it was given, so the ops routes' own
 * work (who may, the shapes, the reason, the audit line) is what a test sees. [refusal] makes the next write fail with
 * that code, as the ramp would.
 */
class FakeRampAdmin : RampAdmin {
    val merchants = ConcurrentHashMap<MerchantId, OpsMerchant>()
    val decisions = CopyOnWriteArrayList<Triple<MerchantId, Boolean, UUID>>()
    val saved = CopyOnWriteArrayList<OpsRampSettings>()
    val preview = CopyOnWriteArrayList<OpsRampPreviewAccess>()
    val review = CopyOnWriteArrayList<OpsRampOrder>()
    val reviewAsked = CopyOnWriteArrayList<Network?>()

    @Volatile
    var refusal: String? = null

    var current: Map<Network, OpsRampSettings> = Network.entries.associateWith { settingsOf(it) }

    private fun refused(): Resource.Error? = refusal?.let { Resource.Error(RampRefusal(it, ErrorKind.CONFLICT)) }

    fun add(state: String = "APPLIED"): MerchantId {
        val id = MerchantId.new()
        merchants[id] =
            OpsMerchant(
                merchantId = id,
                email = "pay@crynce.co.ke",
                displayName = "Crynce",
                contactPhone = "+254712000111",
                state = state,
                onlineUntil = null,
                paymentsConnected = true,
                wallets = listOf(OpsMerchantWallet(Network.TESTNET, "BASE-SEPOLIA", "0xfloat")),
                approvedBy = null,
                approvedAt = null,
                createdAt = AT,
            )
        return id
    }

    override suspend fun merchants(before: MerchantId?, limit: Int): List<OpsMerchant> =
        merchants.values.sortedByDescending { it.merchantId.value }.take(limit)

    private fun decide(
        merchantId: MerchantId,
        approve: Boolean,
        by: UUID,
    ): Resource<OpsMerchant> {
        refused()?.let { return it }
        val merchant =
            merchants[merchantId] ?: return Resource.Error(RampRefusal("ramp.merchant_not_found", ErrorKind.NOT_FOUND))
        decisions += Triple(merchantId, approve, by)
        val after = merchant.copy(state = if (approve) "APPROVED" else "SUSPENDED", approvedBy = by.toString())
        merchants[merchantId] = after
        return Resource.success(after)
    }

    override suspend fun approve(merchantId: MerchantId, by: UUID) = decide(merchantId, true, by)

    override suspend fun suspend(merchantId: MerchantId, by: UUID) = decide(merchantId, false, by)

    override suspend fun settings(network: Network): OpsRampSettings = current.getValue(network)

    override suspend fun updateSettings(settings: OpsRampSettings, by: UUID): Resource<OpsRampSettingsChange> {
        refused()?.let { return it }
        if (settings.collectionsProvider !in OFFERED) {
            return Resource.Error(RampRefusal("ramp.settings_invalid", ErrorKind.INVALID))
        }
        val before = current.getValue(settings.network)
        val after =
            settings.copy(
                version = (before.version ?: 0) + 1,
                updatedBy = by.toString(),
                collectionsProviders = OFFERED,
            )
        saved += settings
        current = current + (settings.network to after)
        return Resource.success(OpsRampSettingsChange(before, after))
    }

    override suspend fun previewAccess(network: Network): List<OpsRampPreviewAccess> =
        preview.filter {
            it.network ==
                network
        }

    override suspend fun setPreviewAccess(
        userId: UserId,
        network: Network,
        enabled: Boolean,
        by: UUID,
    ): Boolean =
        if (enabled) {
            preview.none { it.userId == userId && it.network == network } &&
                preview.add(OpsRampPreviewAccess(userId, network, by.toString(), AT))
        } else {
            preview.removeIf { it.userId == userId && it.network == network }
        }

    override suspend fun ordersInReview(
        network: Network?,
        after: RampOrderId?,
        limit: Int,
    ): List<OpsRampOrder> {
        reviewAsked += network
        return review.filter { network == null || it.network == network }.take(limit)
    }

    companion object {
        val AT: Instant = Instant.parse("2026-11-22T12:00:00Z")

        /** What this pretend server offers: PayHero, and a second real provider to switch to. */
        val OFFERED = listOf("payhero", "daraja")

        fun settingsOf(network: Network) =
            OpsRampSettings(
                network = network,
                state = "OFF",
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
                collectionsProvider = "payhero",
                version = 1,
                collectionsProviders = OFFERED,
            )
    }
}
