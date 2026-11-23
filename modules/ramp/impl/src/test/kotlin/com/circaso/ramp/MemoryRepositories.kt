package com.circaso.ramp

import com.circaso.integrations.api.KycEnvironment
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.QuoteId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.models.Block
import com.circaso.ramp.domain.models.Candidate
import com.circaso.ramp.domain.models.CommissionFigures
import com.circaso.ramp.domain.models.DayTotals
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.EngineSettings
import com.circaso.ramp.domain.models.EngineSettingsVersion
import com.circaso.ramp.domain.models.FeeSplit
import com.circaso.ramp.domain.models.FinishedOrder
import com.circaso.ramp.domain.models.KesBalances
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantSession
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.MerchantType
import com.circaso.ramp.domain.models.MerchantWallet
import com.circaso.ramp.domain.models.OfflineReason
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderPatch
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.PaidOutcome
import com.circaso.ramp.domain.models.PaymentsIssue
import com.circaso.ramp.domain.models.PayoutIssue
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.PreviewAccess
import com.circaso.ramp.domain.models.Quote
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RevenueWallet
import com.circaso.ramp.domain.models.RoutingDecision
import com.circaso.ramp.domain.models.RoutingFilter
import com.circaso.ramp.domain.models.RoutingOutcome
import com.circaso.ramp.domain.models.RoutingStep
import com.circaso.ramp.domain.models.SealedCredentials
import com.circaso.ramp.domain.models.Settlement
import com.circaso.ramp.domain.models.SettlementState
import com.circaso.ramp.domain.models.StaffOrder
import com.circaso.ramp.domain.models.StaffOrderFilter
import com.circaso.ramp.domain.models.Stamped
import com.circaso.ramp.domain.models.StatementLine
import com.circaso.ramp.domain.models.StatementTotals
import com.circaso.ramp.domain.models.StoredWebhookSecret
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.models.SubjectKind
import com.circaso.ramp.domain.models.TestPayout
import com.circaso.ramp.domain.models.TestPayoutState
import com.circaso.ramp.domain.models.TopUp
import com.circaso.ramp.domain.models.TopUpState
import com.circaso.ramp.domain.models.TopUpTarget
import com.circaso.ramp.domain.models.Verification
import com.circaso.ramp.domain.models.VerificationChange
import com.circaso.ramp.domain.models.VerificationStatus
import com.circaso.ramp.domain.models.VerifiedOutcome
import com.circaso.ramp.domain.repositories.BlockRepository
import com.circaso.ramp.domain.repositories.CommissionRepository
import com.circaso.ramp.domain.repositories.CredentialRepository
import com.circaso.ramp.domain.repositories.EngineSettingsRepository
import com.circaso.ramp.domain.repositories.InsightRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.OrderEvent
import com.circaso.ramp.domain.repositories.OrderEventRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.repositories.PayoutHoldRepository
import com.circaso.ramp.domain.repositories.PreviewAccessRepository
import com.circaso.ramp.domain.repositories.QuoteRepository
import com.circaso.ramp.domain.repositories.ReservationRepository
import com.circaso.ramp.domain.repositories.RevenueWalletRepository
import com.circaso.ramp.domain.repositories.RoutingLogRepository
import com.circaso.ramp.domain.repositories.SessionRepository
import com.circaso.ramp.domain.repositories.SettingsRepository
import com.circaso.ramp.domain.repositories.SettlementRepository
import com.circaso.ramp.domain.repositories.StatementRepository
import com.circaso.ramp.domain.repositories.TestPayoutRepository
import com.circaso.ramp.domain.repositories.TopUpRepository
import com.circaso.ramp.domain.repositories.VerificationRepository
import com.circaso.ramp.domain.support.Periods
import com.circaso.ramp.domain.support.Stats
import com.circaso.shared.Network
import com.circaso.shared.UserId
import java.time.Duration
import java.time.Instant
import java.util.UUID

class MemorySettings : SettingsRepository {
    val rows = mutableMapOf<Network, RampSettings>()
    var failure: Exception? = null
    var loads = 0

    override suspend fun load(): Map<Network, RampSettings> {
        failure?.let { throw it }
        loads++
        return rows.toMap()
    }

    override suspend fun seed(settings: RampSettings) {
        rows.putIfAbsent(settings.network, settings)
    }

    override suspend fun update(
        settings: RampSettings,
        expectedVersion: Long?,
        by: String,
        at: Instant,
    ): RampSettings? {
        val current = rows[settings.network] ?: return null
        if (expectedVersion != null && current.version != expectedVersion) return null
        val saved =
            settings.copy(
                walletSetId = current.walletSetId,
                updatedBy = by,
                updatedAt = at,
                version = current.version + 1,
            )
        rows[settings.network] = saved
        return saved
    }

    override suspend fun setWalletSet(network: Network, walletSetId: String) {
        rows[network]?.takeIf { it.walletSetId == null }?.let { rows[network] = it.copy(walletSetId = walletSetId) }
    }

    /** Changes a network's row as staff would, without the checks. */
    fun set(network: Network, change: (RampSettings) -> RampSettings) {
        rows[network] = change(rows[network] ?: RampSettings.defaults(network, Instant.EPOCH))
    }
}

class MemoryPreview : PreviewAccessRepository {
    val rows = linkedMapOf<Pair<UserId, Network>, PreviewAccess>()

    override suspend fun has(userId: UserId, network: Network) = (userId to network) in rows

    override suspend fun grant(access: PreviewAccess) =
        rows.putIfAbsent(access.userId to access.network, access) == null

    override suspend fun revoke(userId: UserId, network: Network) = rows.remove(userId to network) != null

    override suspend fun list(network: Network, limit: Int) = rows.values.filter { it.network == network }.take(limit)
}

class MemoryMerchantWallets : MerchantWalletRepository {
    val rows = linkedMapOf<Pair<MerchantId, Network>, MerchantWallet>()
    lateinit var merchants: MemoryMerchants

    override suspend fun find(merchantId: MerchantId, network: Network) = rows[merchantId to network]

    override suspend fun ofMerchant(merchantId: MerchantId) = rows.values.filter { it.merchantId == merchantId }

    override suspend fun insert(wallet: MerchantWallet, at: Instant) =
        rows.putIfAbsent(wallet.merchantId to wallet.network, wallet) == null

    override suspend fun approvedWithout(network: Network, limit: Int) =
        merchants.rows.values
            .filter { it.state == MerchantState.APPROVED && (it.merchantId to network) !in rows }
            .map { it.merchantId }
            .take(limit)
}

class MemoryMerchants(private val wallets: MemoryMerchantWallets) : MerchantRepository {
    val rows = linkedMapOf<MerchantId, Merchant>()
    val locked = mutableListOf<MerchantId>()

    init {
        wallets.merchants = this
    }

    override suspend fun find(merchantId: MerchantId) = rows[merchantId]

    override suspend fun byEmail(email: String) = rows.values.firstOrNull { it.email == email }

    override suspend fun byCallbackKey(callbackKey: String) = rows.values.firstOrNull { it.callbackKey == callbackKey }

    override suspend fun insert(merchant: Merchant): Boolean {
        if (rows.values.any { it.email == merchant.email }) return false
        rows[merchant.merchantId] = merchant
        return true
    }

    override suspend fun page(before: MerchantId?, limit: Int) =
        rows.values
            .sortedByDescending { it.merchantId.value }
            .filter { before == null || it.merchantId.value < before.value }
            .take(limit)

    override suspend fun pool(
        network: Network,
        blockchain: String,
        limit: Int,
    ) = rows.values
        .mapNotNull { merchant ->
            wallets.rows[merchant.merchantId to network]
                ?.takeIf { it.blockchain == blockchain }
                ?.let { Candidate(merchant, it) }
        }.take(limit)

    override suspend fun setTier(
        merchantId: MerchantId,
        type: MerchantType,
        orderLimitMinor: Long?,
    ): Boolean {
        if (merchantId !in rows) return false
        change(merchantId) { it.copy(merchantType = type, orderLimitMinor = orderLimitMinor) }
        return true
    }

    override suspend fun setOffers(
        merchantId: MerchantId,
        onRamp: Boolean,
        offRamp: Boolean,
    ) = change(merchantId) { it.copy(offersOnramp = onRamp, offersOfframp = offRamp) }

    override suspend fun lock(merchantId: MerchantId): Merchant? {
        locked += merchantId
        return rows[merchantId]
    }

    private fun change(merchantId: MerchantId, change: (Merchant) -> Merchant) {
        rows[merchantId]?.let { rows[merchantId] = change(it) }
    }

    override suspend fun markAssigned(merchantId: MerchantId, at: Instant) =
        change(merchantId) { it.copy(lastAssignedAt = at) }

    override suspend fun setProfile(
        merchantId: MerchantId,
        displayName: String,
        contactPhone: String,
    ) = change(merchantId) {
        val state = if (it.state == MerchantState.NEW) MerchantState.APPLIED else it.state
        it.copy(displayName = displayName, contactPhone = contactPhone, state = state)
    }

    override suspend fun setState(
        merchantId: MerchantId,
        to: MerchantState,
        from: Set<MerchantState>,
        by: String,
        at: Instant,
    ): Boolean {
        val merchant = rows[merchantId]?.takeIf { it.state in from } ?: return false
        val approved = to == MerchantState.APPROVED
        rows[merchantId] =
            merchant.copy(
                state = to,
                approvedBy = if (approved) by else merchant.approvedBy,
                approvedAt = if (approved) at else merchant.approvedAt,
            )
        return true
    }

    override suspend fun setCallbackKey(merchantId: MerchantId, callbackKey: String) =
        change(merchantId) { it.copy(callbackKey = callbackKey) }

    override suspend fun goOnline(
        merchantId: MerchantId,
        until: Instant?,
        since: Instant,
    ) = change(merchantId) {
        it.copy(
            onlineUntil = until,
            onlineOpenEnded = until == null,
            onlineSince = since,
            offlineReason = null,
            offlineAt = null,
        )
    }

    override suspend fun goOffline(
        merchantId: MerchantId,
        reason: OfflineReason,
        at: Instant,
    ) = change(merchantId) { it.ended(reason, at) }

    override suspend fun endLostSession(
        merchantId: MerchantId,
        since: Instant?,
        at: Instant,
    ): Boolean {
        val merchant = rows[merchantId] ?: return false
        if (!merchant.inSession() || merchant.onlineSince != since) return false
        rows[merchantId] = merchant.ended(OfflineReason.RESTART, at)
        return true
    }

    override suspend fun withSessions(limit: Int): List<Merchant> =
        rows.values
            .filter { it.inSession() }
            .sortedBy { it.merchantId.value }
            .take(limit)

    private fun Merchant.inSession() = onlineOpenEnded || onlineUntil != null

    private fun Merchant.ended(reason: OfflineReason, at: Instant) =
        copy(
            onlineUntil = null,
            onlineOpenEnded = false,
            onlineSince = null,
            offlineReason = reason,
            offlineAt = at,
        )

    override suspend fun setPaymentsIssue(
        merchantId: MerchantId,
        issue: PaymentsIssue?,
        at: Instant,
    ) = change(merchantId) { it.copy(paymentsIssue = issue, paymentsIssueAt = at.takeIf { issue != null }) }

    override suspend fun endPassedWindows(now: Instant): Int {
        val passed = rows.values.filter { it.onlineUntil?.isBefore(now) == true }
        passed.forEach { rows[it.merchantId] = it.ended(OfflineReason.WINDOW_ENDED, now) }
        return passed.size
    }

    val balances = mutableMapOf<MerchantId, KesBalances>()

    override suspend fun setPayoutAccount(merchantId: MerchantId, accountId: String?) =
        change(merchantId) { it.copy(payoutAccountId = accountId) }

    override suspend fun setPayoutIssue(
        merchantId: MerchantId,
        issue: PayoutIssue?,
        at: Instant,
    ) = change(merchantId) { it.copy(payoutIssue = issue, payoutIssueAt = at.takeIf { issue != null }) }

    override suspend fun recordBalances(merchantId: MerchantId, balances: KesBalances) {
        this.balances[merchantId] = balances.over(this.balances[merchantId] ?: KesBalances.UNREAD)
    }

    override suspend fun balances(merchantId: MerchantId): KesBalances = balances[merchantId] ?: KesBalances.UNREAD

    val webhookSecrets = mutableMapOf<MerchantId, ByteArray>()

    override suspend fun setWebhookSecret(
        merchantId: MerchantId,
        sealed: ByteArray?,
        at: Instant,
    ) {
        if (sealed == null) webhookSecrets.remove(merchantId) else webhookSecrets[merchantId] = sealed
        change(merchantId) { it.copy(webhookSecretUpdatedAt = at.takeIf { sealed != null }) }
    }

    override suspend fun webhookSecret(callbackKey: String): StoredWebhookSecret? {
        val merchant = rows.values.firstOrNull { it.callbackKey == callbackKey } ?: return null
        return webhookSecrets[merchant.merchantId]?.let { StoredWebhookSecret(merchant.merchantId, it) }
    }
}

class MemoryRevenueWallets : RevenueWalletRepository {
    val rows = mutableMapOf<Network, RevenueWallet>()

    override suspend fun find(network: Network) = rows[network]

    override suspend fun insert(wallet: RevenueWallet, at: Instant) = rows.putIfAbsent(wallet.network, wallet) == null
}

class MemoryCredentials : CredentialRepository {
    val rows = mutableMapOf<MerchantId, SealedCredentials>()

    override suspend fun find(merchantId: MerchantId) = rows[merchantId]

    override suspend fun save(
        merchantId: MerchantId,
        sealed: SealedCredentials,
        at: Instant,
    ) {
        rows[merchantId] = sealed
    }
}

class MemoryPortalSessions : SessionRepository {
    class Row(val merchantId: MerchantId, var expiresAt: Instant)

    val rows = mutableMapOf<String, Row>()

    private fun key(hash: ByteArray) = hash.joinToString("") { "%02x".format(it) }

    override suspend fun insert(
        tokenHash: ByteArray,
        merchantId: MerchantId,
        now: Instant,
        expiresAt: Instant,
    ) {
        rows[key(tokenHash)] = Row(merchantId, expiresAt)
    }

    override suspend fun find(tokenHash: ByteArray, now: Instant) =
        rows[key(tokenHash)]?.takeIf { it.expiresAt.isAfter(now) }?.let { MerchantSession(it.merchantId, it.expiresAt) }

    override suspend fun extend(tokenHash: ByteArray, expiresAt: Instant) {
        rows[key(tokenHash)]?.expiresAt = expiresAt
    }

    override suspend fun delete(tokenHash: ByteArray) = rows.remove(key(tokenHash)) != null
}

class MemoryQuotes : QuoteRepository {
    val rows = mutableMapOf<QuoteId, Quote>()

    override suspend fun insert(quote: Quote) {
        rows[quote.quoteId] = quote
    }

    override suspend fun find(quoteId: QuoteId, now: Instant) = rows[quoteId]?.takeIf { it.expiresAt.isAfter(now) }
}

private fun <T> List<T>.after(
    cursor: PageCursor?,
    at: (T) -> Instant,
    id: (T) -> UUID,
): List<T> =
    sortedWith(compareByDescending<T> { at(it) }.thenByDescending { id(it) }).filter {
        cursor == null || at(it).isBefore(cursor.at) || (at(it) == cursor.at && id(it) < cursor.id)
    }

/**
 * Orders in memory, with the same claims the tables make: one order per quote and attempt, one open order per user,
 * one receipt.
 */
class MemoryOrders :
    OrderRepository,
    OrderEventRepository {
    val rows = linkedMapOf<RampOrderId, Order>()

    /** The timeline: one event per insert and per move, as the table gets them in the same transaction. */
    val events = mutableListOf<OrderEvent>()

    private fun record(
        order: Order,
        at: Instant,
        detail: String?,
    ) {
        events += OrderEvent(order.orderId, order.state, detail, at)
    }

    override suspend fun timeline(orderId: RampOrderId) = events.filter { it.orderId == orderId }

    private fun finishedSince(
        merchantId: MerchantId,
        network: Network,
        direction: Direction,
        since: Instant,
    ) = events
        .filter { it.state in Stats.FINISHED && !it.at.isBefore(since) }
        .filter { event ->
            val order = rows.getValue(event.orderId)
            order.merchantId == merchantId && order.network == network && order.direction == direction
        }

    override suspend fun counted(
        merchantId: MerchantId,
        network: Network,
        direction: Direction,
        since: Instant,
        limit: Int,
    ): List<FinishedOrder> {
        val from = if (direction == Direction.ON_RAMP) Stats.HANDLING_FROM_ON_RAMP else Stats.HANDLING_FROM_OFF_RAMP
        return finishedSince(merchantId, network, direction, since)
            .filter { Stats.isCounted(it.state, it.detail) }
            .sortedByDescending { it.at }
            .take(limit)
            .distinctBy { it.orderId }
            .map { end ->
                val started = events.filter { it.orderId == end.orderId && it.state.name == from }.minOfOrNull { it.at }
                val handled = started?.takeIf { end.state == OrderState.COMPLETED }
                val seconds = handled?.let { Duration.between(it, end.at).seconds }
                FinishedOrder(end.orderId, end.state, end.detail, end.at, seconds)
            }
    }

    override suspend fun notCounted(
        merchantId: MerchantId,
        network: Network,
        direction: Direction,
        since: Instant,
    ) = finishedSince(merchantId, network, direction, since).count { !Stats.isCounted(it.state, it.detail) }

    override suspend fun recent(
        network: Network,
        direction: Direction,
        limit: Int,
    ) = rows.values
        .filter { it.network == network && it.direction == direction && it.attempt == 1 }
        .sortedByDescending { it.createdAt }
        .take(limit)

    override suspend fun insert(order: Order): Boolean {
        val sameAttempt = rows.values.any { it.quoteId == order.quoteId && it.attempt == order.attempt }
        if (sameAttempt || rows.values.any { it.userId == order.userId && it.state.isOpen }) return false
        rows[order.orderId] = order
        record(order, order.createdAt, null)
        return true
    }

    override suspend fun find(orderId: RampOrderId) = rows[orderId]

    override suspend fun byQuote(quoteId: QuoteId) =
        rows.values.filter { it.quoteId == quoteId }.maxByOrNull { it.attempt }

    override suspend fun openOf(userId: UserId) = rows.values.firstOrNull { it.userId == userId && it.state.isOpen }

    override suspend fun list(
        userId: UserId,
        network: Network,
        limit: Int,
        after: PageCursor?,
    ) = rows.values
        .filter { it.userId == userId && it.network == network }
        .after(after, { it.createdAt }, { it.orderId.value })
        .take(limit)

    override suspend fun ofMerchant(
        merchantId: MerchantId,
        network: Network,
        limit: Int,
        after: PageCursor?,
    ) = rows.values
        .filter { it.merchantId == merchantId && it.network == network }
        .after(after, { it.createdAt }, { it.orderId.value })
        .take(limit)

    override suspend fun needsKeys(merchantId: MerchantId) =
        rows.values.any { it.merchantId == merchantId && it.state in OrderState.NEEDS_KEYS }

    override suspend fun attachTransfer(
        orderId: RampOrderId,
        transferId: UUID,
        at: Instant,
    ): Order? {
        val order = rows[orderId] ?: return null
        val elsewhere = rows.values.any { it.walletTransferId == transferId && it.orderId != orderId }
        if (order.walletTransferId != null || elsewhere) return order
        return order.copy(walletTransferId = transferId, updatedAt = at).also { rows[orderId] = it }
    }

    override suspend fun awaitingFunding(
        network: Network,
        before: Instant,
        limit: Int,
    ) = rows.values
        .filter {
            it.network == network && it.walletTransferId != null && it.fundedMinor == null &&
                it.updatedAt.isBefore(before)
        }.sortedBy { it.updatedAt }
        .take(limit)

    override suspend fun dueForPayout(
        network: Network,
        now: Instant,
        limit: Int,
    ) = rows.values
        .filter { order ->
            order.network == network && order.state == OrderState.FUNDED &&
                order.payoutRetryAt.let { it == null || !it.isAfter(now) }
        }.sortedBy { it.updatedAt }
        .take(limit)

    override suspend fun receiptTaken(receipt: String, except: RampOrderId) =
        rows.values.any { it.mpesaReceipt == receipt && it.orderId != except }

    val rechecksAsked = linkedMapOf<RampOrderId, Instant>()

    override suspend fun requestRecheck(orderId: RampOrderId, at: Instant): Boolean {
        if (rows[orderId]?.state != OrderState.REVIEW) return false
        rechecksAsked[orderId] = at
        return true
    }

    override suspend fun rechecks(limit: Int) = rechecksAsked.keys.mapNotNull { rows[it] }.take(limit)

    override suspend fun clearRecheck(orderId: RampOrderId) {
        rechecksAsked.remove(orderId)
    }

    override suspend fun annotate(
        orderId: RampOrderId,
        state: OrderState,
        patch: OrderPatch,
        at: Instant,
    ): Order? {
        val order = rows[orderId]?.takeIf { it.state == state } ?: return null
        return order.patched(patch).copy(updatedAt = at).also { rows[orderId] = it }
    }

    /** What a patch writes: each non-null field, the rest left as it is. */
    private fun Order.patched(patch: OrderPatch) =
        copy(
            providerReference = patch.providerReference ?: providerReference,
            checkoutId = patch.checkoutId ?: checkoutId,
            custodyTxId = patch.custodyTxId ?: custodyTxId,
            txHash = patch.txHash ?: txHash,
            failureReason = patch.failureReason ?: failureReason,
            providerFeeActualMinor = patch.providerFeeActualMinor ?: providerFeeActualMinor,
            fundedMinor = patch.fundedMinor ?: fundedMinor,
            fundingTxHash = patch.fundingTxHash ?: fundingTxHash,
            fundedAt = patch.fundedAt ?: fundedAt,
            refundAddress = patch.refundAddress ?: refundAddress,
            payoutAttempts = patch.payoutAttempts ?: payoutAttempts,
            payoutFailures = patch.payoutFailures ?: payoutFailures,
            payoutReference = patch.payoutReference ?: payoutReference,
            payoutProviderReference = patch.payoutProviderReference ?: payoutProviderReference,
            payoutSecondaryReference = patch.payoutSecondaryReference ?: payoutSecondaryReference,
            payoutOutcome = patch.payoutOutcome ?: payoutOutcome,
            payoutSentAt = patch.payoutSentAt ?: payoutSentAt,
            payoutRetryAt = patch.payoutRetryAt ?: payoutRetryAt,
            mpesaReceipt = patch.mpesaReceipt ?: mpesaReceipt,
        )

    override suspend fun move(
        orderId: RampOrderId,
        to: OrderState,
        from: Set<OrderState>,
        patch: OrderPatch,
        at: Instant,
    ): Order? {
        val order = rows[orderId]?.takeIf { it.state in from } ?: return null
        val receipt = patch.mpesaReceipt
        check(receipt == null || !receiptTaken(receipt, orderId)) { "orders_receipt_key" }
        val moved = order.patched(patch).copy(state = to, updatedAt = at)
        rows[orderId] = moved
        record(moved, at, patch.failureReason)
        return moved
    }

    override suspend fun markPaid(
        orderId: RampOrderId,
        receipt: String?,
        at: Instant,
    ): PaidOutcome {
        val order =
            rows[orderId]?.takeIf { it.state == OrderState.PAYMENT_REQUESTED } ?: return PaidOutcome.AlreadyMoved
        if (receipt != null && rows.values.any { it.mpesaReceipt == receipt }) return PaidOutcome.ReceiptUsed
        val paid = order.copy(state = OrderState.PAID, mpesaReceipt = receipt, updatedAt = at)
        rows[orderId] = paid
        record(paid, at, null)
        return PaidOutcome.Moved(paid)
    }

    override suspend fun stale(
        network: Network,
        states: Set<OrderState>,
        before: Instant,
        limit: Int,
    ) = rows.values.filter { it.network == network && it.state in states && it.updatedAt.isBefore(before) }.take(limit)

    override suspend fun inReview(
        network: Network?,
        after: RampOrderId?,
        limit: Int,
    ) = rows.values
        .filter { it.state == OrderState.REVIEW && (network == null || it.network == network) }
        .sortedBy { it.orderId.value }
        .filter { after == null || it.orderId.value > after.value }
        .take(limit)

    fun reload(order: Order): Order = rows.getValue(order.orderId)
}

class MemoryReservations : ReservationRepository {
    data class Row(
        val merchantId: MerchantId,
        val network: Network,
        val amountMinor: Long,
        var state: String,
        val kind: String = "FLOAT",
    )

    val rows = mutableMapOf<RampOrderId, Row>()

    override suspend fun openTotal(merchantId: MerchantId, network: Network) =
        rows.values
            .filter { it.merchantId == merchantId && it.network == network && it.state == "OPEN" }
            .sumOf { it.amountMinor }

    override suspend fun insert(order: Order, at: Instant) {
        rows.putIfAbsent(order.orderId, Row(order.merchantId, order.network, order.assetMinor, "OPEN"))
    }

    override suspend fun hold(
        order: Order,
        amountMinor: Long,
        at: Instant,
    ) {
        rows.putIfAbsent(order.orderId, Row(order.merchantId, order.network, amountMinor, "OPEN", "HOLD"))
    }

    override suspend fun reopen(orderId: RampOrderId, at: Instant): Boolean {
        val row = rows[orderId]?.takeIf { it.state == "RELEASED" } ?: return false
        row.state = "OPEN"
        return true
    }

    override suspend fun release(orderId: RampOrderId, at: Instant) = close(orderId, "RELEASED")

    override suspend fun consume(orderId: RampOrderId, at: Instant) = close(orderId, "CONSUMED")

    private fun close(orderId: RampOrderId, to: String): Boolean {
        val row = rows[orderId]?.takeIf { it.state == "OPEN" } ?: return false
        row.state = to
        return true
    }
}

/** Commissions, their sweeps and the statement over them, in memory: one store, as the tables are one schema. */
class MemoryPayoutHolds : PayoutHoldRepository {
    data class Row(val merchantId: MerchantId, val amountMinor: Long, var state: String)

    val rows = mutableMapOf<RampOrderId, Row>()

    override suspend fun insert(order: Order, at: Instant) {
        rows.putIfAbsent(order.orderId, Row(order.merchantId, order.payoutHoldMinor, "OPEN"))
    }

    override suspend fun openTotal(merchantId: MerchantId) =
        rows.values.filter { it.merchantId == merchantId && it.state == "OPEN" }.sumOf { it.amountMinor }

    override suspend fun release(orderId: RampOrderId, at: Instant) = close(orderId, "RELEASED")

    override suspend fun consume(orderId: RampOrderId, at: Instant) = close(orderId, "CONSUMED")

    private fun close(orderId: RampOrderId, to: String): Boolean {
        val row = rows[orderId]?.takeIf { it.state == "OPEN" } ?: return false
        row.state = to
        return true
    }
}

class MemoryTestPayouts : TestPayoutRepository {
    val rows = linkedMapOf<UUID, TestPayout>()

    override suspend fun insert(test: TestPayout): Boolean {
        if (rows.values.any { it.merchantId == test.merchantId && it.state.isOpen }) return false
        rows[test.testId] = test
        return true
    }

    override suspend fun find(testId: UUID) = rows[testId]

    override suspend fun latest(merchantId: MerchantId) = rows.values.lastOrNull { it.merchantId == merchantId }

    override suspend fun move(test: TestPayout, from: Set<TestPayoutState>): Boolean {
        val current = rows[test.testId]?.takeIf { it.state in from } ?: return false
        rows[current.testId] = test
        return true
    }

    override suspend fun open(before: Instant, limit: Int) =
        rows.values.filter { it.state.isOpen && it.updatedAt.isBefore(before) }.take(limit)
}

class MemoryTopUps : TopUpRepository {
    val rows = linkedMapOf<UUID, TopUp>()

    override suspend fun insert(topUp: TopUp): Boolean {
        if (rows.values.any { it.merchantId == topUp.merchantId && it.target == topUp.target && it.state.isOpen }) {
            return false
        }
        rows[topUp.topUpId] = topUp
        return true
    }

    override suspend fun find(topUpId: UUID) = rows[topUpId]

    override suspend fun open(merchantId: MerchantId, target: TopUpTarget) =
        rows.values.firstOrNull { it.merchantId == merchantId && it.target == target && it.state.isOpen }

    override suspend fun recent(merchantId: MerchantId, limit: Int) =
        rows.values
            .filter { it.merchantId == merchantId }
            .reversed()
            .take(limit)

    override suspend fun move(topUp: TopUp, from: Set<TopUpState>): Boolean {
        val current = rows[topUp.topUpId]?.takeIf { it.state in from } ?: return false
        rows[current.topUpId] = topUp
        return true
    }

    override suspend fun stale(before: Instant, limit: Int) =
        rows.values.filter { it.state.isOpen && it.updatedAt.isBefore(before) }.take(limit)

    override suspend fun hasOpen(merchantId: MerchantId) =
        rows.values.any { it.merchantId == merchantId && it.state.isOpen }
}

class MemoryLedger(private val orders: MemoryOrders) :
    CommissionRepository,
    SettlementRepository,
    StatementRepository,
    InsightRepository {
    data class Commission(
        val order: Order,
        val split: FeeSplit,
        val earnedAt: Instant,
        var settlementId: UUID? = null,
        var settledAt: Instant? = null,
    )

    val commissions = linkedMapOf<RampOrderId, Commission>()
    val settlements = linkedMapOf<UUID, Settlement>()

    override suspend fun insert(
        order: Order,
        split: FeeSplit,
        at: Instant,
    ): Boolean {
        val settled = if (split.platformAssetMinor == 0L) at else null
        return commissions.putIfAbsent(order.orderId, Commission(order, split, at, settledAt = settled)) == null
    }

    private fun of(merchantId: MerchantId, network: Network) =
        commissions.values.filter { it.order.merchantId == merchantId && it.order.network == network }

    override suspend fun owedTotal(merchantId: MerchantId, network: Network) =
        of(merchantId, network).filter { it.settledAt == null }.sumOf { it.split.platformAssetMinor }

    override suspend fun completedCount(merchantId: MerchantId, network: Network) = of(merchantId, network).size

    override suspend fun insert(settlement: Settlement) {
        val pending =
            settlements.values.any {
                it.merchantId == settlement.merchantId && it.network == settlement.network &&
                    it.state == SettlementState.PENDING
            }
        check(!pending) { "fee_settlements_open_key" }
        settlements[settlement.settlementId] = settlement
    }

    override suspend fun pending(network: Network, limit: Int) =
        settlements.values.filter { it.network == network && it.state == SettlementState.PENDING }.take(limit)

    override suspend fun hasRecent(
        merchantId: MerchantId,
        network: Network,
        since: Instant,
        failedSince: Instant,
    ) = settlements.values.any {
        it.merchantId == merchantId && it.network == network &&
            if (it.state ==
                SettlementState.FAILED
            ) {
                !it.createdAt.isBefore(failedSince)
            } else {
                !it.createdAt.isBefore(since)
            }
    }

    override suspend fun merchantsOwing(network: Network, limit: Int) =
        commissions.values
            .filter { it.order.network == network && it.settledAt == null && it.settlementId == null }
            .map { it.order.merchantId }
            .distinct()
            .take(limit)

    override suspend fun stamp(
        merchantId: MerchantId,
        network: Network,
        settlementId: UUID,
    ): Stamped {
        val taken = of(merchantId, network).filter { it.settledAt == null && it.settlementId == null }
        taken.forEach { it.settlementId = settlementId }
        return Stamped(taken.sumOf { it.split.platformAssetMinor }, taken.size)
    }

    override suspend fun sent(
        settlementId: UUID,
        custodyTxId: String,
        at: Instant,
    ) {
        settlements[settlementId] = settlements.getValue(settlementId).copy(custodyTxId = custodyTxId)
    }

    override suspend fun confirm(
        settlementId: UUID,
        txHash: String?,
        at: Instant,
    ): Boolean {
        val pending = settlements[settlementId]?.takeIf { it.state == SettlementState.PENDING } ?: return false
        settlements[settlementId] = pending.copy(state = SettlementState.CONFIRMED, txHash = txHash)
        commissions.values.filter { it.settlementId == settlementId }.forEach { it.settledAt = at }
        return true
    }

    override suspend fun fail(
        settlementId: UUID,
        reason: String,
        at: Instant,
    ): Boolean {
        val pending = settlements[settlementId]?.takeIf { it.state == SettlementState.PENDING } ?: return false
        settlements[settlementId] = pending.copy(state = SettlementState.FAILED, failureReason = reason)
        commissions.values.filter { it.settlementId == settlementId }.forEach { it.settlementId = null }
        return true
    }

    override suspend fun ofMerchant(
        merchantId: MerchantId,
        network: Network,
        period: Period,
        limit: Int,
    ) = settlements.values
        .filter {
            it.merchantId == merchantId && it.network == network && !it.day.isBefore(period.from) &&
                !it.day.isAfter(period.to)
        }.sortedByDescending { it.day }
        .take(limit)

    private fun inPeriod(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ) = of(merchantId, network).filter {
        !it.earnedAt.isBefore(Periods.start(period)) && it.earnedAt.isBefore(Periods.end(period))
    }

    override suspend fun lines(
        merchantId: MerchantId,
        network: Network,
        period: Period,
        after: PageCursor?,
        limit: Int,
    ) = inPeriod(merchantId, network, period)
        .after(after, { it.earnedAt }, { it.order.orderId.value })
        .take(limit)
        .map {
            val order = orders.rows.getValue(it.order.orderId)
            StatementLine(
                orderId = order.orderId,
                completedAt = it.earnedAt,
                payerPhone = order.payerPhone,
                paidMinor = order.fiat.amountMinor,
                releasedMinor = order.assetMinor,
                rate = order.rate,
                feeMinor = order.feeMinor,
                merchantMinor = it.split.merchantMinor,
                platformMinor = it.split.platformMinor,
                platformAssetMinor = it.split.platformAssetMinor,
                settlementId = it.settlementId.takeIf { _ -> it.settledAt != null },
                txHash = order.txHash,
                direction = order.direction,
            )
        }

    override suspend fun totals(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ): StatementTotals {
        val rows = inPeriod(merchantId, network, period)
        val buying = rows.filter { it.order.direction == Direction.ON_RAMP }
        val selling = rows.filter { it.order.direction == Direction.OFF_RAMP }
        return StatementTotals(
            orders = rows.size,
            collectedMinor = buying.sumOf { it.order.fiat.amountMinor },
            soldMinor = buying.sumOf { it.order.assetMinor },
            paidOutMinor = selling.sumOf { it.order.fiat.amountMinor },
            boughtMinor = selling.sumOf { it.order.assetMinor },
            feesMinor = rows.sumOf { it.order.feeMinor },
            merchantMinor = rows.sumOf { it.split.merchantMinor },
            platformMinor = rows.sumOf { it.split.platformMinor },
            platformAssetMinor = rows.sumOf { it.split.platformAssetMinor },
            sweptAssetMinor = rows.filter { it.settledAt != null }.sumOf { it.split.platformAssetMinor },
        )
    }

    private fun unfinishedIn(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ) = orders.rows.values.filter {
        it.merchantId == merchantId && it.network == network && it.state != OrderState.COMPLETED &&
            !it.createdAt.isBefore(Periods.start(period)) && it.createdAt.isBefore(Periods.end(period))
    }

    override suspend fun unfinished(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ) = unfinishedIn(merchantId, network, period).groupingBy { it.state }.eachCount()

    override suspend fun unfinishedByDay(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ) = unfinishedIn(merchantId, network, period).groupingBy { Periods.merchantDay(it.createdAt) }.eachCount()

    override suspend fun completedByDay(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ) = inPeriod(merchantId, network, period)
        .groupBy { Periods.merchantDay(it.earnedAt) }
        .toSortedMap()
        .map { (day, rows) ->
            DayTotals(
                day = day,
                completed = rows.size,
                collectedMinor = rows.sumOf { it.order.fiat.amountMinor },
                soldMinor = rows.sumOf { it.order.assetMinor },
                feesMinor = rows.sumOf { it.order.feeMinor },
                merchantMinor = rows.sumOf { it.split.merchantMinor },
                platformMinor = rows.sumOf { it.split.platformMinor },
            )
        }

    override suspend fun orders(
        filter: StaffOrderFilter,
        after: PageCursor?,
        limit: Int,
    ) = orders.rows.values
        .filter { it.merchantId == filter.merchantId && it.network == filter.network }
        .filter { filter.state == null || it.state == filter.state }
        .filter { order ->
            filter.period?.let {
                !order.createdAt.isBefore(Periods.start(it)) && order.createdAt.isBefore(Periods.end(it))
            } ?: true
        }.after(after, { it.createdAt }, { it.orderId.value })
        .take(limit)
        .map { order ->
            val commission =
                commissions[order.orderId]?.let {
                    CommissionFigures(
                        earnedAt = it.earnedAt,
                        merchantMinor = it.split.merchantMinor,
                        platformMinor = it.split.platformMinor,
                        platformAssetMinor = it.split.platformAssetMinor,
                        settlementId = it.settlementId.takeIf { _ -> it.settledAt != null },
                    )
                }
            StaffOrder(order, commission)
        }
}

class MemoryVerifications : VerificationRepository {
    val rows = mutableMapOf<Pair<Subject, KycEnvironment>, Verification>()

    override suspend fun find(subject: Subject, environment: KycEnvironment) = rows[subject to environment]

    override suspend fun bySession(sessionId: String) = rows.values.firstOrNull { it.sessionId == sessionId }

    override suspend fun ofSubject(subject: Subject) =
        rows.values.filter { it.subject == subject }.sortedBy { it.environment }

    override suspend fun save(verification: Verification) {
        val key = verification.subject to verification.environment
        val before = rows[key]
        rows[key] =
            verification.copy(
                identityKey = before?.identityKey ?: verification.identityKey,
                createdAt = before?.createdAt ?: verification.createdAt,
            )
    }

    override suspend fun move(
        subject: Subject,
        environment: KycEnvironment,
        sessionId: String,
        change: VerificationChange,
        at: Instant,
    ): Verification? {
        val row = rows[subject to environment]?.takeIf { it.sessionId == sessionId } ?: return null
        val isThere =
            row.status == change.status && row.reason == change.reason && row.isSubmitted == change.isSubmitted
        if (isThere) return null
        val moved =
            row.copy(
                status = change.status,
                reason = change.reason,
                isSubmitted = change.isSubmitted,
                checkedAt = at,
                updatedAt = at,
            )
        rows[subject to environment] = moved
        return moved
    }

    override suspend fun verify(
        subject: Subject,
        environment: KycEnvironment,
        sessionId: String,
        identityKey: String?,
        at: Instant,
    ): VerifiedOutcome {
        val row =
            rows[subject to environment]?.takeIf { it.sessionId == sessionId } ?: return VerifiedOutcome.AlreadyThere
        if (row.status == VerificationStatus.VERIFIED) return VerifiedOutcome.AlreadyThere
        val taken =
            identityKey != null &&
                rows.values.any {
                    it.identityKey == identityKey &&
                        it.environment == environment &&
                        it.subject.kind == subject.kind &&
                        it.subject != subject
                }
        if (taken) return VerifiedOutcome.IdentityInUse
        val verified =
            row.copy(
                status = VerificationStatus.VERIFIED,
                reason = null,
                isSubmitted = false,
                identityKey = identityKey ?: row.identityKey,
                sessionToken = null,
                sessionUrl = null,
                sessionExpiresAt = null,
                verifiedAt = at,
                checkedAt = at,
                updatedAt = at,
            )
        rows[subject to environment] = verified
        return VerifiedOutcome.Moved(verified)
    }

    override suspend fun touch(
        subject: Subject,
        environment: KycEnvironment,
        at: Instant,
    ) {
        rows[subject to environment]?.let { rows[subject to environment] = it.copy(checkedAt = at) }
    }

    override suspend fun stale(
        statuses: Set<VerificationStatus>,
        checkedBefore: Instant,
        limit: Int,
    ) = rows.values
        .filter { it.status in statuses && it.checkedAt.isBefore(checkedBefore) }
        .sortedBy { it.checkedAt }
        .take(limit)

    override suspend fun delete(subject: Subject, environment: KycEnvironment) = rows.remove(subject to environment)
}

class MemoryBlocks : BlockRepository {
    val rows = mutableListOf<Block>()

    override suspend fun ofUser(userId: UserId) = rows.firstOrNull { it.userId == userId }

    override suspend fun isBlocked(userId: UserId, identityKey: String?) =
        rows.any { it.userId == userId || (identityKey != null && it.identityKey == identityKey) }

    override suspend fun hasIdentity(identityKey: String) = rows.any { it.identityKey == identityKey }

    override suspend fun add(block: Block): Boolean {
        val taken =
            rows.any {
                (block.userId != null && it.userId == block.userId) ||
                    (block.identityKey != null && it.identityKey == block.identityKey)
            }
        if (!taken) rows += block
        return !taken
    }

    override suspend fun removeUser(userId: UserId): Int {
        val before = rows.size
        rows.removeAll { it.userId == userId || it.sourceUserId == userId }
        return before - rows.size
    }

    override suspend fun users(after: PageCursor?, limit: Int) =
        rows
            .filter { it.userId != null }
            .sortedWith(compareByDescending<Block> { it.createdAt }.thenByDescending { it.blockId })
            .filter { after == null || it.createdAt < after.at || (it.createdAt == after.at && it.blockId < after.id) }
            .take(limit)
}

/** The engine's settings in memory: the row in force per network and direction, and every version there has been. */
class MemoryEngineSettings : EngineSettingsRepository {
    val rows = linkedMapOf<Pair<Network, Direction>, EngineSettingsVersion>()
    val history = mutableListOf<EngineSettingsVersion>()

    override suspend fun load() = rows.toMap()

    override suspend fun seed(row: EngineSettingsVersion) {
        if (rows.putIfAbsent(row.network to row.direction, row) == null) history += row
    }

    override suspend fun update(
        network: Network,
        direction: Direction,
        settings: EngineSettings,
        expectedVersion: Long?,
        by: String,
        reason: String,
        at: Instant,
    ): EngineSettingsVersion? {
        val current = rows[network to direction] ?: return null
        if (expectedVersion != null && current.version != expectedVersion) return null
        val next =
            current.copy(
                version = current.version + 1,
                settings = settings,
                updatedBy = by,
                reason = reason,
                updatedAt = at,
            )
        rows[network to direction] = next
        history += next
        return next
    }

    override suspend fun version(
        network: Network,
        direction: Direction,
        version: Long,
    ) = history.firstOrNull { it.network == network && it.direction == direction && it.version == version }

    /** Changes what is in force without a version, as a test sets the scene. */
    fun set(
        network: Network,
        direction: Direction = Direction.ON_RAMP,
        change: (EngineSettings) -> EngineSettings,
    ) {
        val current =
            rows[network to direction]
                ?: EngineSettingsVersion(
                    network,
                    direction,
                    1,
                    EngineSettings.defaults(network),
                    null,
                    null,
                    Instant.EPOCH,
                ).also { history += it }
        rows[network to direction] = current.copy(settings = change(current.settings))
    }
}

/** The routing log in memory. */
class MemoryRoutingLog : RoutingLogRepository {
    val rows = linkedMapOf<UUID, RoutingDecision>()

    override suspend fun insert(decision: RoutingDecision) {
        rows[decision.decisionId] = decision
    }

    override suspend fun finish(
        decisionId: UUID,
        orderId: RampOrderId?,
        merchantId: MerchantId?,
        outcome: RoutingOutcome,
        steps: List<RoutingStep>,
    ) {
        rows[decisionId]?.let {
            rows[decisionId] = it.copy(orderId = orderId, merchantId = merchantId, outcome = outcome, steps = steps)
        }
    }

    override suspend fun find(decisionId: UUID) = rows[decisionId]

    override suspend fun list(
        filter: RoutingFilter,
        after: PageCursor?,
        limit: Int,
    ) = rows.values
        .filter { it.network == filter.network }
        .filter { filter.merchantId == null || it.merchantId == filter.merchantId }
        .filter { filter.quoteId == null || it.quoteId == filter.quoteId }
        .filter { filter.from == null || !it.createdAt.isBefore(filter.from) }
        .filter { filter.to == null || it.createdAt.isBefore(filter.to) }
        .after(after, { it.createdAt }, { it.decisionId })
        .take(limit)

    override suspend fun prune(
        network: Network,
        direction: Direction,
        before: Instant,
        limit: Int,
    ): Int {
        val old =
            rows.values
                .filter { it.network == network && it.order.direction == direction && it.createdAt.isBefore(before) }
                .take(limit)
        old.forEach { rows.remove(it.decisionId) }
        return old.size
    }

    fun last(): RoutingDecision = rows.values.last()
}

/**
 * Tests set a merchant's session directly: a timed window to [until], or offline (null) as the merchant going offline
 * themselves would. The repository's own calls are `goOnline` and `goOffline`.
 */
suspend fun MerchantRepository.setOnlineUntil(merchantId: MerchantId, until: Instant?) {
    if (until == null) {
        goOffline(merchantId, OfflineReason.STOPPED, Instant.now())
    } else {
        goOnline(merchantId, until, since = Instant.now())
    }
}
