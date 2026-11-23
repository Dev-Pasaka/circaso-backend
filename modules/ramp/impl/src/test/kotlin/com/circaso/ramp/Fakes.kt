package com.circaso.ramp

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountStatus
import com.circaso.integrations.api.BalanceReading
import com.circaso.integrations.api.CollectionBalances
import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.CollectionProviders
import com.circaso.integrations.api.CollectionReceipt
import com.circaso.integrations.api.CollectionRequest
import com.circaso.integrations.api.CollectionState
import com.circaso.integrations.api.CollectionStatus
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.DepositQuery
import com.circaso.integrations.api.Email
import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.EmailReceipt
import com.circaso.integrations.api.FeeSource
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.KycDecision
import com.circaso.integrations.api.KycDeclineReason
import com.circaso.integrations.api.KycDocument
import com.circaso.integrations.api.KycEnvironment
import com.circaso.integrations.api.KycProvider
import com.circaso.integrations.api.KycProviders
import com.circaso.integrations.api.KycSession
import com.circaso.integrations.api.KycSessionRequest
import com.circaso.integrations.api.KycStatus
import com.circaso.integrations.api.MerchantFloat
import com.circaso.integrations.api.MobileCollections
import com.circaso.integrations.api.PayoutLookup
import com.circaso.integrations.api.PayoutReceipt
import com.circaso.integrations.api.PayoutReference
import com.circaso.integrations.api.PayoutRequest
import com.circaso.integrations.api.PayoutResult
import com.circaso.integrations.api.PayoutStatus
import com.circaso.integrations.api.ProviderBalances
import com.circaso.integrations.api.ProviderFee
import com.circaso.integrations.api.ProviderFees
import com.circaso.integrations.api.TopUpLookup
import com.circaso.integrations.api.TopUpReceipt
import com.circaso.integrations.api.TopUpRequest
import com.circaso.integrations.api.TopUpResult
import com.circaso.integrations.api.TopUpStatus
import com.circaso.integrations.api.TopUpWallet
import com.circaso.integrations.api.TreasuryAccess
import com.circaso.integrations.api.TreasuryBalance
import com.circaso.integrations.api.TreasuryCustody
import com.circaso.integrations.api.TreasuryRefusal
import com.circaso.integrations.api.TreasuryTransaction
import com.circaso.integrations.api.TreasuryTransactionState
import com.circaso.integrations.api.TreasuryTransfer
import com.circaso.integrations.api.TreasuryWallet
import com.circaso.integrations.api.TreasuryWalletRequest
import com.circaso.messaging.api.ChatId
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.CodeCheck
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.KycView
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.Priced
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.models.UnlockedKeys
import com.circaso.ramp.domain.ports.FrameSender
import com.circaso.ramp.domain.ports.KycGate
import com.circaso.ramp.domain.ports.PayoutJournal
import com.circaso.ramp.domain.ports.PinAttempts
import com.circaso.ramp.domain.ports.Pricing
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.ports.RampCounters
import com.circaso.ramp.domain.ports.RampOutbox
import com.circaso.ramp.domain.ports.SignInCodes
import com.circaso.ramp.domain.ports.TradingSessions
import com.circaso.ramp.domain.ports.Transactor
import com.circaso.ramp.domain.ports.UsdcContracts
import com.circaso.ramp.domain.support.MerchantSignIn
import com.circaso.ramp.domain.support.PinGuard
import com.circaso.shared.Asset
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventId
import com.circaso.shared.EventSubscription
import com.circaso.shared.FiatAmount
import com.circaso.shared.Money
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.GroupWalletSummary
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.TransferSummary
import com.circaso.wallet.api.Wallets
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.BigInteger
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random

const val USDC_CONTRACT = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"

/** A clock the test moves by hand. */
class TestClock(var now: Instant = Instant.parse("2026-11-22T12:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
}

/** Runs the block as it is; an error from `attempt` is simply returned (the memory repositories have nothing to undo). */
class DirectTransactor : Transactor {
    override suspend fun <T> run(operation: String, block: suspend () -> T): T = block()

    override suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T> = block()
}

class RecordingOutbox : RampOutbox {
    val written = mutableListOf<EventEnvelope>()

    override suspend fun write(envelope: EventEnvelope) {
        written += envelope
    }
}

class MemoryBus : EventBus {
    val published = mutableListOf<EventEnvelope>()

    override fun <E : DomainEvent> envelope(event: E, eventSerializer: KSerializer<E>): EventEnvelope =
        EventEnvelope(
            EventId.new(),
            event.type,
            1,
            Instant.now(),
            "ramp",
            null,
            Json.encodeToJsonElement(eventSerializer, event),
        )

    override suspend fun publish(envelope: EventEnvelope) {
        published += envelope
    }

    override fun subscribe(subscription: EventSubscription) = Unit

    fun types(): List<String> = published.map { it.type }
}

class RecordingFrames : FrameSender {
    val states = mutableListOf<OrderState>()

    /** Every `kyc.updated` frame: who, on which network, and the state they were told. */
    val kyc = mutableListOf<Triple<UserId, Network, KycView>>()

    override suspend fun orderUpdated(order: Order) {
        states += order.state
    }

    override suspend fun kycUpdated(
        userId: UserId,
        network: Network,
        view: KycView,
    ) {
        kyc += Triple(userId, network, view)
    }
}

class RecordingAlerts : RampAlerts {
    val reasons = mutableListOf<String>()
    val stuck = mutableListOf<String>()

    override fun needsReview(order: Order, reason: String) {
        reasons += reason
    }

    override fun stuck(what: String, reason: String) {
        stuck += reason
    }
}

class MemoryCounters : RampCounters {
    val prompts = mutableMapOf<UserId, Long>()
    val daily = mutableMapOf<Pair<UserId, Network>, Long>()

    override suspend fun promptsThisHour(userId: UserId): Long = prompts[userId] ?: 0

    override suspend fun countPrompt(userId: UserId): Long = prompts.merge(userId, 1, Long::plus)!!

    override suspend fun reserveDaily(
        userId: UserId,
        network: Network,
        day: LocalDate,
        fiatMinor: Long,
        limitMinor: Long,
    ): Boolean {
        val total = (daily[userId to network] ?: 0) + fiatMinor
        if (total > limitMinor) return false
        daily[userId to network] = total
        return true
    }

    override suspend fun giveBackDaily(
        userId: UserId,
        network: Network,
        day: LocalDate,
        fiatMinor: Long,
    ) {
        daily[userId to network] = (daily[userId to network] ?: 0) - fiatMinor
    }

    val sold = mutableMapOf<Pair<UserId, Network>, Long>()

    override suspend fun reserveDailySale(
        userId: UserId,
        network: Network,
        day: LocalDate,
        fiatMinor: Long,
        limitMinor: Long,
    ): Boolean {
        val total = (sold[userId to network] ?: 0) + fiatMinor
        if (total > limitMinor) return false
        sold[userId to network] = total
        return true
    }

    override suspend fun giveBackDailySale(
        userId: UserId,
        network: Network,
        day: LocalDate,
        fiatMinor: Long,
    ) {
        sold[userId to network] = (sold[userId to network] ?: 0) - fiatMinor
    }
}

class FakeAccounts : AccountDirectory {
    val statuses = mutableMapOf<UserId, AccountStatus>()
    val phones = mutableMapOf<PhoneNumber, UserId>()

    override suspend fun status(userId: UserId) = statuses[userId]

    override suspend fun kind(userId: UserId): AccountKind? = null

    override suspend fun resolvePhones(phones: Set<PhoneNumber>) =
        phones.mapNotNull { number -> this.phones[number]?.let { number to it } }.toMap()
}

class FakeWallets : Wallets {
    val addresses = mutableMapOf<Pair<UserId, String>, String>()

    /** The wallet's own record of each transfer, as `Wallets.transfer` answers it; set by a test. */
    val transfers = mutableMapOf<TransferId, TransferSummary>()

    override suspend fun hasWallet(userId: UserId) = addresses.keys.any { it.first == userId }

    override suspend fun transfer(transferId: TransferId): TransferSummary? = transfers[transferId]

    override suspend fun groupWallet(chatId: ChatId): GroupWalletSummary? = null

    override suspend fun depositAddress(userId: UserId, blockchain: String) = addresses[userId to blockchain]
}

/**
 * The identity gate with a switch: [verified] waves everybody through (the tests that aren't about KYC); otherwise
 * [real] answers, which is the ramp's own rows.
 */
class FakeKyc(private val real: KycGate, var verified: Boolean = false) : KycGate {
    override suspend fun isVerified(subject: Subject, environment: KycEnvironment) =
        verified || real.isVerified(subject, environment)

    override suspend fun isBlocked(userId: UserId, environment: KycEnvironment) = real.isBlocked(userId, environment)
}

/**
 * An in-memory KYC provider: a session is `PENDING`, with the person still in it, until the test says what the
 * provider decided, or that the person finished and it hasn't decided yet ([submit]). Like Didit it keeps one
 * unfinished session per environment, workflow and subject. [failure] fails every call; [available] are
 * the environments that have keys.
 */
class FakeKycProvider(private val clock: Clock) : KycProvider {
    val started = mutableListOf<KycSessionRequest>()
    val decisionQueries = mutableListOf<String>()
    val available = KycEnvironment.entries.toMutableSet()
    var failure: IntegrationError? = null

    private class Kept(val request: KycSessionRequest, val session: KycSession, var decision: KycDecision)

    private val sessions = mutableListOf<Kept>()

    /** The id of the session last started for [subject]. */
    fun sessionOf(subject: Subject): String =
        sessions.last { it.request.subject == subject.reference }.session.sessionId

    fun approve(sessionId: String, document: KycDocument? = KycDocument("KEN", "Identity Card", "12345678")) =
        set(sessionId, KycStatus.APPROVED, document = document)

    fun decline(sessionId: String, reason: KycDeclineReason = KycDeclineReason.DOCUMENT) =
        set(sessionId, KycStatus.DECLINED, reason = reason)

    /** The person finished everything the session asked; the provider is still deciding. */
    fun submit(sessionId: String, status: KycStatus = KycStatus.PENDING) = set(sessionId, status, isSubmitted = true)

    /** [isSubmitted]: "not yet" for an unfinished session unless said; null is the provider not saying. */
    fun set(
        sessionId: String,
        status: KycStatus,
        document: KycDocument? = null,
        reason: KycDeclineReason? = null,
        subject: String? = kept(sessionId).request.subject,
        isSubmitted: Boolean? = if (status in UNFINISHED) false else null,
    ) {
        kept(sessionId).decision = KycDecision(status, subject, document, reason, isSubmitted)
    }

    /** The provider no longer knows the session (deleted on its side). */
    fun forget(sessionId: String) {
        sessions.remove(kept(sessionId))
    }

    override fun isAvailable(environment: KycEnvironment) = environment in available

    override suspend fun startSession(request: KycSessionRequest): Resource<KycSession> {
        failure?.let { return Resource.error(it) }
        if (request.environment !in available) return Resource.error(IntegrationError.NotConfigured("fake"))
        started += request
        val open =
            sessions.firstOrNull {
                it.request.environment == request.environment &&
                    it.request.subject == request.subject &&
                    it.request.workflowId == request.workflowId &&
                    it.decision.status in UNFINISHED
            }
        if (open != null) return Resource.success(open.session)
        val number = sessions.size + 1
        val session =
            KycSession(
                sessionId = UUID.randomUUID().toString(),
                token = "token-$number",
                url = "https://verify.test/session/token-$number",
                expiresAt = clock.instant().plus(request.validFor ?: Duration.ofDays(7)),
            )
        sessions += Kept(request, session, KycDecision(KycStatus.PENDING, request.subject, isSubmitted = false))
        return Resource.success(session)
    }

    override suspend fun decision(environment: KycEnvironment, sessionId: String): Resource<KycDecision> {
        failure?.let { return Resource.error(it) }
        if (environment !in available) return Resource.error(IntegrationError.NotConfigured("fake"))
        decisionQueries += sessionId
        val found = sessions.firstOrNull { it.session.sessionId == sessionId && it.request.environment == environment }
        return found?.let { Resource.success(it.decision) } ?: Resource.error(IntegrationError.Rejected("No session."))
    }

    private fun kept(sessionId: String) = sessions.first { it.session.sessionId == sessionId }

    private companion object {
        val UNFINISHED = setOf(KycStatus.PENDING, KycStatus.RESUBMIT)
    }
}

/** A provider that needs nothing from the person and approves at once, as the local `log` stand-in does. */
class InstantKycProvider : KycProvider {
    override fun isAvailable(environment: KycEnvironment) = true

    override suspend fun startSession(request: KycSessionRequest): Resource<KycSession> =
        Resource.success(KycSession("instant-${request.subject}", token = null, url = null, expiresAt = null))

    override suspend fun decision(environment: KycEnvironment, sessionId: String): Resource<KycDecision> =
        Resource.success(KycDecision(KycStatus.APPROVED, null, KycDocument("KEN", "Local", sessionId)))
}

class FakeKycProviders(private val offered: Map<String, KycProvider>) : KycProviders {
    override val names: List<String> get() = offered.keys.toList()

    override fun named(name: String): KycProvider? = offered[name]
}

/** 130 shillings a dollar; the fee is the settings' rate of what is paid: 1 300 at 2 % is a fee of 26, buying 9.8 USDC. */
class FixedPricing : Pricing {
    var failure: Resource.Error? = null

    /** 130 shillings a dollar, as [price] uses. */
    override suspend fun rate(): Resource<BigDecimal> = failure ?: Resource.success(BigDecimal("130"))

    override suspend fun price(
        direction: Direction,
        fiatMinor: Long?,
        assetMinor: Long?,
        feeBps: Int,
    ): Resource<Priced> {
        failure?.let { return it }
        val paid = fiatMinor ?: (assetMinor!! * 130 / 10_000)
        val fee = paid * feeBps / 10_000
        return Resource.success(Priced(paid, (paid - fee) * 10_000 / 130, BigDecimal("130"), fee))
    }
}

class FixedUsdc(var contract: String? = USDC_CONTRACT) : UsdcContracts {
    override fun contractOn(network: Network, blockchain: String): String? = contract
}

/** The collections providers a pretend server offers, by name. */
class FakeProviders(private val offered: Map<String, MobileCollections>) : CollectionProviders {
    override val names: List<String> get() = offered.keys.toList()

    override fun named(name: String): MobileCollections? = offered[name]
}

/** The payment provider: a prompt is `QUEUED` until the test says what the payer did, or sets any status at all. */
class FakeCollections : MobileCollections {
    val prompts = mutableListOf<CollectionRequest>()

    /** Prompts that got no answer: whether one reached the phone is exactly what nobody knows. */
    val unansweredPrompts = mutableListOf<CollectionRequest>()
    val promptedWith = mutableListOf<CollectionCredentials>()
    val statuses = mutableMapOf<String, CollectionStatus>()
    val statusQueries = mutableListOf<String>()
    val verified = mutableListOf<CollectionCredentials>()
    var failure: IntegrationError? = null
    var rejectCredentials = false

    /** Whose prompts the provider refuses, by API username, and how. */
    val refusals = mutableMapOf<String, IntegrationError>()

    /** The service wallet's balance by API username; a merchant not named here can't be read. */
    val serviceBalances = mutableMapOf<String, Long>()
    val balanceQueries = mutableListOf<CollectionCredentials>()

    fun referenceOf(order: Order): String =
        "PH-${prompts.indexOfFirst { it.reference == order.orderId.toString() } + 1}"

    fun pay(order: Order, receipt: String? = "SKL${order.orderId.toString().takeLast(6)}") {
        val prompt = prompts.first { it.reference == order.orderId.toString() }
        statuses[referenceOf(order)] =
            CollectionStatus(CollectionState.SUCCESS, prompt.amount, prompt.payer, prompt.reference, receipt)
    }

    fun fail(order: Order) {
        statuses[referenceOf(order)] = CollectionStatus(CollectionState.FAILED, null, null, null, null)
    }

    /**
     * Whose prompts get no answer, by API username: the request fails with the given cause (a timeout, an open
     * circuit) and no reference comes back.
     */
    val unanswered = mutableMapOf<String, Throwable>()

    /**
     * What the provider says when asked about a prompt by **our** reference after it gave no answer: the state it
     * reports, or absent when it can't look a prompt up that way (PayHero today).
     */
    val byOurReference = mutableMapOf<String, CollectionState>()

    /** The same for every prompt that gets no answer, whichever order it is for. */
    var unansweredState: CollectionState? = null

    override suspend fun requestPayment(
        credentials: CollectionCredentials,
        request: CollectionRequest,
    ): Resource<CollectionReceipt> {
        failure?.let { return Resource.error(it) }
        refusals[credentials.username]?.let { return Resource.error(it) }
        unanswered[credentials.username]?.let { cause ->
            unansweredState?.let { byOurReference[request.reference] = it }
            unansweredPrompts += request
            return Resource.error(IntegrationError.Unavailable("PayHero"), cause)
        }
        prompts += request
        promptedWith += credentials
        val reference = "PH-${prompts.size}"
        statuses[reference] = CollectionStatus(CollectionState.QUEUED, null, null, request.reference, null)
        return Resource.success(CollectionReceipt(reference, "ws_CO_${prompts.size}"))
    }

    override suspend fun paymentStatus(
        credentials: CollectionCredentials,
        providerReference: String,
    ): Resource<CollectionStatus> {
        failure?.let { return Resource.error(it) }
        statusQueries += providerReference
        byOurReference[providerReference]?.let { state ->
            val asked = unansweredPrompts.first { it.reference == providerReference }
            return Resource.success(CollectionStatus(state, asked.amount, asked.payer, providerReference, null))
        }
        val known =
            statuses[providerReference] ?: return Resource.error(IntegrationError.Rejected("Unknown reference."))
        return Resource.success(known)
    }

    // ---- payouts (2026-11-23) ----

    /** Every payout that reached the provider (including one whose answer was lost), so a test can count sends. */
    val payouts = mutableListOf<PayoutRequest>()

    /** Every payout asked for, whatever came of it: a send the provider never took is in here and not in [payouts]. */
    val payoutCalls = mutableListOf<PayoutRequest>()
    val payoutLookups = mutableListOf<PayoutLookup>()

    /** What the provider says of each payout, by our reference; set to answer anything at all. */
    val payoutStatuses = mutableMapOf<String, PayoutStatus>()

    /** What the next sends answer, in order; [PayoutResult.Accepted] once they run out. */
    val nextPayouts = ArrayDeque<PayoutResult>()

    /** Whether a send that answered `Unknown` actually reached the provider. */
    var unknownReached = true

    /** The payout balance by API username, and the fee balance read with it; unnamed: unreadable. */
    val payoutBalances = mutableMapOf<String, Long>()

    /** The next send reaches the provider and then the call dies (a caller's timeout): the payout went out. */
    var timeOutAfterSend = false

    override suspend fun sendPayout(credentials: CollectionCredentials, request: PayoutRequest): PayoutResult {
        payoutCalls += request
        if (timeOutAfterSend) {
            timeOutAfterSend = false
            payouts += request
            payoutStatuses.putIfAbsent(request.reference, PayoutStatus.Pending)
            throw SocketTimeoutException("read timed out")
        }
        val result = nextPayouts.removeFirstOrNull() ?: PayoutResult.Accepted(PayoutReceipt("PO-${payouts.size + 1}"))
        val reached = result is PayoutResult.Accepted || (result == PayoutResult.Unknown && unknownReached)
        if (reached) {
            payouts += request
            payoutStatuses.putIfAbsent(request.reference, PayoutStatus.Pending)
        }
        return result
    }

    /** By our reference, which the fake can always search: a payout it never took is not found. */
    override suspend fun lookUpPayout(credentials: CollectionCredentials, lookup: PayoutLookup): PayoutStatus {
        payoutLookups += lookup
        val by = if (lookup.providerReference != null) PayoutReference.PROVIDER else PayoutReference.OURS
        return payoutStatuses[lookup.reference] ?: PayoutStatus.NotFound(by)
    }

    /** The provider paid [reference] as it was asked. */
    fun payoutPaid(reference: String, receipt: String = "QP${reference.takeLast(6).uppercase()}") {
        val payout = payouts.last { it.reference == reference }
        payoutStatuses[reference] = PayoutStatus.Paid(receipt, payout.amount, reference)
    }

    // ---- top-ups (2026-11-23) ----

    /** Every top-up prompt asked for, and the ones that reached the provider (a send it never took is not here). */
    val topUpCalls = mutableListOf<TopUpRequest>()
    val topUps = mutableListOf<TopUpRequest>()
    val topUpLookups = mutableListOf<TopUpLookup>()

    /** What the next top-ups answer, in order; accepted once they run out. `Unknown` reaches the provider. */
    val nextTopUps = ArrayDeque<TopUpResult>()

    /** What the provider says of each top-up, by our reference; a prompt that went out is `Pending`. */
    val topUpStatuses = mutableMapOf<String, TopUpStatus>()

    override suspend fun requestTopUp(credentials: CollectionCredentials, request: TopUpRequest): TopUpResult {
        topUpCalls += request
        val result = nextTopUps.removeFirstOrNull() ?: TopUpResult.Accepted(TopUpReceipt("TU-${topUps.size + 1}"))
        if (result is TopUpResult.Accepted || result == TopUpResult.Unknown) {
            topUps += request
            topUpStatuses[request.reference] = TopUpStatus.Pending
        }
        return result
    }

    override suspend fun lookUpTopUp(credentials: CollectionCredentials, lookup: TopUpLookup): TopUpStatus {
        topUpLookups += lookup
        return topUpStatuses[lookup.reference] ?: TopUpStatus.NotFound
    }

    /** The merchant approved the prompt: the money lands in [username]'s wallet, and the status says so if [reported]. */
    fun topUpPaid(
        reference: String,
        username: String,
        reported: Boolean = true,
    ) {
        val topUp = topUps.last { it.reference == reference }
        val amount = topUp.amount.amountMinor
        when (topUp.wallet) {
            TopUpWallet.SERVICE -> serviceBalances[username] = (serviceBalances[username] ?: 0) + amount
            TopUpWallet.PAYMENT -> payoutBalances[username] = (payoutBalances[username] ?: 0) + amount
        }
        if (reported) {
            topUpStatuses[reference] =
                TopUpStatus.Paid("QT${reference.takeLast(6).uppercase()}", topUp.amount)
        }
    }

    override suspend fun balances(credentials: CollectionCredentials, accountId: String?): Resource<ProviderBalances> {
        val payout = payoutBalances[credentials.username] ?: return Resource.error(IntegrationError.Unavailable("x"))
        val fees = serviceBalances[credentials.username]
        return Resource.success(
            ProviderBalances(
                BalanceReading(FiatAmount(payout, "KES"), "payment_wallet"),
                fees?.let { BalanceReading(FiatAmount(it, "KES"), "service_wallet") },
            ),
        )
    }

    override suspend fun balance(credentials: CollectionCredentials): Resource<CollectionBalances> {
        balanceQueries += credentials
        val fees =
            serviceBalances[credentials.username] ?: return Resource.error(IntegrationError.Unavailable("PayHero"))
        return Resource.success(CollectionBalances(FiatAmount(0, "KES"), FiatAmount(fees, "KES")))
    }

    override suspend fun verify(credentials: CollectionCredentials): Resource<Unit> {
        failure?.let { return Resource.error(it) }
        if (rejectCredentials) return Resource.error(IntegrationError.Rejected("Wrong username or password."))
        verified += credentials
        return Resource.success(Unit)
    }
}

/**
 * The custodian's own wallets in memory, and the float reader over the same balances. A send stays in flight until
 * the test settles it; the same key never sends twice. A wallet may also hold a token that only calls itself USDC.
 */
class FakeTreasury :
    TreasuryCustody,
    MerchantFloat {
    val balances = mutableMapOf<String, Long>()
    val impostors = mutableMapOf<String, Long>()
    val sends = mutableListOf<TreasuryTransfer>()
    val walletRequests = mutableListOf<TreasuryWalletRequest>()
    val walletSets = mutableListOf<String>()
    val transactions = mutableMapOf<String, TreasuryTransaction>()
    val addresses = mutableMapOf<String, String>()

    /** What the custodian recorded arriving in each chain transaction, by its hash; set by a test. */
    val deposits = mutableMapOf<String, List<CustodyTransaction>>()
    var failure: IntegrationError? = null
    var access = TreasuryAccess.FULL
    private val byKey = mutableMapOf<UUID, String>()

    fun settle(
        transactionId: String,
        state: TreasuryTransactionState = TreasuryTransactionState.CONFIRMED,
    ): TreasuryTransaction {
        val hash = if (state == TreasuryTransactionState.CONFIRMED) "0xhash-$transactionId" else ""
        val settled = transactions.getValue(transactionId).copy(state = state, txHash = hash)
        transactions[transactionId] = settled
        return settled
    }

    fun settleAll() = transactions.keys.toList().forEach { settle(it) }

    override fun access(network: Network): TreasuryAccess = access

    override suspend fun createWalletSet(
        network: Network,
        name: String,
        idempotencyKey: UUID,
    ): Resource<String> {
        failure?.let { return Resource.error(it) }
        walletSets += name
        return Resource.success("set-${network.name.lowercase()}")
    }

    override suspend fun createWallets(
        network: Network,
        request: TreasuryWalletRequest,
    ): Resource<List<TreasuryWallet>> {
        failure?.let { return Resource.error(it) }
        walletRequests += request
        return Resource.success(
            request.blockchains.map { chain ->
                val id = "w-${request.refId}-$chain"
                addresses[id] = "0x$id"
                TreasuryWallet(id, request.walletSetId, chain, "0x$id", request.accountType, request.refId, true)
            },
        )
    }

    override suspend fun deriveWallet(
        network: Network,
        walletId: String,
        blockchain: String,
        name: String,
        refId: String,
    ): Resource<TreasuryWallet> = Resource.error(IntegrationError.NotImplemented("deriveWallet", "fake"))

    override suspend fun findWallets(
        network: Network,
        address: String?,
        blockchain: String?,
        refId: String?,
    ): Resource<List<TreasuryWallet>> = Resource.success(emptyList())

    override suspend fun balances(network: Network, walletId: String): Resource<List<TreasuryBalance>> {
        failure?.let { return Resource.error(it) }
        val real =
            balances[walletId]?.let {
                TreasuryBalance(
                    "usdc",
                    "USDC",
                    USDC_CONTRACT,
                    6,
                    false,
                    it.toBigInteger(),
                )
            }
        val fake = impostors[walletId]?.let { TreasuryBalance("scam", "USDC", "0xbad", 6, false, it.toBigInteger()) }
        return Resource.success(listOfNotNull(fake, real))
    }

    override suspend fun balance(network: Network, walletId: String): Resource<Money> {
        failure?.let { return Resource.error(it) }
        return Resource.success(Money(balances[walletId] ?: 0, Asset.USDC))
    }

    override suspend fun deposits(network: Network, query: DepositQuery): Resource<List<CustodyTransaction>> {
        failure?.let { return Resource.error(it) }
        return Resource.success(deposits[query.txHash].orEmpty().filter { it.walletId == query.walletId })
    }

    override suspend fun estimateTransfer(network: Network, transfer: TreasuryTransfer): Resource<BigDecimal> =
        Resource.success(BigDecimal.ZERO)

    override suspend fun send(network: Network, transfer: TreasuryTransfer): Resource<TreasuryTransaction> {
        failure?.let { return Resource.error(it) }
        byKey[transfer.idempotencyKey]?.let { return Resource.success(transactions.getValue(it)) }
        val held = balances[transfer.walletId] ?: 0
        if (transfer.tokenId != "usdc" || BigInteger.valueOf(held) < transfer.amountMinor) {
            return Resource.error(TreasuryRefusal(400, null))
        }
        balances[transfer.walletId] = held - transfer.amountMinor.toLong()
        addresses.entries.firstOrNull { it.value == transfer.destinationAddress }?.let { (id, _) ->
            balances[id] = (balances[id] ?: 0) + transfer.amountMinor.toLong()
        }
        sends += transfer
        val transaction = TreasuryTransaction("tx-${sends.size}", TreasuryTransactionState.IN_FLIGHT, "", "")
        transactions[transaction.transactionId] = transaction
        byKey[transfer.idempotencyKey] = transaction.transactionId
        return Resource.success(transaction)
    }

    override suspend fun transaction(network: Network, transactionId: String): Resource<TreasuryTransaction> {
        failure?.let { return Resource.error(it) }
        return Resource.success(transactions.getValue(transactionId))
    }
}

class RecordingEmail : EmailGateway {
    val sent = mutableListOf<Pair<String, Email>>()
    var failure: IntegrationError? = null

    fun lastCode(): String = (sent.last().second as Email.MerchantSignInCode).code

    override suspend fun send(
        to: String,
        email: Email,
        idempotencyKey: String?,
    ): Resource<EmailReceipt> {
        failure?.let { return Resource.error(it) }
        sent += to to email
        return Resource.success(EmailReceipt("m-${sent.size}", "fake"))
    }
}

/** Unlocked keys in memory, as the node keeps them; [marks] stands in for the `rmp:session` keys in Redis. */
class MemorySessions : TradingSessions {
    val keys = mutableMapOf<MerchantId, UnlockedKeys>()
    val marks = mutableMapOf<MerchantId, Instant>()

    override suspend fun unlock(
        merchantId: MerchantId,
        keys: UnlockedKeys,
        until: Instant?,
    ) {
        this.keys[merchantId] = keys
        marks[merchantId] = until ?: Instant.MAX
    }

    override fun hold(merchantId: MerchantId, keys: UnlockedKeys) {
        this.keys.putIfAbsent(merchantId, keys)
    }

    /** Open-ended sessions this node renewed the mark of. */
    val renewed = mutableListOf<MerchantId>()

    override suspend fun renew(merchantId: MerchantId) {
        if (merchantId in keys) renewed += merchantId
    }

    override fun of(merchantId: MerchantId): UnlockedKeys? = keys[merchantId]

    override fun replace(merchantId: MerchantId, keys: UnlockedKeys) {
        if (merchantId in this.keys) this.keys[merchantId] = keys
    }

    override suspend fun end(merchantId: MerchantId, keepKeys: Boolean) {
        if (!keepKeys) keys.remove(merchantId)
        marks.remove(merchantId)
    }

    override fun held(): Set<MerchantId> = keys.keys.toSet()

    /** The node restarted: nothing it held survives. */
    fun restart() = keys.clear()
}

class MemoryPinAttempts(private val clock: Clock) : PinAttempts {
    private class Tries(var count: Long, val until: Instant)

    private val tries = mutableMapOf<MerchantId, Tries>()

    private fun live(merchantId: MerchantId): Tries? = tries[merchantId]?.takeIf { it.until.isAfter(clock.instant()) }

    override suspend fun lockedFor(merchantId: MerchantId): Duration? =
        live(merchantId)?.takeIf { it.count >= PinGuard.MAX_TRIES }?.let { Duration.between(clock.instant(), it.until) }

    override suspend fun failed(merchantId: MerchantId): Long {
        val current = live(merchantId) ?: Tries(0, clock.instant().plusSeconds(PinGuard.LOCK_SECONDS))
        current.count += 1
        tries[merchantId] = current
        return current.count
    }

    override suspend fun clear(merchantId: MerchantId) {
        tries.remove(merchantId)
    }
}

/** The sign-in code rules in memory: one request a minute and three in ten per email, ten an hour per address. */
class MemorySignInCodes(private val clock: Clock) : SignInCodes {
    private val codes = mutableMapOf<String, Pair<String, Instant>>()
    private val tries = mutableMapOf<String, Int>()
    private val asked = mutableMapOf<String, MutableList<Instant>>()
    private val from = mutableMapOf<String, MutableList<Instant>>()

    override suspend fun admit(emailKey: String, address: String): Duration? {
        val now = clock.instant()
        val fromAddress =
            from
                .getOrPut(
                    address,
                ) { mutableListOf() }
                .apply { removeIf { it.isBefore(now.minusSeconds(3_600)) } }
        fromAddress += now
        if (fromAddress.size > 10) return Duration.between(now, fromAddress.first().plusSeconds(3_600))
        val byEmail =
            asked
                .getOrPut(
                    emailKey,
                ) { mutableListOf() }
                .apply { removeIf { it.isBefore(now.minusSeconds(600)) } }
        byEmail.lastOrNull()?.plusSeconds(MerchantSignIn.RESEND_SECONDS)?.takeIf { it.isAfter(now) }?.let {
            return Duration.between(now, it)
        }
        byEmail += now
        if (byEmail.size > 3) return Duration.between(now, byEmail.first().plusSeconds(600))
        return null
    }

    override suspend fun keep(emailKey: String, codeDigest: String) {
        codes[emailKey] = codeDigest to clock.instant().plusSeconds(600)
        tries.remove(emailKey)
    }

    override suspend fun check(emailKey: String, codeDigest: String): CodeCheck {
        val (kept, until) = codes[emailKey] ?: return CodeCheck.WRONG
        if (!until.isAfter(clock.instant())) return CodeCheck.WRONG
        if (tries.merge(emailKey, 1, Int::plus)!! > 5) {
            codes.remove(emailKey)
            return CodeCheck.LOCKED
        }
        if (kept != codeDigest) return CodeCheck.WRONG
        codes.remove(emailKey)
        return CodeCheck.MATCH
    }
}

/** A [Random] that asks for the one to use on every draw, so a test can swap the seed after the harness is built. */
class SwitchableRandom(private val current: () -> Random) : Random() {
    override fun nextBits(bitCount: Int): Int = current().nextBits(bitCount)

    override fun nextDouble(): Double = current().nextDouble()
}

/** The provider's published fees, as a test sets them: every payout costs [payoutFeeMinor]; null is "can't say". */
class FakeProviderFees(var payoutFeeMinor: Long? = 500) : ProviderFees {
    val asked = mutableListOf<Long>()

    override suspend fun collectionFee(provider: String, amount: FiatAmount): ProviderFee = ProviderFee.UNKNOWN

    override suspend fun payoutFee(provider: String, amount: FiatAmount): ProviderFee {
        asked += amount.amountMinor
        val fee = payoutFeeMinor ?: return ProviderFee.UNKNOWN
        return ProviderFee(FiatAmount(fee, amount.currency), FeeSource.LIVE_TABLE)
    }
}

/** The payout steps the ramp writes down, as `what id outcome`. */
class RecordingJournal : PayoutJournal {
    val steps = mutableListOf<String>()

    override fun step(
        what: String,
        id: String,
        outcome: String,
    ) {
        steps += "$what $id $outcome"
    }
}
