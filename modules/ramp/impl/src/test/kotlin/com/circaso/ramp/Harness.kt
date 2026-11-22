package com.circaso.ramp

import com.circaso.identity.api.AccountStatus
import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.MobileCollections
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.data.support.Argon2Cost
import com.circaso.ramp.data.support.EnvelopeVault
import com.circaso.ramp.data.support.SecureTokens
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.MerchantWallet
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderView
import com.circaso.ramp.domain.models.Quote
import com.circaso.ramp.domain.models.QuoteCommand
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.domain.models.RevenueWallet
import com.circaso.ramp.domain.models.SignedIn
import com.circaso.ramp.domain.models.StartOnRampCommand
import com.circaso.ramp.domain.models.UnlockedKeys
import com.circaso.ramp.domain.support.Engine
import com.circaso.ramp.domain.support.FeeSweeps
import com.circaso.ramp.domain.support.FloatWallets
import com.circaso.ramp.domain.support.Gates
import com.circaso.ramp.domain.support.MerchantInsights
import com.circaso.ramp.domain.support.MerchantViews
import com.circaso.ramp.domain.support.OrderFlow
import com.circaso.ramp.domain.support.PinGuard
import com.circaso.ramp.domain.support.Presence
import com.circaso.ramp.domain.support.Releases
import com.circaso.ramp.domain.support.SettingsBook
import com.circaso.ramp.domain.usecases.ApplyAsMerchant
import com.circaso.ramp.domain.usecases.CancelOrder
import com.circaso.ramp.domain.usecases.ChangePin
import com.circaso.ramp.domain.usecases.CreateQuote
import com.circaso.ramp.domain.usecases.DecideMerchant
import com.circaso.ramp.domain.usecases.EndSessions
import com.circaso.ramp.domain.usecases.GetEarnings
import com.circaso.ramp.domain.usecases.GetMerchant
import com.circaso.ramp.domain.usecases.GetMerchantSummary
import com.circaso.ramp.domain.usecases.GetOrder
import com.circaso.ramp.domain.usecases.GetRampOverview
import com.circaso.ramp.domain.usecases.GetRampStatus
import com.circaso.ramp.domain.usecases.GetSettings
import com.circaso.ramp.domain.usecases.GetStatement
import com.circaso.ramp.domain.usecases.GoOffline
import com.circaso.ramp.domain.usecases.GoOnline
import com.circaso.ramp.domain.usecases.HandlePaymentCallback
import com.circaso.ramp.domain.usecases.ListMerchantOrders
import com.circaso.ramp.domain.usecases.ListMerchants
import com.circaso.ramp.domain.usecases.ListOrders
import com.circaso.ramp.domain.usecases.ListReviewOrders
import com.circaso.ramp.domain.usecases.ListStaffMerchantOrders
import com.circaso.ramp.domain.usecases.ReleaseOrders
import com.circaso.ramp.domain.usecases.RequestSignInCode
import com.circaso.ramp.domain.usecases.ResolveSession
import com.circaso.ramp.domain.usecases.RotateCallback
import com.circaso.ramp.domain.usecases.SetPreviewAccess
import com.circaso.ramp.domain.usecases.SettleFees
import com.circaso.ramp.domain.usecases.SignOut
import com.circaso.ramp.domain.usecases.StartOnRamp
import com.circaso.ramp.domain.usecases.StorePaymentAccount
import com.circaso.ramp.domain.usecases.SweepOrders
import com.circaso.ramp.domain.usecases.UpdateSettings
import com.circaso.ramp.domain.usecases.VerifySignInCode
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Duration

const val CHAIN = "BASE-SEPOLIA"
val TESTNET = Network.TESTNET
const val PAYHERO = "payhero"
const val DARAJA = "daraja"
const val MASTER_KEY = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

/**
 * The whole ramp over in-memory fakes, both processes' sides in one: the testnet's ramp open to everyone at a 2 % fee
 * split 70/30, one approved merchant online with 100 USDC of float and his keys unlocked, and one user with a wallet.
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
    val kyc = FakeKyc()
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

    private val transactor = DirectTransactor()
    val presence = Presence(merchants, orders, sessions)
    val book = SettingsBook(settingsRows, alerts, providers, clock)
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
            clock,
        )
    val engine =
        Engine(merchants, merchantWallets, reservations, ledger, orders, transactor, treasury, sessions, clock)
    val releases =
        Releases(orders, merchantWallets, reservations, ledger, transactor, outbox, bus, frames, alerts, usdc, clock)
    val floatWallets = FloatWallets(book, settingsRows, merchantWallets, revenue, alerts, clock)
    val sweeps = FeeSweeps(ledger, merchantWallets, revenue, releases, book, transactor, alerts, clock)
    val views =
        MerchantViews(merchantWallets, credentials, reservations, ledger, treasury, sessions, book, pricing, clock)
    val pins = PinGuard(vault, pinAttempts)

    val status = GetRampStatus(gates, accounts, orders, engine)
    val createQuote = CreateQuote(gates, accounts, pricing, quotes, clock)
    val startOnRamp = StartOnRamp(gates, accounts, wallets, quotes, orders, merchants, counters, engine, flow, clock)
    val getOrder = GetOrder(orders, merchants)
    val listOrders = ListOrders(orders, merchants)
    val cancelOrder = CancelOrder(orders, merchants, flow)
    val paymentCallback = HandlePaymentCallback(merchants, orders, flow)
    val sweep = SweepOrders(orders, flow, clock)
    val releaseOrders = ReleaseOrders(treasury, floatWallets, releases, orders, clock)
    val settleFees = SettleFees(treasury, sweeps)

    val requestCode = RequestSignInCode(codes, tokens, email)
    val verifyCode = VerifySignInCode(codes, tokens, merchants, portalSessions, views, clock)
    val resolveSession = ResolveSession(portalSessions, tokens, clock)
    val signOut = SignOut(portalSessions, tokens, presence, clock)
    val getMerchant = GetMerchant(merchants, views)
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

    val listMerchants = ListMerchants(merchants, merchantWallets, credentials)
    val decideMerchant = DecideMerchant(merchants, merchantWallets, credentials, presence, clock)
    val getSettings = GetSettings(settingsRows, book)
    val updateSettings = UpdateSettings(settingsRows, book, providers, clock)
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

    val phone = PhoneNumber.parse("+254712345678")!!
    val amina: UserId
    val crynce: Merchant

    init {
        settingsRows.set(TESTNET) {
            it.copy(state = RampState.ON, feeBps = 200, merchantShareBps = 7_000, callbackBaseUrl = "https://api.test")
        }
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

    /** Signs [address] in to the portal the way a merchant does: ask for the code, read the email, send it back. */
    suspend fun verifyCodeFor(address: String, network: Network = TESTNET): SignedIn {
        requestCode(address, "203.0.113.7")
        return (verifyCode(address, email.lastCode(), network) as Resource.Success).data
    }
}
