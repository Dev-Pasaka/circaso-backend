package com.circaso.ramp

import com.circaso.identity.api.AccountStatus
import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.KycDocument
import com.circaso.integrations.api.KycEnvironment
import com.circaso.integrations.api.MobileCollections
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.data.support.Argon2Cost
import com.circaso.ramp.data.support.EnvelopeVault
import com.circaso.ramp.data.support.HmacIdentityKeys
import com.circaso.ramp.data.support.HttpPromptTransport
import com.circaso.ramp.data.support.SecureTokens
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.EngineMode
import com.circaso.ramp.domain.models.EngineSettings
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.MerchantWallet
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderView
import com.circaso.ramp.domain.models.Quote
import com.circaso.ramp.domain.models.QuoteCommand
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.domain.models.RevenueWallet
import com.circaso.ramp.domain.models.RoutingDecision
import com.circaso.ramp.domain.models.SignedIn
import com.circaso.ramp.domain.models.StartOnRampCommand
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.models.UnlockedKeys
import com.circaso.ramp.domain.support.Engine
import com.circaso.ramp.domain.support.EngineBook
import com.circaso.ramp.domain.support.EngineFacts
import com.circaso.ramp.domain.support.FeeSweeps
import com.circaso.ramp.domain.support.FloatWallets
import com.circaso.ramp.domain.support.Gates
import com.circaso.ramp.domain.support.MerchantInsights
import com.circaso.ramp.domain.support.MerchantStatsBook
import com.circaso.ramp.domain.support.MerchantViews
import com.circaso.ramp.domain.support.OrderFlow
import com.circaso.ramp.domain.support.PinGuard
import com.circaso.ramp.domain.support.Presence
import com.circaso.ramp.domain.support.PurchaseRouting
import com.circaso.ramp.domain.support.Releases
import com.circaso.ramp.domain.support.RoutingSimulator
import com.circaso.ramp.domain.support.SettingsBook
import com.circaso.ramp.domain.support.Verifications
import com.circaso.ramp.domain.usecases.ApplyAsMerchant
import com.circaso.ramp.domain.usecases.BlockUser
import com.circaso.ramp.domain.usecases.CancelOrder
import com.circaso.ramp.domain.usecases.ChangePin
import com.circaso.ramp.domain.usecases.CreateQuote
import com.circaso.ramp.domain.usecases.DecideMerchant
import com.circaso.ramp.domain.usecases.EndSessions
import com.circaso.ramp.domain.usecases.GetEarnings
import com.circaso.ramp.domain.usecases.GetEngineSettings
import com.circaso.ramp.domain.usecases.GetMerchant
import com.circaso.ramp.domain.usecases.GetMerchantPerformance
import com.circaso.ramp.domain.usecases.GetMerchantSummary
import com.circaso.ramp.domain.usecases.GetOrder
import com.circaso.ramp.domain.usecases.GetRampOverview
import com.circaso.ramp.domain.usecases.GetRampStatus
import com.circaso.ramp.domain.usecases.GetRoutingDecision
import com.circaso.ramp.domain.usecases.GetSettings
import com.circaso.ramp.domain.usecases.GetStatement
import com.circaso.ramp.domain.usecases.GetUserKyc
import com.circaso.ramp.domain.usecases.GetUserVerifications
import com.circaso.ramp.domain.usecases.GoOffline
import com.circaso.ramp.domain.usecases.GoOnline
import com.circaso.ramp.domain.usecases.HandleKycNotification
import com.circaso.ramp.domain.usecases.HandlePaymentCallback
import com.circaso.ramp.domain.usecases.ListBlocks
import com.circaso.ramp.domain.usecases.ListMerchantOrders
import com.circaso.ramp.domain.usecases.ListMerchants
import com.circaso.ramp.domain.usecases.ListOrders
import com.circaso.ramp.domain.usecases.ListReviewOrders
import com.circaso.ramp.domain.usecases.ListRoutingDecisions
import com.circaso.ramp.domain.usecases.ListStaffMerchantOrders
import com.circaso.ramp.domain.usecases.PruneRoutingLog
import com.circaso.ramp.domain.usecases.ReleaseOrders
import com.circaso.ramp.domain.usecases.RequestSignInCode
import com.circaso.ramp.domain.usecases.ResetVerification
import com.circaso.ramp.domain.usecases.ResolveSession
import com.circaso.ramp.domain.usecases.RotateCallback
import com.circaso.ramp.domain.usecases.SetMerchantOffers
import com.circaso.ramp.domain.usecases.SetMerchantTier
import com.circaso.ramp.domain.usecases.SetPreviewAccess
import com.circaso.ramp.domain.usecases.SettleFees
import com.circaso.ramp.domain.usecases.SignOut
import com.circaso.ramp.domain.usecases.SimulateRouting
import com.circaso.ramp.domain.usecases.StartMerchantKyc
import com.circaso.ramp.domain.usecases.StartOnRamp
import com.circaso.ramp.domain.usecases.StartUserKyc
import com.circaso.ramp.domain.usecases.StorePaymentAccount
import com.circaso.ramp.domain.usecases.SweepOrders
import com.circaso.ramp.domain.usecases.SweepVerifications
import com.circaso.ramp.domain.usecases.UnblockUser
import com.circaso.ramp.domain.usecases.UpdateEngineSettings
import com.circaso.ramp.domain.usecases.UpdateSettings
import com.circaso.ramp.domain.usecases.VerifySignInCode
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Duration
import kotlin.random.Random

const val CHAIN = "BASE-SEPOLIA"
val TESTNET = Network.TESTNET
const val PAYHERO = "payhero"
const val DARAJA = "daraja"
const val DIDIT = "didit"
const val INSTANT = "instant"
const val MASTER_KEY = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

/**
 * The whole ramp over in-memory fakes, both processes' sides in one: the testnet's ramp open to everyone at a 2 % fee
 * split 70/30, one approved merchant online with 100 USDC of float and his keys unlocked, and one user with a wallet.
 * The merchant engine takes turns (`ROUND_ROBIN`) until a test switches it on with [engineSettings].
 * [release] and [settle] are what the ops process's jobs do.
 */
class Harness {
    val clock = TestClock()
    val settingsRows = MemorySettings()
    val preview = MemoryPreview()
    val merchantWallets = MemoryMerchantWallets()
    val merchants = MemoryMerchants(merchantWallets)
    val revenue = MemoryRevenueWallets()
    val credentials = MemoryCredentials()
    val portalSessions = MemoryPortalSessions()
    val quotes = MemoryQuotes()
    val orders = MemoryOrders()
    val reservations = MemoryReservations()
    val ledger = MemoryLedger(orders)
    val outbox = RecordingOutbox()
    val bus = MemoryBus()
    val frames = RecordingFrames()
    val alerts = RecordingAlerts()
    val counters = MemoryCounters()
    val accounts = FakeAccounts()
    val wallets = FakeWallets()
    val verificationRows = MemoryVerifications()
    val blocks = MemoryBlocks()
    val didit = FakeKycProvider(clock)
    val kycProviders = FakeKycProviders(linkedMapOf(DIDIT to didit, INSTANT to InstantKycProvider()))
    val pricing = FixedPricing()
    val usdc = FixedUsdc()
    val collections = FakeCollections()

    /** A second real provider staff can switch a network to. */
    val daraja = FakeCollections()
    val offered = linkedMapOf<String, MobileCollections>(PAYHERO to collections, DARAJA to daraja)
    val providers = FakeProviders(offered)
    val treasury = FakeTreasury()
    val email = RecordingEmail()
    val sessions = MemorySessions()
    val pinAttempts = MemoryPinAttempts(clock)
    val codes = MemorySignInCodes(clock)
    val tokens = SecureTokens()
    var masterKey: String? = MASTER_KEY
    val vault =
        EnvelopeVault(
            { EnvelopeVault.masterKey(masterKey) },
            Argon2Cost(memoryKb = 64, iterations = 1, parallelism = 1),
        )

    val identities = HmacIdentityKeys { EnvelopeVault.masterKey(masterKey) }

    private val transactor = DirectTransactor()
    val transport = HttpPromptTransport()
    val presence = Presence(merchants, orders, sessions)
    val book = SettingsBook(settingsRows, alerts, providers, kycProviders, clock)
    val verifications =
        Verifications(verificationRows, blocks, kycProviders, identities, book, transactor, frames, alerts, clock)

    /** The gate with a switch: `kyc.verified = true` waves everybody through; otherwise the real rows answer. */
    val kyc = FakeKyc(verifications)
    val gates = Gates(book, preview, kyc)
    val flow =
        OrderFlow(
            orders,
            reservations,
            merchants,
            transactor,
            outbox,
            bus,
            frames,
            counters,
            alerts,
            providers,
            sessions,
            book,
            presence,
            transport,
            clock,
        )

    /** The engine's settings; the harness puts the testnet on `ROUND_ROBIN` (its seed is `ACTIVE`). */
    val engineRows = MemoryEngineSettings()
    val engineBook = EngineBook(engineRows, alerts, clock, Duration.ZERO)

    /** Statistics are never kept between reads here, so a test sees an order the moment it finishes. */
    val statsBook = MerchantStatsBook(orders, ledger, clock, Duration.ZERO)
    val routingLog = MemoryRoutingLog()

    /** The engine's dice: seeded, so a draw is the same on every run. */
    var random: Random = Random(SEED)
    val engineFacts = EngineFacts(merchants, reservations, ledger, treasury, sessions, kyc, statsBook, clock)
    val engine =
        Engine(
            facts = engineFacts,
            book = engineBook,
            merchants = merchants,
            wallets = merchantWallets,
            reservations = reservations,
            commissions = ledger,
            orders = orders,
            transactor = transactor,
            log = routingLog,
            random = SwitchableRandom { random },
            clock = clock,
        )
    val releases =
        Releases(orders, merchantWallets, reservations, ledger, transactor, outbox, bus, frames, alerts, usdc, clock)
    val floatWallets = FloatWallets(book, settingsRows, merchantWallets, revenue, alerts, clock)
    val sweeps = FeeSweeps(ledger, merchantWallets, revenue, releases, book, transactor, alerts, clock)
    val views =
        MerchantViews(
            merchantWallets,
            credentials,
            reservations,
            ledger,
            treasury,
            sessions,
            book,
            pricing,
            verifications,
            clock,
        )
    val pins = PinGuard(vault, pinAttempts)

    val status = GetRampStatus(gates, accounts, orders, engine, verifications)
    val startKyc = StartUserKyc(gates, accounts, verifications)
    val getKyc = GetUserKyc(gates, verificationRows, verifications)
    val startMerchantKyc = StartMerchantKyc(merchants, book, verifications)
    val kycNotification = HandleKycNotification(verificationRows, verifications, alerts)
    val sweepVerifications = SweepVerifications(verificationRows, verifications, book, clock)
    val userVerifications = GetUserVerifications(verificationRows, blocks)
    val listBlocks = ListBlocks(blocks)
    val blockUser = BlockUser(verificationRows, blocks, transactor, clock)
    val unblockUser = UnblockUser(blocks)
    val resetVerification = ResetVerification(verificationRows)
    val createQuote = CreateQuote(gates, accounts, pricing, quotes, clock)
    val purchases = PurchaseRouting(engine, flow, counters, clock)
    val startOnRamp = StartOnRamp(gates, accounts, wallets, quotes, orders, merchants, counters, purchases, clock)
    val getOrder = GetOrder(orders, merchants)
    val listOrders = ListOrders(orders, merchants)
    val cancelOrder = CancelOrder(orders, merchants, flow)
    val paymentCallback = HandlePaymentCallback(merchants, orders, flow, alerts)
    val sweep = SweepOrders(orders, flow, clock)
    val releaseOrders = ReleaseOrders(treasury, floatWallets, releases, orders, clock)
    val settleFees = SettleFees(treasury, sweeps)

    val requestCode = RequestSignInCode(codes, tokens, email)
    val verifyCode = VerifySignInCode(codes, tokens, merchants, portalSessions, views, clock)
    val resolveSession = ResolveSession(portalSessions, tokens, clock)
    val signOut = SignOut(portalSessions, tokens, presence, clock)
    val getMerchant = GetMerchant(merchants, views, verificationRows, verifications, book)
    val applyAsMerchant = ApplyAsMerchant(merchants, views)
    val storePayments =
        StorePaymentAccount(merchants, credentials, providers, vault, pins, sessions, tokens, book, clock)
    val rotateCallback = RotateCallback(merchants, credentials, tokens, book)
    val goOnline =
        GoOnline(merchants, merchantWallets, credentials, views, pins, sessions, providers, book, clock)
    val goOffline = GoOffline(presence)
    val changePin = ChangePin(credentials, pins, clock)
    val merchantOrders = ListMerchantOrders(orders)
    val endSessions = EndSessions(merchants, orders, sessions, clock)
    val earnings = GetEarnings(ledger, ledger, book, clock)
    val statement = GetStatement(ledger, clock)

    val listMerchants = ListMerchants(merchants, merchantWallets, credentials, verificationRows)
    val decideMerchant =
        DecideMerchant(merchants, merchantWallets, credentials, presence, book, verificationRows, clock)
    val getSettings = GetSettings(settingsRows, book)
    val updateSettings = UpdateSettings(settingsRows, book, providers, kycProviders, clock)
    val setPreview = SetPreviewAccess(preview, clock)
    val reviewOrders = ListReviewOrders(orders, merchants)
    val insights = MerchantInsights(ledger, ledger)
    val overview = GetRampOverview(merchants, orders, insights, clock)
    val merchantSummary =
        GetMerchantSummary(
            merchants,
            merchantWallets,
            credentials,
            reservations,
            ledger,
            ledger,
            ledger,
            insights,
            treasury,
            clock,
        )
    val staffOrders = ListStaffMerchantOrders(merchants, ledger)

    val getEngineSettings = GetEngineSettings(engineRows, engineBook)
    val updateEngineSettings = UpdateEngineSettings(engineRows, engineBook, clock)
    val routingDecisions = ListRoutingDecisions(routingLog, orders)
    val routingDecision = GetRoutingDecision(routingLog)
    val simulate = SimulateRouting(RoutingSimulator(engineFacts, engineBook, book, orders, pricing, clock))
    val setTier = SetMerchantTier(merchants, merchantWallets, credentials, verificationRows)
    val performance = GetMerchantPerformance(merchants, engineBook, statsBook)
    val setOffers = SetMerchantOffers(merchants, views)
    val pruneRouting = PruneRoutingLog(routingLog, engineBook, clock)

    val phone = PhoneNumber.parse("+254712345678")!!
    val amina: UserId
    val crynce: Merchant

    init {
        settingsRows.set(TESTNET) {
            it.copy(state = RampState.ON, feeBps = 200, merchantShareBps = 7_000, callbackBaseUrl = "https://api.test")
        }
        // Merchants take turns here unless a test switches the engine on: who gets an order is then never a draw.
        engineRows.set(TESTNET) { it.copy(mode = EngineMode.ROUND_ROBIN) }
        revenue.rows[TESTNET] = RevenueWallet(TESTNET, CHAIN, "w-revenue", "0xrevenue")
        treasury.addresses["w-revenue"] = "0xrevenue"
        amina = user("+254712345678")
        crynce = merchant("Crynce", floatMinor = 100_000_000)
    }

    fun user(number: String, address: String = "0xuser${accounts.statuses.size + 1}"): UserId {
        val userId = UserId.new()
        accounts.statuses[userId] = AccountStatus.ACTIVE
        accounts.phones[PhoneNumber.parse(number)!!] = userId
        wallets.addresses[userId to CHAIN] = address
        return userId
    }

    /** An approved merchant with a funded float wallet, a callback address, online for eight hours and unlocked. */
    fun merchant(name: String, floatMinor: Long): Merchant {
        val id = MerchantId.new()
        val merchant =
            Merchant(
                merchantId = id,
                email = "${name.lowercase()}@example.com",
                displayName = name,
                contactPhone = "+254700000000",
                state = MerchantState.APPROVED,
                callbackKey = "${name.lowercase()}-0123456789abcdef",
                onlineUntil = clock.instant().plus(Duration.ofHours(8)),
                lastAssignedAt = null,
                approvedBy = "staff",
                approvedAt = clock.instant(),
                createdAt = clock.instant(),
            )
        merchants.rows[id] = merchant
        val walletId = "w-$id-$CHAIN"
        merchantWallets.rows[id to TESTNET] = MerchantWallet(id, TESTNET, CHAIN, walletId, "0x$walletId")
        treasury.balances[walletId] = floatMinor
        treasury.addresses[walletId] = "0x$walletId"
        sessions.keys[id] = UnlockedKeys(PAYHERO, CollectionCredentials("$name-user", "$name-pass", "911"))
        return merchant
    }

    fun walletOf(merchant: Merchant): MerchantWallet = merchantWallets.rows.getValue(merchant.merchantId to TESTNET)

    /** A quote to pay [shillings] (130 a dollar, 2 % fee: 1 300 pays a fee of 26 and buys 9.8 USDC). */
    suspend fun quote(userId: UserId = amina, shillings: Long = 1_300): Resource<Quote> =
        createQuote(QuoteCommand(userId, TESTNET, Direction.ON_RAMP, CHAIN, shillings * 100, null))

    suspend fun buy(
        userId: UserId = amina,
        number: PhoneNumber = phone,
        shillings: Long = 1_300,
    ): Resource<OrderView> {
        val quote = (quote(userId, shillings) as Resource.Success).data
        return startOnRamp(StartOnRampCommand(userId, TESTNET, quote.quoteId, number))
    }

    /** An order whose M-Pesa prompt is on the phone. */
    suspend fun prompted(
        userId: UserId = amina,
        number: PhoneNumber = phone,
        shillings: Long = 1_300,
    ): Order = (buy(userId, number, shillings) as Resource.Success).data.order

    /** The provider's callback on the address of the order's own merchant. */
    suspend fun callback(order: Order) {
        paymentCallback(merchants.rows.getValue(order.merchantId).callbackKey!!, order.orderId.toString())
    }

    /** An order the provider confirmed paid, as the app leaves it: `PAID`, waiting for the ops process. */
    suspend fun paid(
        userId: UserId = amina,
        number: PhoneNumber = phone,
        shillings: Long = 1_300,
    ): Order {
        val order = prompted(userId, number, shillings)
        collections.pay(order)
        callback(order)
        return now(order)
    }

    /** Requires KYC on the testnet, as staff do once Didit is set up: verification in the sandbox. */
    fun requireKyc(network: Network = TESTNET, change: (RampSettings) -> RampSettings = { it }) {
        settingsRows.set(network) { change(it.copy(kycRequired = true, kycProvider = DIDIT)) }
        book.invalidate()
    }

    /** The session the provider has open for [subject], as the app's SDK or the hosted page would finish it. */
    fun sessionOf(subject: Subject): String = didit.sessionOf(subject)

    /** The provider's webhook for [sessionId], already verified by integrations. */
    suspend fun webhook(sessionId: String, environment: KycEnvironment = KycEnvironment.SANDBOX) =
        kycNotification(environment, sessionId)

    /** [userId] starts a check and the provider approves it: verified in the testnet's environment. */
    suspend fun verified(userId: UserId, document: KycDocument? = KycDocument("KEN", "Identity Card", "12345678")) {
        startKyc(userId, TESTNET)
        val session = sessionOf(Subject.user(userId))
        didit.approve(session, document)
        webhook(session, book.of(TESTNET).kycEnvironment)
    }

    /** [merchant] passes the merchant check on the portal: verified in the testnet's environment. */
    suspend fun verified(merchant: Merchant, document: KycDocument? = KycDocument("KEN", "Passport", "M0001")) {
        startMerchantKyc(merchant.merchantId, TESTNET)
        val session = sessionOf(Subject.merchant(merchant.merchantId))
        didit.approve(session, document)
        webhook(session, book.of(TESTNET).kycEnvironment)
    }

    /** One run of the ops process's `ramp.release` on the testnet. */
    suspend fun release(): Int = releaseOrders(TESTNET)

    /** One run of the ops process's `ramp.settle-fees` on the testnet. */
    suspend fun settle(): Int = settleFees(TESTNET)

    /** An order bought, paid, released and confirmed on chain. */
    suspend fun completed(
        userId: UserId = amina,
        number: PhoneNumber = phone,
        shillings: Long = 1_300,
    ): Order {
        val order = paid(userId, number, shillings)
        release()
        treasury.settleAll()
        release()
        return now(order)
    }

    fun now(order: Order): Order = orders.reload(order)

    /** Changes the testnet's engine settings for buying, as staff would, and makes them the ones in force. */
    fun engineSettings(change: (EngineSettings) -> EngineSettings) {
        engineRows.set(TESTNET, Direction.ON_RAMP, change)
        engineBook.invalidate()
    }

    /** The routing the engine logged last. */
    fun lastRouting(): RoutingDecision = routingLog.last()

    companion object {
        const val SEED = 20_261_123
    }

    /** Signs [address] in to the portal the way a merchant does: ask for the code, read the email, send it back. */
    suspend fun verifyCodeFor(address: String, network: Network = TESTNET): SignedIn {
        requestCode(address, "203.0.113.7")
        return (verifyCode(address, email.lastCode(), network) as Resource.Success).data
    }
}
