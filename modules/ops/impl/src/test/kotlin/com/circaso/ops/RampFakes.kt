package com.circaso.ops

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsMerchant
import com.circaso.ramp.api.OpsMerchantWallet
import com.circaso.ramp.api.OpsRampBlock
import com.circaso.ramp.api.OpsRampBlockCursor
import com.circaso.ramp.api.OpsRampDay
import com.circaso.ramp.api.OpsRampFloat
import com.circaso.ramp.api.OpsRampKesBalances
import com.circaso.ramp.api.OpsRampMerchantActivity
import com.circaso.ramp.api.OpsRampMerchantOrder
import com.circaso.ramp.api.OpsRampMerchantProfile
import com.circaso.ramp.api.OpsRampMerchantSummary
import com.circaso.ramp.api.OpsRampMoney
import com.circaso.ramp.api.OpsRampOrder
import com.circaso.ramp.api.OpsRampOrderCounts
import com.circaso.ramp.api.OpsRampOrderCursor
import com.circaso.ramp.api.OpsRampOverview
import com.circaso.ramp.api.OpsRampPaymentsIssue
import com.circaso.ramp.api.OpsRampPeriod
import com.circaso.ramp.api.OpsRampPreviewAccess
import com.circaso.ramp.api.OpsRampProviderWallet
import com.circaso.ramp.api.OpsRampResolved
import com.circaso.ramp.api.OpsRampSettings
import com.circaso.ramp.api.OpsRampSettingsChange
import com.circaso.ramp.api.OpsRampSweep
import com.circaso.ramp.api.OpsRampUserVerification
import com.circaso.ramp.api.OpsRampVerification
import com.circaso.ramp.api.RampAdmin
import com.circaso.ramp.api.RampAmount
import com.circaso.ramp.api.RampOrderId
import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
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

    val orders = CopyOnWriteArrayList<OpsRampMerchantOrder>()
    val asked = CopyOnWriteArrayList<Asked>()

    /** True: the float is read; false: the custodian is down; null: the merchant has no wallet on the network. */
    @Volatile
    var floatReadable: Boolean? = true

    @Volatile
    var issue: OpsRampPaymentsIssue? = null

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

    val verifications = ConcurrentHashMap<UserId, List<OpsRampVerification>>()
    val merchantVerifications = ConcurrentHashMap<MerchantId, List<OpsRampVerification>>()
    val blocked = CopyOnWriteArrayList<OpsRampBlock>()
    val resets = CopyOnWriteArrayList<Triple<String, String, UUID>>()

    override suspend fun userVerification(userId: UserId) =
        OpsRampUserVerification(userId, verifications[userId].orEmpty(), blocked.firstOrNull { it.userId == userId })

    override suspend fun blocks(after: OpsRampBlockCursor?, limit: Int): List<OpsRampBlock> =
        blocked
            .sortedByDescending { it.createdAt }
            .filter { after == null || it.createdAt < after.at }
            .take(limit)

    override suspend fun block(
        userId: UserId,
        note: String,
        by: UUID,
    ): Resource<OpsRampBlock> {
        refused()?.let { return it }
        val existing = blocked.firstOrNull { it.userId == userId }
        if (existing != null) return Resource.success(existing)
        val at = AT.plusSeconds(blocked.size.toLong())
        val block = OpsRampBlock(UUID.randomUUID(), userId, "OPS", note, by.toString(), at)
        blocked += block
        return Resource.success(block)
    }

    override suspend fun unblock(userId: UserId, by: UUID): Boolean = blocked.removeIf { it.userId == userId }

    private fun reset(
        who: String,
        environment: String,
        by: UUID,
    ): Resource<Unit> {
        refused()?.let { return it }
        if (environment.uppercase() !in listOf("SANDBOX", "LIVE")) {
            return Resource.Error(RampRefusal("request.malformed", ErrorKind.INVALID))
        }
        resets += Triple(who, environment.uppercase(), by)
        return Resource.success(Unit)
    }

    override suspend fun resetUserVerification(
        userId: UserId,
        environment: String,
        by: UUID,
    ): Resource<Boolean> =
        when (val checked = reset(userId.toString(), environment, by)) {
            is Resource.Error -> checked
            is Resource.Success -> Resource.success(verifications.remove(userId) != null)
        }

    override suspend fun resetMerchantVerification(
        merchantId: MerchantId,
        environment: String,
        by: UUID,
    ): Resource<Boolean> {
        if (!merchants.containsKey(
                merchantId,
            )
        ) {
            return Resource.Error(RampRefusal("ramp.merchant_not_found", ErrorKind.NOT_FOUND))
        }
        return when (val checked = reset(merchantId.toString(), environment, by)) {
            is Resource.Error -> checked
            is Resource.Success -> Resource.success(merchantVerifications.remove(merchantId) != null)
        }
    }

    override suspend fun merchants(before: MerchantId?, limit: Int): List<OpsMerchant> =
        merchants.values.sortedByDescending { it.merchantId.value }.take(limit)

    /** What staff asked of each order (RO4): the outcome and the receipt. */
    val resolutions = CopyOnWriteArrayList<Triple<RampOrderId, String, String?>>()

    /** An order in review that RO4 resolves: it answers [resolvedState] (and `queued` for a re-check). */
    override suspend fun resolve(
        orderId: RampOrderId,
        resolution: String,
        receipt: String?,
        by: UUID,
    ): Resource<OpsRampResolved> {
        refused()?.let { return it }
        val order =
            review.firstOrNull { it.orderId == orderId }
                ?: return Resource.Error(RampRefusal("ramp.order_not_found", ErrorKind.NOT_FOUND))
        resolutions += Triple(orderId, resolution, receipt)
        val state = if (resolution == "RECHECK") order.state else resolvedState
        return Resource.success(OpsRampResolved(order.copy(state = state), "queued".takeIf { resolution == "RECHECK" }))
    }

    var resolvedState = "COMPLETED"

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
                providerFeeFloorMinor = settings.providerFeeFloorMinor ?: before.providerFeeFloorMinor,
                kycProvider = settings.kycProvider ?: before.kycProvider,
                kycEnvironment = settings.kycEnvironment ?: before.kycEnvironment,
                kycWorkflowUser = settings.kycWorkflowUser ?: before.kycWorkflowUser,
                kycWorkflowMerchant = settings.kycWorkflowMerchant ?: before.kycWorkflowMerchant,
                merchantPortalUrl = settings.merchantPortalUrl ?: before.merchantPortalUrl,
                kycAttemptsPerDay = settings.kycAttemptsPerDay ?: before.kycAttemptsPerDay,
                kycPendingHours = settings.kycPendingHours ?: before.kycPendingHours,
                kycProviders = listOf("didit"),
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

    override suspend fun overview(network: Network, period: OpsRampPeriod?): OpsRampOverview {
        asked += Asked(null, network, period)
        val rows =
            merchants.values.sortedBy { it.merchantId.value }.map {
                OpsRampMerchantActivity(
                    merchantId = it.merchantId,
                    displayName = it.displayName,
                    state = it.state,
                    online = true,
                    orders = COUNTS,
                    successRatePercent = "95.2",
                    money = MONEY,
                    lastOrderAt = AT,
                    providerWallets = WALLETS,
                )
            }
        return OpsRampOverview(network, period ?: MONTH, COUNTS, MONEY, rows)
    }

    override suspend fun merchantSummary(
        merchantId: MerchantId,
        network: Network,
        period: OpsRampPeriod?,
    ): Resource<OpsRampMerchantSummary> {
        asked += Asked(merchantId, network, period)
        val merchant =
            merchants[merchantId] ?: return Resource.Error(RampRefusal("ramp.merchant_not_found", ErrorKind.NOT_FOUND))
        return Resource.success(
            OpsRampMerchantSummary(
                merchant =
                    OpsRampMerchantProfile(
                        merchantId = merchantId,
                        displayName = merchant.displayName,
                        email = merchant.email,
                        contactPhone = merchant.contactPhone,
                        state = merchant.state,
                        createdAt = merchant.createdAt,
                        approvedAt = AT,
                        online = true,
                        onlineUntil = AT.plusSeconds(3_600),
                        paymentsConnected = true,
                        collectionsProvider = "payhero",
                        paymentsIssue = issue,
                        kyc = merchant.verifications.firstOrNull(),
                    ),
                network = network,
                period = period ?: MONTH,
                wallet = OpsMerchantWallet(network, "BASE-SEPOLIA", "0xfloat").takeIf { floatReadable != null },
                float =
                    OpsRampFloat(usdc(100_000_000), usdc(9_800_000), usdc(120_000), usdc(90_080_000))
                        .takeIf { floatReadable == true },
                orders = COUNTS,
                successRatePercent = "95.2",
                money = MONEY,
                daily =
                    listOf(
                        OpsRampDay(
                            MONTH.from.plusDays(20),
                            9,
                            9,
                            kes(1_170_000),
                            usdc(88_200_000),
                            kes(23_400),
                            kes(16_380),
                            kes(7_020),
                        ),
                    ),
                sweeps = listOf(OpsRampSweep(SWEEP, MONTH.from.plusDays(20), usdc(540_000), "CONFIRMED", 9, "0xsweep")),
                kesBalances =
                    OpsRampKesBalances(
                        payout = null,
                        payoutSource = null,
                        fees = kes(700),
                        feesSource = "finoq.balance.service_wallet",
                        readAt = AT,
                        held = kes(0),
                        wallets = WALLETS,
                    ),
            ),
        )
    }

    override suspend fun merchantOrders(
        merchantId: MerchantId,
        network: Network,
        period: OpsRampPeriod?,
        state: String?,
        after: OpsRampOrderCursor?,
        limit: Int,
    ): Resource<List<OpsRampMerchantOrder>> {
        asked += Asked(merchantId, network, period, state, after, limit)
        if (!merchants.containsKey(
                merchantId,
            )
        ) {
            return Resource.Error(RampRefusal("ramp.merchant_not_found", ErrorKind.NOT_FOUND))
        }
        return Resource.success(orders.filter { state == null || it.state == state }.take(limit))
    }

    /** One of a merchant's orders as the ramp hands it over: the payer's number already masked. */
    fun order(state: String = "COMPLETED", at: Instant = AT): OpsRampMerchantOrder {
        val completed = state == "COMPLETED"
        return OpsRampMerchantOrder(
            orderId = RampOrderId.new(),
            createdAt = at,
            completedAt = at.plusSeconds(250).takeIf { completed },
            state = state,
            direction = "ON_RAMP",
            blockchain = "BASE-SEPOLIA",
            maskedPhone = "+254 7•• ••• 324",
            paid = kes(130_000),
            released = usdc(9_800_000),
            rate = "129.96",
            fee = kes(2_600),
            merchantShare = kes(1_820).takeIf { completed },
            circasoShare = kes(780).takeIf { completed },
            circasoShareUsdc = usdc(60_000).takeIf { completed },
            failureReason = "payment_failed".takeIf { state == "FAILED" },
            settlementId = SWEEP.takeIf { completed },
            txHash = "0xrelease".takeIf { completed },
            collectionsProvider = "payhero",
        ).also { orders += it }
    }

    /** What a read of the merchants' activity was asked with. */
    data class Asked(
        val merchantId: MerchantId?,
        val network: Network,
        val period: OpsRampPeriod?,
        val state: String? = null,
        val after: OpsRampOrderCursor? = null,
        val limit: Int? = null,
    )

    companion object {
        /** PayHero's read, FinoQ's service wallet low, FinoQ's payment wallet never read. */
        val WALLETS: List<OpsRampProviderWallet>
            get() =
                listOf(
                    OpsRampProviderWallet(
                        "PAYHERO_SERVICE",
                        "PayHero service wallet",
                        "PAYHERO",
                        "ON_RAMP",
                        RampAmount("KES", "31240", "312.4"),
                        "payhero.wallets.service_wallet",
                        AT,
                        false,
                    ),
                    OpsRampProviderWallet(
                        "FINOQ_SERVICE",
                        "FinoQ service wallet",
                        "FINOQ",
                        "OFF_RAMP",
                        RampAmount("KES", "700", "7"),
                        "finoq.balance.service_wallet",
                        AT,
                        true,
                    ),
                    OpsRampProviderWallet(
                        "FINOQ_PAYMENT",
                        "FinoQ payment wallet",
                        "FINOQ",
                        "OFF_RAMP",
                        null,
                        null,
                        null,
                        false,
                    ),
                )

        val AT: Instant = Instant.parse("2026-11-22T12:00:00Z")
        val MONTH = OpsRampPeriod(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"))
        val SWEEP: UUID = UUID.fromString("0193a000-0000-7000-8000-000000000001")
        val COUNTS =
            OpsRampOrderCounts(
                total = 42,
                completed = 40,
                failed = 1,
                expired = 0,
                cancelled = 0,
                inReview = 1,
                open = 0,
            )

        fun kes(minor: Long) = RampAmount("KES", minor.toString(), (minor / 100).toString())

        fun usdc(minor: Long) =
            RampAmount("USDC", minor.toString(), BigDecimal.valueOf(minor, 6).stripTrailingZeros().toPlainString())

        val MONEY =
            OpsRampMoney(
                collected = kes(5_460_000),
                sold = usdc(415_800_000),
                fees = kes(109_200),
                merchantShare = kes(76_440),
                circasoShare = kes(32_760),
                circasoShareUsdc = usdc(2_520_000),
                swept = usdc(2_400_000),
                held = usdc(120_000),
            )

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
                providerFeeFloorMinor = 5_000,
                version = 1,
                collectionsProviders = OFFERED,
                kycProvider = "didit",
                kycEnvironment = if (network == Network.TESTNET) "SANDBOX" else "LIVE",
                kycWorkflowUser = if (network == Network.TESTNET) "a3cb5782-d5c1-4c35-832d-ed373d80a0ea" else "",
                kycWorkflowMerchant = if (network == Network.TESTNET) "dabdb52a-b4bc-435a-b2bf-a636bb17bad6" else "",
                merchantPortalUrl = "https://merchants.circaso.com",
                kycAttemptsPerDay = 3,
                kycPendingHours = 24,
                kycProviders = listOf("didit"),
            )
    }
}
