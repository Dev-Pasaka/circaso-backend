package com.circaso.ramp

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.DatabaseException
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.integrations.api.CollectionCredentials
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.QuoteId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.data.cache.RampExpiry
import com.circaso.ramp.data.repositories.BlockRepositoryImpl
import com.circaso.ramp.data.repositories.CommissionRepositoryImpl
import com.circaso.ramp.data.repositories.CredentialRepositoryImpl
import com.circaso.ramp.data.repositories.DatabaseTransactor
import com.circaso.ramp.data.repositories.EngineSettingsRepositoryImpl
import com.circaso.ramp.data.repositories.InsightRepositoryImpl
import com.circaso.ramp.data.repositories.MerchantRepositoryImpl
import com.circaso.ramp.data.repositories.MerchantWalletRepositoryImpl
import com.circaso.ramp.data.repositories.OrderEventRepositoryImpl
import com.circaso.ramp.data.repositories.OrderRepositoryImpl
import com.circaso.ramp.data.repositories.PayoutHoldRepositoryImpl
import com.circaso.ramp.data.repositories.PreviewAccessRepositoryImpl
import com.circaso.ramp.data.repositories.QuoteRepositoryImpl
import com.circaso.ramp.data.repositories.RampOutboxImpl
import com.circaso.ramp.data.repositories.ReservationRepositoryImpl
import com.circaso.ramp.data.repositories.RevenueWalletRepositoryImpl
import com.circaso.ramp.data.repositories.RoutingLogRepositoryImpl
import com.circaso.ramp.data.repositories.SessionRepositoryImpl
import com.circaso.ramp.data.repositories.SettingsRepositoryImpl
import com.circaso.ramp.data.repositories.SettlementRepositoryImpl
import com.circaso.ramp.data.repositories.StatementRepositoryImpl
import com.circaso.ramp.data.repositories.VerificationRepositoryImpl
import com.circaso.ramp.data.support.EnvelopeVault
import com.circaso.ramp.data.support.HmacIdentityKeys
import com.circaso.ramp.data.support.HttpPromptTransport
import com.circaso.ramp.di.rampDatabaseSpec
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Candidate
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.Exclusion
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.MerchantWallet
import com.circaso.ramp.domain.models.OfflineReason
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderPatch
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.PaidOutcome
import com.circaso.ramp.domain.models.PaymentsIssue
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.PreviewAccess
import com.circaso.ramp.domain.models.Quote
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.domain.models.RevenueWallet
import com.circaso.ramp.domain.models.RoutingOrder
import com.circaso.ramp.domain.models.Settlement
import com.circaso.ramp.domain.models.SettlementState
import com.circaso.ramp.domain.models.StaffOrderFilter
import com.circaso.ramp.domain.models.UnlockedKeys
import com.circaso.ramp.domain.support.Engine
import com.circaso.ramp.domain.support.EngineBook
import com.circaso.ramp.domain.support.EngineFacts
import com.circaso.ramp.domain.support.FeeSweeps
import com.circaso.ramp.domain.support.MerchantStatsBook
import com.circaso.ramp.domain.support.OrderFlow
import com.circaso.ramp.domain.support.Periods
import com.circaso.ramp.domain.support.Presence
import com.circaso.ramp.domain.support.Prompted
import com.circaso.ramp.domain.support.Releases
import com.circaso.ramp.domain.support.Routing
import com.circaso.ramp.domain.support.RoutingCommand
import com.circaso.ramp.domain.support.SettingsBook
import com.circaso.ramp.domain.support.Verifications
import com.circaso.shared.FiatAmount
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ramp's data layer on PostgreSQL (infrastructure's test container, migrated by the ramp's own Flyway script):
 * the claims the keys settle, the checks the table makes, float reserved under the merchant's row lock, and steps that
 * happen once however many callers race for them.
 */
class RampDataTest {
    private val database: Database = TestPostgres.database(rampDatabaseSpec)
    private val settingsRows = SettingsRepositoryImpl(database)
    private val preview = PreviewAccessRepositoryImpl(database)
    private val merchants = MerchantRepositoryImpl(database)
    private val merchantWallets = MerchantWalletRepositoryImpl(database)
    private val revenue = RevenueWalletRepositoryImpl(database)
    private val credentials = CredentialRepositoryImpl(database)
    private val portalSessions = SessionRepositoryImpl(database)
    private val quotes = QuoteRepositoryImpl(database)
    private val orders = OrderRepositoryImpl(database)
    private val reservations = ReservationRepositoryImpl(database)
    private val commissions = CommissionRepositoryImpl(database)
    private val settlements = SettlementRepositoryImpl(database)
    private val statements = StatementRepositoryImpl(database)
    private val insights = InsightRepositoryImpl(database)
    private val transactor = DatabaseTransactor(database)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val clock = TestClock(now)
    private val treasury = FakeTreasury()
    private val collections = FakeCollections()
    private val sessions = MemorySessions()
    private val bus = MemoryBus()
    private val alerts = RecordingAlerts()
    private val outbox = RampOutboxImpl(database)
    private val providers = FakeProviders(mapOf(PAYHERO to collections))
    private val verificationRows = VerificationRepositoryImpl(database)
    private val blocks = BlockRepositoryImpl(database)
    private val didit = FakeKycProvider(clock)
    private val kycProviders = FakeKycProviders(mapOf(DIDIT to didit))
    private val book = SettingsBook(settingsRows, alerts, providers, kycProviders, clock)
    private val frames = RecordingFrames()
    private val identities = HmacIdentityKeys { EnvelopeVault.masterKey(MASTER_KEY) }
    private val verifications =
        Verifications(verificationRows, blocks, kycProviders, identities, book, transactor, frames, alerts, clock)
    private val orderEvents = OrderEventRepositoryImpl(database)
    private val engineRows = EngineSettingsRepositoryImpl(database)
    private val routingLog = RoutingLogRepositoryImpl(database)
    private val engineBook = EngineBook(engineRows, alerts, clock, Duration.ZERO)
    private val statsBook = MerchantStatsBook(orderEvents, commissions, clock, Duration.ZERO)
    private val engineFacts =
        EngineFacts(
            merchants,
            reservations,
            commissions,
            treasury,
            sessions,
            verifications,
            statsBook,
            clock,
            PayoutHoldRepositoryImpl(database),
        )
    private val engine =
        Engine(
            facts = engineFacts,
            book = engineBook,
            merchants = merchants,
            wallets = merchantWallets,
            reservations = reservations,
            commissions = commissions,
            orders = orders,
            transactor = transactor,
            log = routingLog,
            random = Random(Harness.SEED),
            clock = clock,
            payoutHolds = PayoutHoldRepositoryImpl(database),
        )
    private val flow =
        OrderFlow(
            orders,
            reservations,
            merchants,
            transactor,
            outbox,
            bus,
            RecordingFrames(),
            MemoryCounters(),
            alerts,
            providers,
            sessions,
            book,
            Presence(merchants, orders, sessions, Clock.systemUTC()),
            HttpPromptTransport(),
            clock,
        )
    private val releases =
        Releases(
            orders,
            merchantWallets,
            reservations,
            commissions,
            transactor,
            outbox,
            bus,
            RecordingFrames(),
            alerts,
            FixedUsdc(),
            clock,
        )
    private val sweeps = FeeSweeps(settlements, merchantWallets, revenue, releases, book, transactor, alerts, clock)
    private val phone = PhoneNumber.parse("+254712345678")!!
    private lateinit var settings: RampSettings

    @BeforeTest
    fun clean() =
        runBlocking<Unit> {
            TestPostgres.truncate("ramp")
            RampExpiry(database).run()
            book.invalidate()
            engineBook.invalidate()
            settings = book.of(TESTNET)
        }

    private fun count(sql: String): Int =
        TestPostgres.superuser { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun rows(table: String, where: String): Int = count("SELECT count(*) FROM ramp.$table WHERE $where")

    private suspend fun merchant(name: String = "Crynce", floatMinor: Long = 100_000_000): Merchant {
        val merchant =
            Merchant(
                merchantId = MerchantId.new(),
                email = "${name.lowercase()}-${MerchantId.new()}@example.com",
                displayName = name,
                contactPhone = "+254700000000",
                state = MerchantState.APPROVED,
                callbackKey = "${name.lowercase()}-${MerchantId.new().toString().take(16)}",
                onlineUntil = now.plus(Duration.ofHours(8)),
                lastAssignedAt = null,
                approvedBy = "staff",
                approvedAt = now,
                createdAt = now,
            )
        assertTrue(merchants.insert(merchant))
        val walletId = "w-${merchant.merchantId}"
        assertTrue(
            merchantWallets.insert(MerchantWallet(merchant.merchantId, TESTNET, CHAIN, walletId, "0x$walletId"), now),
        )
        treasury.balances[walletId] = floatMinor
        treasury.addresses[walletId] = "0x$walletId"
        sessions.keys[merchant.merchantId] = UnlockedKeys(PAYHERO, CollectionCredentials("user", "pass", "911"))
        return merchant
    }

    private fun order(
        merchantId: MerchantId,
        userId: UserId = UserId.new(),
        quoteId: QuoteId = QuoteId.new(),
        state: OrderState = OrderState.RESERVED,
        at: Instant = now,
        attempt: Int = 1,
    ) = Order(
        orderId = RampOrderId.new(),
        userId = userId,
        merchantId = merchantId,
        quoteId = quoteId,
        network = TESTNET,
        direction = Direction.ON_RAMP,
        state = state,
        blockchain = CHAIN,
        fiat = FiatAmount.ofWholeUnits(1_300, "KES"),
        assetMinor = 9_800_000,
        rate = BigDecimal("130"),
        feeMinor = 2_600,
        feeBps = 200,
        merchantShareBps = 7_000,
        payerPhone = phone,
        destinationAddress = "0xuser",
        providerReference = null,
        checkoutId = null,
        mpesaReceipt = null,
        custodyTxId = null,
        txHash = null,
        failureReason = null,
        collectionsProvider = PAYHERO,
        paymentDeadline = at.plusSeconds(120),
        createdAt = at,
        updatedAt = at,
        attempt = attempt,
    )

    /** A routing of the usual purchase (1 300 shillings for 9.8 USDC), as the engine decides and logs it. */
    private suspend fun route(quoteId: QuoteId = QuoteId.new()): Routing =
        engine.route(
            RoutingCommand(quoteId, TESTNET, CHAIN, RoutingOrder(Direction.ON_RAMP, 130_000, 9_800_000)),
            settings,
        )

    /** Routes and reserves with the first merchant, in the engine's order, who can still take it under their lock. */
    private suspend fun reserve(userId: UserId = UserId.new()): Resource<Order> {
        val routing = route()
        routing.ranked.forEach { candidate ->
            val reserved =
                engine.reserve(routing, candidate, settings) { picked: Candidate ->
                    order(picked.merchant.merchantId, userId, at = clock.instant())
                }
            val triesNext = reserved is Resource.Error && reserved.error == RampError.NoMerchantAvailable
            if (!triesNext) return reserved
        }
        return Resource.error(RampError.NoMerchantAvailable)
    }

    /** A reserved order with its prompt sent, as R3 leaves it. */
    private suspend fun prompted(userId: UserId = UserId.new()): Order =
        (flow.requestPayment((reserve(userId) as Resource.Success).data) as Prompted.Sent).order

    /** An order bought, paid, released and confirmed, at the clock's time. */
    private suspend fun completed(): Order {
        val order = prompted()
        collections.pay(order)
        flow.verify(order)
        releases.release(treasury, orders.find(order.orderId)!!)
        treasury.settleAll()
        return releases.follow(treasury, orders.find(order.orderId)!!)
    }

    // The webhook signing secret

    @Test
    fun `a webhook secret is kept sealed, found by the callback address, and removed with its date`() =
        runBlocking<Unit> {
            val crynce = merchant()
            val other = merchant("Bidii")
            assertNull(merchants.webhookSecret(crynce.callbackKey!!), "none set")

            merchants.setWebhookSecret(crynce.merchantId, byteArrayOf(1, 2, 3), now)
            val stored = merchants.webhookSecret(crynce.callbackKey!!)
            assertEquals(crynce.merchantId, stored?.merchantId)
            assertTrue(byteArrayOf(1, 2, 3).contentEquals(stored?.sealed))
            assertEquals(now, merchants.find(crynce.merchantId)?.webhookSecretUpdatedAt)
            assertNull(merchants.webhookSecret(other.callbackKey!!), "only the merchant's own address")
            assertNull(merchants.webhookSecret("nobody-0123456789abcdef"))

            merchants.setWebhookSecret(crynce.merchantId, null, now)
            assertNull(merchants.webhookSecret(crynce.callbackKey!!))
            assertNull(merchants.find(crynce.merchantId)?.webhookSecretUpdatedAt)
            assertEquals(
                0,
                rows("merchants", "webhook_secret_sealed IS NOT NULL OR webhook_secret_updated_at IS NOT NULL"),
            )
        }

    // Settings

    @Test
    fun `settings are seeded once per network, and a change needs the version it was read at`() =
        runBlocking<Unit> {
            val seeded = settingsRows.load()
            assertEquals(Network.entries.toSet(), seeded.keys)
            assertEquals(RampState.OFF, seeded.getValue(Network.MAINNET).state)
            assertEquals(listOf("BASE-SEPOLIA"), seeded.getValue(TESTNET).chains)
            assertEquals(PAYHERO, seeded.getValue(TESTNET).collectionsProvider)

            settingsRows.seed(seeded.getValue(TESTNET).copy(feeBps = 499))
            assertEquals(150, settingsRows.load().getValue(TESTNET).feeBps, "a second seed changes nothing")

            val wanted =
                seeded
                    .getValue(
                        TESTNET,
                    ).copy(
                        state = RampState.PREVIEW,
                        chains = listOf("BASE-SEPOLIA", "ETH-SEPOLIA"),
                        collectionsProvider = DARAJA,
                    )
            val saved = settingsRows.update(wanted, expectedVersion = 0, by = "staff-1", at = now)
            assertEquals(1L to "staff-1", saved?.version to saved?.updatedBy)
            assertEquals(wanted.chains, saved?.chains)
            assertEquals(DARAJA, saved?.collectionsProvider, "the provider is a column staff change")
            assertNull(settingsRows.update(wanted.copy(feeBps = 10), expectedVersion = 0, by = "staff-2", at = now))
            assertEquals(150, settingsRows.load().getValue(TESTNET).feeBps, "the stale write changed nothing")

            val racing = List(6) { async { settingsRows.update(wanted.copy(feeBps = 100 + it), 1, "staff-$it", now) } }
            assertEquals(1, racing.awaitAll().count { it != null }, "one of six saves at the same version wins")

            settingsRows.setWalletSet(TESTNET, "set-1")
            settingsRows.setWalletSet(TESTNET, "set-2")
            assertEquals("set-1", settingsRows.load().getValue(TESTNET).walletSetId, "written once")
        }

    @Test
    fun `the table refuses a fee above 5 percent, a split above the whole fee, and KYC off on mainnet`() =
        runBlocking<Unit> {
            val testnet = settingsRows.load().getValue(TESTNET)
            val mainnet = settingsRows.load().getValue(Network.MAINNET)

            assertFailsWith<DatabaseException> { settingsRows.update(testnet.copy(feeBps = 501), null, "staff", now) }
            assertFailsWith<DatabaseException> {
                settingsRows.update(
                    testnet.copy(merchantShareBps = 10_001),
                    null,
                    "s",
                    now,
                )
            }
            assertFailsWith<DatabaseException> {
                settingsRows.update(
                    testnet.copy(feeSettleHourUtc = 24),
                    null,
                    "s",
                    now,
                )
            }
            assertFailsWith<DatabaseException> {
                settingsRows.update(
                    mainnet.copy(kycRequired = false),
                    null,
                    "s",
                    now,
                )
            }
            assertNotNull(settingsRows.update(testnet.copy(kycRequired = false, feeBps = 500), null, "staff", now))
        }

    @Test
    fun `the preview list grants once and revokes once`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val access = PreviewAccess(user, TESTNET, "staff", now)

            assertTrue(preview.grant(access))
            assertFalse(preview.grant(access))
            assertTrue(preview.has(user, TESTNET))
            assertFalse(preview.has(user, Network.MAINNET))
            assertEquals(listOf(access), preview.list(TESTNET, 10))
            assertTrue(preview.revoke(user, TESTNET))
            assertFalse(preview.revoke(user, TESTNET))
        }

    // Merchants

    @Test
    fun `a merchant is found by email and callback key, one per email, and moves only from an allowed state`() =
        runBlocking<Unit> {
            val merchant = merchant()

            assertEquals(merchant, merchants.find(merchant.merchantId))
            assertEquals(merchant.merchantId, merchants.byEmail(merchant.email)?.merchantId)
            assertEquals(merchant.merchantId, merchants.byCallbackKey(merchant.callbackKey!!)?.merchantId)
            assertNull(merchants.byCallbackKey("crynce-nobody"))
            assertFalse(
                merchants.insert(merchant.copy(merchantId = MerchantId.new(), callbackKey = null)),
                "same email",
            )

            merchants.setCallbackKey(merchant.merchantId, "crynce-new")
            assertNull(merchants.byCallbackKey(merchant.callbackKey!!), "the old address is gone at once")

            val applied = setOf(MerchantState.APPLIED)
            assertFalse(merchants.setState(merchant.merchantId, MerchantState.APPROVED, applied, "staff", now))
            assertTrue(
                merchants.setState(
                    merchant.merchantId,
                    MerchantState.SUSPENDED,
                    setOf(MerchantState.APPROVED),
                    "s",
                    now,
                ),
            )

            val fresh =
                merchant.copy(
                    merchantId = MerchantId.new(),
                    email = "new@example.com",
                    state = MerchantState.NEW,
                    callbackKey = null,
                )
            merchants.insert(fresh)
            merchants.setProfile(fresh.merchantId, "Fresh", "+254711111111")
            assertEquals(MerchantState.APPLIED, merchants.find(fresh.merchantId)?.state)
            merchants.setProfile(merchant.merchantId, "Renamed", "+254722222222")
            assertEquals(
                MerchantState.SUSPENDED to "Renamed",
                merchants.find(merchant.merchantId)!!.let {
                    it.state to
                        it.displayName
                },
            )
        }

    @Test
    fun `the pool is every merchant with a wallet on the chain, and the engine says why each other one is out`() =
        runBlocking<Unit> {
            val online = merchant("Online")
            val offline = merchant("Offline")
            merchants.setOnlineUntil(offline.merchantId, null)
            val ending = merchant("Ending")
            merchants.setOnlineUntil(ending.merchantId, now.plusSeconds(30))
            val walletless =
                online.copy(
                    merchantId = MerchantId.new(),
                    displayName = "Walletless",
                    email = "w@example.com",
                    callbackKey = null,
                )
            merchants.insert(walletless)

            val pool = merchants.pool(TESTNET, CHAIN, 100)
            assertEquals(
                setOf(online.merchantId, offline.merchantId, ending.merchantId),
                pool.map { it.merchant.merchantId }.toSet(),
            )
            assertEquals(
                "w-${online.merchantId}",
                pool.single { it.merchant.merchantId == online.merchantId }.wallet.custodyWalletId,
            )
            assertEquals(1, merchants.pool(TESTNET, CHAIN, 1).size, "bounded")
            assertTrue(merchants.pool(Network.MAINNET, "BASE", 100).isEmpty())
            assertTrue(merchants.pool(TESTNET, "ETH-SEPOLIA", 100).isEmpty())

            val routing = route()
            assertEquals(listOf(online.merchantId), routing.plan.ranked)
            val excluded = routing.plan.candidates.associate { it.facts.merchantId to it.exclusion }
            assertEquals(Exclusion.OFFLINE, excluded[offline.merchantId])
            assertEquals(Exclusion.OFFLINE, excluded[ending.merchantId], "a window shorter than the payment's")

            assertEquals(listOf(walletless.merchantId), merchantWallets.approvedWithout(TESTNET, 10))
            assertEquals(4, merchantWallets.approvedWithout(Network.MAINNET, 10).size)
            assertFalse(
                merchantWallets.insert(MerchantWallet(online.merchantId, TESTNET, CHAIN, "other", "0xother"), now),
            )

            assertEquals(1, merchants.endPassedWindows(now.plusSeconds(60)))
            assertNull(merchants.find(ending.merchantId)?.onlineUntil)
        }

    @Test
    fun `an open-ended session is stored as one, never ends by time, and a lost one ends only if it is the same`() =
        runBlocking<Unit> {
            val open = merchant("Open").merchantId
            merchants.goOnline(open, until = null, since = now)
            val timed = merchant("Timed").merchantId

            val stored = merchants.find(open)!!
            assertEquals(
                Triple(true, null, now),
                Triple(stored.onlineOpenEnded, stored.onlineUntil, stored.onlineSince),
            )
            assertTrue(stored.isTradingUntil(now.plus(Duration.ofDays(365))))
            assertEquals(setOf(open, timed), merchants.withSessions(10).map { it.merchantId }.toSet())
            assertNull(
                route()
                    .plan.candidates
                    .single { it.facts.merchantId == open }
                    .exclusion,
                "eligible",
            )

            assertEquals(1, merchants.endPassedWindows(now.plus(Duration.ofDays(2))), "only the timed window")
            assertEquals(OfflineReason.WINDOW_ENDED, merchants.find(timed)!!.offlineReason)
            assertTrue(merchants.find(open)!!.onlineOpenEnded)

            assertFalse(merchants.endLostSession(open, now.minusSeconds(1), now), "another session than the one read")
            assertTrue(merchants.endLostSession(open, now, now.plusSeconds(60)))
            val ended = merchants.find(open)!!
            assertEquals(
                listOf(false, null, null, OfflineReason.RESTART, now.plusSeconds(60)),
                listOf(
                    ended.onlineOpenEnded,
                    ended.onlineUntil,
                    ended.onlineSince,
                    ended.offlineReason,
                    ended.offlineAt,
                ),
            )
            assertFalse(merchants.endLostSession(open, null, now), "no session left to end")
            assertTrue(merchants.withSessions(10).isEmpty())

            merchants.goOnline(open, until = now.plusSeconds(600), since = now)
            val timedAgain = merchants.find(open)!!
            assertFalse(timedAgain.onlineOpenEnded)
            assertNull(timedAgain.offlineReason, "going online clears why the last one ended")
        }

    @Test
    fun `sealed credentials come back byte for byte, and a portal session lasts until it expires`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val vault = Harness().vault
            val sealed =
                vault.seal(
                    merchant.merchantId,
                    PAYHERO,
                    CollectionCredentials("api-user", "api-pass", "911"),
                    "482913",
                )!!

            credentials.save(merchant.merchantId, sealed, now)
            credentials.save(merchant.merchantId, sealed, now)
            val read = credentials.find(merchant.merchantId)!!

            assertTrue(read.sealed.contentEquals(sealed.sealed) && read.wrappedKey.contentEquals(sealed.wrappedKey))
            assertEquals("911" to sealed.kdfParams, read.channelId to read.kdfParams)
            assertEquals(1, count("SELECT count(*) FROM ramp.merchant_credentials"))
            assertEquals(0, rows("merchant_credentials", "encode(sealed, 'escape') LIKE '%api-pass%'"))

            val hash = ByteArray(32) { it.toByte() }
            portalSessions.insert(hash, merchant.merchantId, now, now.plusSeconds(60))
            assertEquals(merchant.merchantId, portalSessions.find(hash, now)?.merchantId)
            assertNull(portalSessions.find(ByteArray(32), now))
            assertNull(portalSessions.find(hash, now.plusSeconds(61)), "expired")
            portalSessions.extend(hash, now.plusSeconds(600))
            assertNotNull(portalSessions.find(hash, now.plusSeconds(61)))
            assertTrue(portalSessions.delete(hash))
            assertFalse(portalSessions.delete(hash))
        }

    // Orders

    @Test
    fun `a quote and an order are read back as written, with their network, fee and split`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val quote =
                Quote(
                    QuoteId.new(),
                    UserId.new(),
                    TESTNET,
                    Direction.ON_RAMP,
                    CHAIN,
                    FiatAmount.ofWholeUnits(1_300, "KES"),
                    9_800_000,
                    BigDecimal("129.25"),
                    2_600,
                    200,
                    7_000,
                    now.plusSeconds(60),
                )
            quotes.insert(quote)
            val order = order(merchant.merchantId, quote.userId, quote.quoteId)
            assertTrue(orders.insert(order))

            assertEquals(quote, quotes.find(quote.quoteId, now))
            assertNull(quotes.find(quote.quoteId, now.plusSeconds(61)), "an expired quote is gone")
            assertEquals(order, orders.find(order.orderId))
            assertEquals(order, orders.byQuote(quote.quoteId))
            assertEquals(order, orders.openOf(quote.userId))
            assertTrue(orders.needsKeys(merchant.merchantId))
        }

    @Test
    fun `a quote makes one order and a user has one open order`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val first = order(merchant.merchantId)
            assertTrue(orders.insert(first))

            assertFalse(orders.insert(order(merchant.merchantId, quoteId = first.quoteId)), "the same quote")
            assertFalse(orders.insert(order(merchant.merchantId, userId = first.userId)), "a second open order")
            orders.move(first.orderId, OrderState.EXPIRED, OrderState.AWAITING_PAYMENT, OrderPatch(), now)
            assertTrue(orders.insert(order(merchant.merchantId, userId = first.userId)), "once the first is over")
        }

    @Test
    fun `an order moves only from an allowed state, and a patch leaves the other columns alone`() =
        runBlocking<Unit> {
            val order = order(merchant().merchantId)
            orders.insert(order)
            val prompt = OrderPatch(providerReference = "PH-1", checkoutId = "ws_CO_1")

            val moved =
                orders.move(
                    order.orderId,
                    OrderState.PAYMENT_REQUESTED,
                    setOf(OrderState.RESERVED),
                    prompt,
                    now,
                )
            assertEquals(OrderState.PAYMENT_REQUESTED to "PH-1", moved?.state to moved?.providerReference)
            assertNull(
                orders.move(order.orderId, OrderState.PAYMENT_REQUESTED, setOf(OrderState.RESERVED), prompt, now),
            )
            assertNull(orders.move(order.orderId, OrderState.COMPLETED, setOf(OrderState.RELEASING), OrderPatch(), now))

            val review =
                orders.move(
                    order.orderId,
                    OrderState.REVIEW,
                    OrderFlow.REVIEWABLE,
                    OrderPatch(failureReason = "x"),
                    now,
                )
            assertEquals("PH-1" to "x", review?.providerReference to review?.failureReason)
            assertEquals(listOf(order.orderId), orders.inReview(null, null, 10).map { it.orderId })
            assertTrue(orders.inReview(Network.MAINNET, null, 10).isEmpty())
            assertTrue(orders.inReview(TESTNET, order.orderId, 10).isEmpty())
            assertFalse(orders.needsKeys(order.merchantId))
        }

    @Test
    fun `one M-Pesa receipt settles one order`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val first = order(merchant.merchantId, state = OrderState.PAYMENT_REQUESTED)
            val second = order(merchant.merchantId, state = OrderState.PAYMENT_REQUESTED)
            orders.insert(first)
            orders.insert(second)

            assertIs<PaidOutcome.Moved>(orders.markPaid(first.orderId, "SKL4XYZ123", now))
            assertEquals(PaidOutcome.AlreadyMoved, orders.markPaid(first.orderId, "SKL4XYZ123", now))
            assertEquals(PaidOutcome.ReceiptUsed, orders.markPaid(second.orderId, "SKL4XYZ123", now))
            assertEquals(OrderState.PAYMENT_REQUESTED, orders.find(second.orderId)?.state)
        }

    @Test
    fun `orders page by keyset for their user and their merchant, on one network`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val user = UserId.new()
            val made =
                (0..4).map { index ->
                    val order =
                        order(
                            merchant.merchantId,
                            user,
                            state = OrderState.FAILED,
                            at =
                                now.minusSeconds(index / 2L),
                        )
                    orders.insert(order)
                    order
                }
            val expected =
                made.sortedWith(
                    compareByDescending<Order> { it.createdAt }.thenByDescending { it.orderId.value },
                )

            val first = orders.list(user, TESTNET, 2, null)
            val second = orders.list(user, TESTNET, 2, PageCursor(first.last().createdAt, first.last().orderId.value))
            val third = orders.list(user, TESTNET, 2, PageCursor(second.last().createdAt, second.last().orderId.value))

            assertEquals(
                expected.map {
                    it.orderId
                },
                (first + second + third).map { it.orderId },
                "ties in time don't repeat or skip",
            )
            assertEquals(
                expected.take(3).map {
                    it.orderId
                },
                orders.ofMerchant(merchant.merchantId, TESTNET, 3, null).map { it.orderId },
            )
            assertTrue(orders.list(user, Network.MAINNET, 10, null).isEmpty())
            assertEquals(5, orders.stale(TESTNET, setOf(OrderState.FAILED), now.plusSeconds(1), 10).size)
            assertTrue(orders.stale(Network.MAINNET, setOf(OrderState.FAILED), now.plusSeconds(1), 10).isEmpty())
        }

    // Float, under the merchant's row lock

    @Test
    fun `ten orders at once against float for three reserve exactly three`() =
        runBlocking<Unit> {
            val merchant = merchant(floatMinor = 30_000_000)

            val outcomes = List(10) { async { reserve() } }.awaitAll()

            assertEquals(3, outcomes.count { it is Resource.Success })
            assertTrue(outcomes.filterIsInstance<Resource.Error>().all { it.error == RampError.NoMerchantAvailable })
            assertEquals(3 * 9_800_000L, reservations.openTotal(merchant.merchantId, TESTNET))
            assertEquals(0, reservations.openTotal(merchant.merchantId, Network.MAINNET))
            assertEquals(3, rows("orders", "state = 'RESERVED' AND network = 'TESTNET'"))
            assertEquals(3, rows("reservations", "state = 'OPEN'"))
        }

    @Test
    fun `what the float holds for Circaso is not free to sell - ten at once reserve only what is left`() =
        runBlocking<Unit> {
            val merchant = merchant(floatMinor = 29_660_000)
            completed()
            // 29.66 - 9.8 released = 19.86 left, of which 0.06 is Circaso's: 19.8 free, exactly two orders
            assertEquals(60_000, commissions.owedTotal(merchant.merchantId, TESTNET))

            val outcomes = List(10) { async { reserve() } }.awaitAll()

            assertEquals(2, outcomes.count { it is Resource.Success })
        }

    @Test
    fun `an order refused inside the reservation leaves nothing behind`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val userId = UserId.new()
            assertIs<Resource.Success<Order>>(reserve(userId))

            val second = reserve(userId)

            assertEquals(RampError.OrderInProgress, (second as Resource.Error).error)
            assertEquals(9_800_000, reservations.openTotal(merchant.merchantId, TESTNET))
            assertEquals(1, count("SELECT count(*) FROM ramp.reservations"))
            assertNotNull(merchants.find(merchant.merchantId)?.lastAssignedAt)
        }

    // Steps that happen once

    @Test
    fun `racing callbacks pay once, racing release runs send once, and racing confirmations complete once`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val order = prompted()
            collections.pay(order)

            List(8) { async { flow.verify(order) } }.awaitAll()
            assertEquals(OrderState.PAID, orders.find(order.orderId)?.state)
            assertTrue(treasury.sends.isEmpty(), "the app sends nothing")

            List(6) { async { releases.release(treasury, orders.find(order.orderId)!!) } }.awaitAll()
            val releasing = orders.find(order.orderId)!!
            assertEquals(OrderState.RELEASING, releasing.state)
            assertEquals(1, treasury.sends.size)

            treasury.settle(releasing.custodyTxId!!)
            List(8) { async { releases.follow(treasury, orders.find(order.orderId)!!) } }.awaitAll()

            assertEquals(OrderState.COMPLETED, orders.find(order.orderId)?.state)
            assertEquals(0, reservations.openTotal(merchant.merchantId, TESTNET))
            assertEquals(1, rows("reservations", "state = 'CONSUMED'"))
            assertEquals(1, rows("commissions", "platform_asset_minor = 60000 AND network = 'TESTNET'"))
            assertEquals(1, count("SELECT count(*) FROM ramp.outbox"))
            assertEquals(1, bus.published.size)
        }

    @Test
    fun `an order that ends unpaid frees its float in the same step, once`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val order = prompted()
            collections.fail(order)

            List(5) { async { flow.verify(order) } }.awaitAll()

            assertEquals(OrderState.FAILED, orders.find(order.orderId)?.state)
            assertEquals(0, reservations.openTotal(merchant.merchantId, TESTNET))
            assertEquals(1, rows("reservations", "state = 'RELEASED'"))
            assertEquals(1, count("SELECT count(*) FROM ramp.outbox"))
        }

    // The sweep and the statement

    @Test
    fun `racing sweeps collect each commission once, and a confirmed sweep settles its orders`() =
        runBlocking<Unit> {
            val merchant = merchant()
            revenue.insert(RevenueWallet(TESTNET, CHAIN, "w-revenue", "0xrevenue"), now)
            treasury.addresses["w-revenue"] = "0xrevenue"
            repeat(3) { completed() }
            assertEquals(180_000, commissions.owedTotal(merchant.merchantId, TESTNET))

            List(6) { async { runCatching { sweeps.run(treasury, TESTNET) } } }.awaitAll()

            assertEquals(1, count("SELECT count(*) FROM ramp.fee_settlements"))
            assertEquals(1, rows("fee_settlements", "amount_minor = 180000 AND orders = 3"))
            assertEquals(3, rows("commissions", "settlement_id IS NOT NULL AND settled_at IS NULL"))
            assertEquals(180_000, commissions.owedTotal(merchant.merchantId, TESTNET), "held until the chain confirms")
            assertEquals(4, treasury.sends.size, "three releases and one sweep")

            treasury.settleAll()
            List(4) { async { sweeps.run(treasury, TESTNET) } }.awaitAll()

            assertEquals(0, commissions.owedTotal(merchant.merchantId, TESTNET))
            assertEquals(180_000, treasury.balances.getValue("w-revenue"))
            val period = Periods.thisMonth(now)
            val sweep =
                settlements
                    .ofMerchant(
                        merchant.merchantId,
                        TESTNET,
                        Period(period.from.minusDays(1), period.to),
                        10,
                    ).single()
            assertEquals(SettlementState.CONFIRMED, sweep.state)
            assertFalse(settlements.confirm(sweep.settlementId, "0x", now), "confirmed once")
            assertFalse(settlements.fail(sweep.settlementId, "late", now))
            assertTrue(settlements.merchantsOwing(TESTNET, 10).isEmpty())
        }

    @Test
    fun `a failed sweep frees its commissions, and a merchant has one pending sweep at a time`() =
        runBlocking<Unit> {
            val merchant = merchant()
            completed()
            val id = UUID.randomUUID()
            val stamped = settlements.stamp(merchant.merchantId, TESTNET, id)
            assertEquals(60_000L to 1, stamped.totalMinor to stamped.orders)
            assertEquals(
                0L to 0,
                settlements.stamp(merchant.merchantId, TESTNET, UUID.randomUUID()).let {
                    it.totalMinor to
                        it.orders
                },
            )
            val sweep =
                Settlement(
                    id,
                    merchant.merchantId,
                    TESTNET,
                    Periods.merchantDay(now),
                    60_000,
                    1,
                    SettlementState.PENDING,
                    null,
                    null,
                    null,
                    now,
                )
            settlements.insert(sweep)
            assertFailsWith<DatabaseException> { settlements.insert(sweep.copy(settlementId = UUID.randomUUID())) }
            assertTrue(settlements.hasRecent(merchant.merchantId, TESTNET, now.minusSeconds(60), now))
            assertEquals(listOf(id), settlements.pending(TESTNET, 10).map { it.settlementId })
            settlements.sent(id, "tx-9", now)
            assertEquals("tx-9", settlements.pending(TESTNET, 10).single().custodyTxId)

            assertTrue(settlements.fail(id, "refused", now))
            assertFalse(settlements.fail(id, "refused", now))

            assertEquals(listOf(merchant.merchantId), settlements.merchantsOwing(TESTNET, 10))
            assertFalse(settlements.hasRecent(merchant.merchantId, TESTNET, now.minusSeconds(60), now.plusSeconds(1)))
            assertTrue(
                settlements.hasRecent(merchant.merchantId, TESTNET, now.plusSeconds(60), now.minusSeconds(1)),
                "a fresh failure waits",
            )
        }

    @Test
    fun `the statement's totals are the sum of its lines, and swept plus held is Circaso's USDC share`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val other = merchant("Other")
            revenue.insert(RevenueWallet(TESTNET, CHAIN, "w-revenue", "0xrevenue"), now)
            treasury.addresses["w-revenue"] = "0xrevenue"
            repeat(4) {
                completed()
                clock.advance(1)
            }
            sweeps.run(treasury, TESTNET)
            treasury.settleAll()
            sweeps.run(treasury, TESTNET)
            repeat(3) {
                completed()
                clock.advance(1)
            }
            val period = Period(Periods.merchantDay(now), Periods.merchantDay(clock.instant()))

            val both = listOf(merchant, other).map { it.merchantId }
            val allLines =
                both.associateWith { id ->
                    val first = statements.lines(id, TESTNET, period, null, 3)
                    val rest =
                        first.lastOrNull()?.let {
                            statements.lines(id, TESTNET, period, PageCursor(it.completedAt, it.orderId.value), 50)
                        }
                    first + rest.orEmpty()
                }
            assertEquals(7, allLines.values.sumOf { it.size }, "every exchange is on exactly one merchant's statement")

            both.forEach { id ->
                val lines = allLines.getValue(id)
                val totals = statements.totals(id, TESTNET, period)
                assertEquals(lines.size, totals.orders)
                assertEquals(lines.sumOf { it.paidMinor }, totals.collectedMinor)
                assertEquals(lines.sumOf { it.releasedMinor }, totals.soldMinor)
                assertEquals(lines.sumOf { it.feeMinor }, totals.feesMinor)
                assertEquals(lines.sumOf { it.merchantMinor }, totals.merchantMinor)
                assertEquals(lines.sumOf { it.platformMinor }, totals.platformMinor)
                assertEquals(lines.sumOf { it.platformAssetMinor }, totals.platformAssetMinor)
                assertEquals(totals.feesMinor, totals.merchantMinor + totals.platformMinor)
                assertEquals(
                    lines.filter { it.settlementId != null }.sumOf { it.platformAssetMinor },
                    totals.sweptAssetMinor,
                )
                assertEquals(totals.platformAssetMinor, totals.sweptAssetMinor + totals.heldAssetMinor)
                assertEquals(commissions.owedTotal(id, TESTNET), totals.heldAssetMinor)
                assertEquals(lines.sortedByDescending { it.completedAt }.map { it.orderId }, lines.map { it.orderId })
            }
            assertEquals(4 * 60_000L, both.sumOf { statements.totals(it, TESTNET, period).sweptAssetMinor })
            assertEquals(0, statements.totals(merchant.merchantId, Network.MAINNET, period).orders)
            val before = Period(period.from.minusDays(9), period.from.minusDays(1))
            assertEquals(0, statements.totals(merchant.merchantId, TESTNET, before).orders)
            assertTrue(statements.lines(merchant.merchantId, TESTNET, before, null, 10).isEmpty())
        }

    // After the first real test: attempts, the payments issue, the provider fee floor, and staff's view

    @Test
    fun `a quote makes one order per attempt, and the latest attempt is the quote's order`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val other = merchant("Other")
            val first = order(merchant.merchantId)
            assertTrue(orders.insert(first))
            assertEquals(1, orders.find(first.orderId)!!.attempt)
            orders.move(
                first.orderId,
                OrderState.FAILED,
                OrderState.AWAITING_PAYMENT,
                OrderPatch(failureReason = "prompt_refused"),
                now,
            )

            val second = order(other.merchantId, userId = first.userId, quoteId = first.quoteId, attempt = 2)
            assertTrue(orders.insert(second), "the next merchant, for the same quote")
            assertFalse(
                orders.insert(order(merchant.merchantId, quoteId = first.quoteId, attempt = 2)),
                "an attempt is made once",
            )
            assertFalse(orders.insert(order(merchant.merchantId, quoteId = first.quoteId)), "and so was the first")

            val latest = orders.byQuote(first.quoteId)!!
            assertEquals(second.orderId to 2, latest.orderId to latest.attempt)
            assertEquals(0, rows("orders", "attempt < 1"))
        }

    @Test
    fun `a merchant with a payments issue is nobody's candidate until it is cleared`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val at = now.minusSeconds(5)

            merchants.setPaymentsIssue(merchant.merchantId, PaymentsIssue.SERVICE_BALANCE, at)

            val marked = merchants.find(merchant.merchantId)!!
            assertEquals(PaymentsIssue.SERVICE_BALANCE to at, marked.paymentsIssue to marked.paymentsIssueAt)
            assertEquals(
                Exclusion.PAYMENTS_ISSUE,
                route()
                    .plan.candidates
                    .single()
                    .exclusion,
            )
            assertEquals(RampError.NoMerchantAvailable, (reserve() as Resource.Error).error)
            assertEquals(0, count("SELECT count(*) FROM ramp.orders"))
            assertFailsWith<SQLException>("only an issue the code knows") {
                TestPostgres.superuser { connection ->
                    connection.createStatement().use {
                        it.executeUpdate("UPDATE ramp.merchants SET payments_issue = 'SOMETHING_ELSE'")
                    }
                }
            }

            merchants.setPaymentsIssue(merchant.merchantId, null, now)

            val cleared = merchants.find(merchant.merchantId)!!
            assertNull(cleared.paymentsIssue)
            assertNull(cleared.paymentsIssueAt)
            assertEquals(listOf(merchant.merchantId), route().plan.ranked)
        }

    @Test
    fun `the provider fee floor is a setting - seeded at 50 shillings, changed by staff, never negative`() =
        runBlocking<Unit> {
            val testnet = settingsRows.load().getValue(TESTNET)
            assertEquals(5_000, testnet.providerFeeFloorMinor)

            val saved = settingsRows.update(testnet.copy(providerFeeFloorMinor = 0), testnet.version, "staff", now)
            assertEquals(0, saved?.providerFeeFloorMinor)
            assertEquals(0, settingsRows.load().getValue(TESTNET).providerFeeFloorMinor)
            assertEquals(5_000, settingsRows.load().getValue(Network.MAINNET).providerFeeFloorMinor)
            assertFailsWith<DatabaseException> {
                settingsRows.update(testnet.copy(providerFeeFloorMinor = -1), null, "staff", now)
            }
        }

    @Test
    fun `staff's days and orders are read from the statement's own rows, a Nairobi day at a time`() =
        runBlocking<Unit> {
            val merchant = merchant()
            revenue.insert(RevenueWallet(TESTNET, CHAIN, "w-revenue", "0xrevenue"), now)
            treasury.addresses["w-revenue"] = "0xrevenue"
            // 20:30 UTC is 23:30 in Nairobi; an hour later is the next day there
            val lateEvening = Instant.parse("2026-11-21T20:30:00Z")
            clock.now = lateEvening
            val first = completed()
            clock.advance(1)
            val second = completed()
            sweeps.run(treasury, TESTNET)
            treasury.settleAll()
            sweeps.run(treasury, TESTNET)
            clock.now = lateEvening.plusSeconds(3_600)
            val third = completed()
            clock.advance(1)
            val waiting = prompted()
            clock.advance(1)
            val lost = prompted()
            flow.close(lost, OrderState.FAILED, "payment_failed")
            val id = merchant.merchantId
            val both = Period(LocalDate.parse("2026-11-21"), LocalDate.parse("2026-11-22"))
            val the21st = Period(both.from, both.from)

            val days = insights.completedByDay(id, TESTNET, both)
            assertEquals(listOf("2026-11-21" to 2, "2026-11-22" to 1), days.map { it.day.toString() to it.completed })
            val totals = statements.totals(id, TESTNET, both)
            assertEquals(totals.orders, days.sumOf { it.completed })
            assertEquals(totals.collectedMinor, days.sumOf { it.collectedMinor })
            assertEquals(totals.soldMinor, days.sumOf { it.soldMinor })
            assertEquals(totals.feesMinor, days.sumOf { it.feesMinor })
            assertEquals(totals.merchantMinor, days.sumOf { it.merchantMinor })
            assertEquals(totals.platformMinor, days.sumOf { it.platformMinor })
            val oneDay = insights.completedByDay(id, TESTNET, the21st).single()
            assertEquals(statements.totals(id, TESTNET, the21st).collectedMinor, oneDay.collectedMinor)

            assertEquals(
                mapOf(OrderState.PAYMENT_REQUESTED to 1, OrderState.FAILED to 1),
                insights.unfinished(id, TESTNET, both),
            )
            assertEquals(mapOf(both.to to 2), insights.unfinishedByDay(id, TESTNET, both))
            assertTrue(insights.unfinished(id, TESTNET, the21st).isEmpty())
            assertTrue(insights.unfinished(id, Network.MAINNET, both).isEmpty())
            assertTrue(insights.completedByDay(MerchantId.new(), TESTNET, both).isEmpty())

            val everything = StaffOrderFilter(id, TESTNET, null, null)
            val page = insights.orders(everything, null, 3)
            val last = page.last().order
            val rest = insights.orders(everything, PageCursor(last.createdAt, last.orderId.value), 3)
            assertEquals(
                listOf(lost, waiting, third, second, first).map { it.orderId },
                (page + rest).map { it.order.orderId },
            )
            val byId = (page + rest).associateBy { it.order.orderId }
            assertNull(byId.getValue(waiting.orderId).commission)
            assertNull(byId.getValue(lost.orderId).commission)
            val swept = byId.getValue(first.orderId).commission!!
            assertEquals(
                Triple(1_820L, 780L, 60_000L),
                Triple(swept.merchantMinor, swept.platformMinor, swept.platformAssetMinor),
            )
            assertNotNull(swept.settlementId, "the confirmed sweep collected it")
            assertNull(byId.getValue(third.orderId).commission!!.settlementId, "still held")
            val lines = statements.lines(id, TESTNET, both, null, 10).associateBy { it.orderId }
            listOf(first, second, third).forEach { order ->
                val commission = byId.getValue(order.orderId).commission!!
                val line = lines.getValue(order.orderId)
                assertEquals(line.completedAt, commission.earnedAt)
                assertEquals(
                    line.merchantMinor to line.platformMinor,
                    commission.merchantMinor to commission.platformMinor,
                )
                assertEquals(line.settlementId, commission.settlementId)
            }

            assertEquals(
                listOf(third.orderId, second.orderId, first.orderId),
                insights.orders(everything.copy(state = OrderState.COMPLETED), null, 10).map { it.order.orderId },
            )
            assertEquals(
                listOf(second.orderId, first.orderId),
                insights.orders(everything.copy(period = the21st), null, 10).map { it.order.orderId },
            )
            assertTrue(insights.orders(everything.copy(network = Network.MAINNET), null, 10).isEmpty())
        }

    @Test
    fun `the purge deletes expired quotes and sessions and keeps the partitions a day ahead`() =
        runBlocking<Unit> {
            val gone =
                Quote(
                    QuoteId.new(),
                    UserId.new(),
                    TESTNET,
                    Direction.ON_RAMP,
                    CHAIN,
                    FiatAmount.ofWholeUnits(1_300, "KES"),
                    9_800_000,
                    BigDecimal("130"),
                    2_600,
                    200,
                    7_000,
                    Instant.now().minusSeconds(5),
                )
            quotes.insert(gone)
            quotes.insert(gone.copy(quoteId = QuoteId.new(), expiresAt = Instant.now().plusSeconds(600)))
            portalSessions.insert(ByteArray(32) { 1 }, MerchantId.new(), now, Instant.now().minusSeconds(5))
            portalSessions.insert(ByteArray(32) { 2 }, MerchantId.new(), now, Instant.now().plusSeconds(600))

            RampExpiry(database).run()

            assertEquals(1, count("SELECT count(*) FROM ramp.quotes"))
            assertEquals(1, count("SELECT count(*) FROM ramp.merchant_sessions"))
            val partitions =
                count(
                    "SELECT count(*) FROM pg_inherits i JOIN pg_class p ON p.oid = i.inhparent " +
                        "JOIN pg_namespace n ON n.oid = p.relnamespace " +
                        "WHERE n.nspname = 'ramp' AND p.relname = 'outbox'",
                )
            assertTrue(partitions >= 2, "today's and tomorrow's")
        }
}
