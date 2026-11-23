package com.circaso.ramp

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.QuoteId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.data.cache.RampExpiry
import com.circaso.ramp.data.repositories.CommissionRepositoryImpl
import com.circaso.ramp.data.repositories.DatabaseTransactor
import com.circaso.ramp.data.repositories.EngineSettingsRepositoryImpl
import com.circaso.ramp.data.repositories.MerchantRepositoryImpl
import com.circaso.ramp.data.repositories.MerchantWalletRepositoryImpl
import com.circaso.ramp.data.repositories.OrderEventRepositoryImpl
import com.circaso.ramp.data.repositories.OrderRepositoryImpl
import com.circaso.ramp.data.repositories.RoutingLogRepositoryImpl
import com.circaso.ramp.di.rampDatabaseSpec
import com.circaso.ramp.domain.models.CandidateFacts
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.EngineMode
import com.circaso.ramp.domain.models.EngineSettings
import com.circaso.ramp.domain.models.EngineSettingsVersion
import com.circaso.ramp.domain.models.Exclusion
import com.circaso.ramp.domain.models.FeeSplit
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.MerchantStats
import com.circaso.ramp.domain.models.MerchantType
import com.circaso.ramp.domain.models.MerchantWallet
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderPatch
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.PaidOutcome
import com.circaso.ramp.domain.models.RoutingDecision
import com.circaso.ramp.domain.models.RoutingFilter
import com.circaso.ramp.domain.models.RoutingOrder
import com.circaso.ramp.domain.models.RoutingOutcome
import com.circaso.ramp.domain.models.RoutingStep
import com.circaso.ramp.domain.support.EngineBook
import com.circaso.ramp.domain.support.Router
import com.circaso.ramp.domain.support.Stats
import com.circaso.shared.FiatAmount
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The merchant engine's tables on PostgreSQL (V6, the ramp's own Flyway scripts): the order timeline written by the
 * transaction of each move, a merchant's last finished orders read back from it, the versioned settings document, the
 * routing log, and a merchant's type, limit and switches.
 */
class EngineDataTest {
    private val database: Database = TestPostgres.database(rampDatabaseSpec)
    private val merchants = MerchantRepositoryImpl(database)
    private val merchantWallets = MerchantWalletRepositoryImpl(database)
    private val orders = OrderRepositoryImpl(database)
    private val events = OrderEventRepositoryImpl(database)
    private val commissions = CommissionRepositoryImpl(database)
    private val engineRows = EngineSettingsRepositoryImpl(database)
    private val routingLog = RoutingLogRepositoryImpl(database)
    private val transactor = DatabaseTransactor(database)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val phone = PhoneNumber.parse("+254712345678")!!

    @BeforeTest
    fun clean() =
        runBlocking<Unit> {
            TestPostgres.truncate("ramp")
            RampExpiry(database).run()
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

    private fun execute(sql: String) {
        TestPostgres.superuser { connection -> connection.createStatement().use { it.executeUpdate(sql) } }
    }

    private suspend fun merchant(name: String = "Crynce"): Merchant {
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
        return merchant
    }

    private fun order(
        merchantId: MerchantId,
        at: Instant = now,
        direction: Direction = Direction.ON_RAMP,
        network: Network = TESTNET,
        attempt: Int = 1,
        quoteId: QuoteId = QuoteId.new(),
    ) = Order(
        orderId = RampOrderId.new(),
        userId = UserId.new(),
        merchantId = merchantId,
        quoteId = quoteId,
        network = network,
        direction = direction,
        state = OrderState.RESERVED,
        blockchain = if (network == TESTNET) CHAIN else "BASE",
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

    /** An order taken through to `COMPLETED`, each step [step] seconds after the one before. */
    private suspend fun completed(
        merchantId: MerchantId,
        at: Instant,
        step: Long,
    ): Order {
        val order = order(merchantId, at)
        assertTrue(orders.insert(order))
        val id = order.orderId
        orders.move(id, OrderState.PAYMENT_REQUESTED, setOf(OrderState.RESERVED), OrderPatch(), at)
        assertTrue(orders.markPaid(id, "R${id.toString().takeLast(8)}", at.plusSeconds(step)) is PaidOutcome.Moved)
        orders.move(id, OrderState.RELEASING, setOf(OrderState.PAID), OrderPatch(), at.plusSeconds(step + 1))
        return orders.move(
            id,
            OrderState.COMPLETED,
            setOf(OrderState.RELEASING),
            OrderPatch(),
            at.plusSeconds(2 * step),
        )!!
    }

    /** An order that ended [state] for [reason] at [at]. */
    private suspend fun ended(
        merchantId: MerchantId,
        state: OrderState,
        reason: String,
        at: Instant,
        direction: Direction = Direction.ON_RAMP,
        network: Network = TESTNET,
    ): Order {
        val order = order(merchantId, at.minusSeconds(60), direction, network)
        assertTrue(orders.insert(order))
        return orders.move(order.orderId, state, OrderState.AWAITING_PAYMENT, OrderPatch(failureReason = reason), at)!!
    }

    // The timeline

    @Test
    fun `every insert and every move writes the order's timeline, and a move that doesn't happen writes nothing`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val order = order(merchant.merchantId)
            assertTrue(orders.insert(order))
            assertFalse(orders.insert(order.copy(orderId = RampOrderId.new())), "the same quote and attempt again")
            assertEquals(1, count("SELECT count(*) FROM ramp.order_events"), "a refused insert leaves no event")

            val id = order.orderId
            orders.move(id, OrderState.PAYMENT_REQUESTED, setOf(OrderState.RESERVED), OrderPatch(), now.plusSeconds(1))
            assertNull(orders.move(id, OrderState.PAYMENT_REQUESTED, setOf(OrderState.RESERVED), OrderPatch(), now))
            assertTrue(orders.markPaid(id, "RECEIPT1", now.plusSeconds(30)) is PaidOutcome.Moved)
            assertEquals(PaidOutcome.AlreadyMoved, orders.markPaid(id, "RECEIPT1", now.plusSeconds(31)))
            val review = OrderPatch(failureReason = "release_failed")
            orders.move(id, OrderState.REVIEW, setOf(OrderState.PAID), review, now.plusSeconds(40))

            val timeline = events.timeline(id)
            assertEquals(
                listOf(
                    Triple(OrderState.RESERVED, now, null),
                    Triple(OrderState.PAYMENT_REQUESTED, now.plusSeconds(1), null),
                    Triple(OrderState.PAID, now.plusSeconds(30), null),
                    Triple(OrderState.REVIEW, now.plusSeconds(40), "release_failed"),
                ),
                timeline.map { Triple(it.state, it.at, it.detail) },
            )
            assertEquals(
                4,
                count(
                    "SELECT count(*) FROM ramp.order_events WHERE merchant_id = '${merchant.merchantId}' " +
                        "AND network = 'TESTNET' AND direction = 'ON_RAMP'",
                ),
                "each event carries what the statistics filter on",
            )
            assertTrue(events.timeline(RampOrderId.new()).isEmpty())
        }

    @Test
    fun `a move rolled back with its transaction takes its event with it`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val order = order(merchant.merchantId)
            assertTrue(orders.insert(order))

            assertFailsWith<IllegalStateException> {
                transactor.run("test.rolled-back") {
                    orders.move(order.orderId, OrderState.FAILED, OrderState.AWAITING_PAYMENT, OrderPatch(), now)
                    error("the rest of the transaction fails")
                }
            }

            assertEquals(OrderState.RESERVED, orders.find(order.orderId)!!.state)
            assertEquals(listOf(OrderState.RESERVED), events.timeline(order.orderId).map { it.state })
        }

    @Test
    fun `a merchant's counted orders come back newest first, with handling time from the verified payment`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val other = merchant("Bidii")
            val id = merchant.merchantId
            val quick = completed(id, now.minusSeconds(4_000), step = 10)
            val slow = completed(id, now.minusSeconds(3_000), step = 300)
            val lapsed = ended(id, OrderState.FAILED, "payment_failed", now.minusSeconds(2_000))
            val refused = ended(id, OrderState.FAILED, "prompt_refused", now.minusSeconds(1_000))
            // not this merchant's, not this direction's, not this network's, too old, and not finished
            completed(other.merchantId, now.minusSeconds(500), step = 10)
            ended(id, OrderState.FAILED, "payout_failed", now.minusSeconds(400), Direction.OFF_RAMP)
            ended(id, OrderState.FAILED, "prompt_refused", now.minusSeconds(300), network = Network.MAINNET)
            ended(id, OrderState.FAILED, "prompt_refused", now.minus(Duration.ofDays(40)))
            assertTrue(orders.insert(order(id, now.minusSeconds(100))))

            val since = now.minus(Duration.ofDays(30))
            val counted = events.counted(id, TESTNET, Direction.ON_RAMP, since, 50)

            assertEquals(listOf(refused.orderId, slow.orderId, quick.orderId), counted.map { it.orderId })
            assertEquals(listOf("prompt_refused", null, null), counted.map { it.reason })
            assertEquals(
                listOf(null, 300L, 10L),
                counted.map { it.handlingSeconds },
                "from PAID to COMPLETED; an order that never completed has none",
            )
            assertEquals(1, events.notCounted(id, TESTNET, Direction.ON_RAMP, since), "the buyer's lapsed prompt")
            assertTrue(counted.none { it.orderId == lapsed.orderId }, "which is never in the window")

            val stats = Stats.of(counted, events.notCounted(id, TESTNET, Direction.ON_RAMP, since))
            assertEquals(4, stats.finished)
            assertEquals(2 to 1, stats.completed to stats.merchantFailures)
            assertEquals(1, stats.notCounted)
            assertEquals(10L to 300L, stats.medianHandlingSeconds to stats.p90HandlingSeconds)

            assertEquals(
                listOf(refused.orderId, slow.orderId),
                events.counted(id, TESTNET, Direction.ON_RAMP, since, 2).map { it.orderId },
                "the last two that count: the lapsed prompt between them takes no place",
            )
            val selling = events.counted(id, TESTNET, Direction.OFF_RAMP, since, 50)
            assertEquals(listOf("payout_failed"), selling.map { it.reason })
            assertTrue(events.counted(id, TESTNET, Direction.ON_RAMP, now.plusSeconds(1), 50).isEmpty())
            assertEquals(0, events.notCounted(id, TESTNET, Direction.OFF_RAMP, since))
        }

    @Test
    fun `completed trades are counted from the commissions, per network`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val id = merchant.merchantId
            assertEquals(0, commissions.completedCount(id, TESTNET))

            repeat(3) {
                val done = completed(id, now.minusSeconds(1_000L * (it + 1)), step = 10)
                assertTrue(commissions.insert(done, FeeSplit(1_820, 780, 60_000), now))
            }

            assertEquals(3, commissions.completedCount(id, TESTNET))
            assertEquals(0, commissions.completedCount(id, Network.MAINNET))
            assertEquals(0, commissions.completedCount(MerchantId.new(), TESTNET))
        }

    // What a merchant is and offers

    @Test
    fun `a merchant starts as an individual who takes purchases only, and staff and the merchant change it`() =
        runBlocking<Unit> {
            val merchant = merchant()
            val id = merchant.merchantId
            val fresh = merchants.find(id)!!
            assertEquals(MerchantType.INDIVIDUAL to null, fresh.merchantType to fresh.orderLimitMinor)
            assertEquals(true to false, fresh.offersOnramp to fresh.offersOfframp)

            assertTrue(merchants.setTier(id, MerchantType.BUSINESS, 5_000_000))
            merchants.setOffers(id, onRamp = false, offRamp = true)

            val changed = merchants.find(id)!!
            assertEquals(MerchantType.BUSINESS to 5_000_000L, changed.merchantType to changed.orderLimitMinor)
            assertEquals(false to true, changed.offersOnramp to changed.offersOfframp)
            assertEquals(changed, merchants.pool(TESTNET, CHAIN, 10).single().merchant, "the pool reads them too")
            assertEquals(changed, transactor.run("test.lock") { merchants.lock(id) }, "and so does the lock")

            assertTrue(merchants.setTier(id, MerchantType.INDIVIDUAL, null))
            assertNull(merchants.find(id)!!.orderLimitMinor, "back to the type's default")
            assertFalse(merchants.setTier(MerchantId.new(), MerchantType.BUSINESS, null), "nobody there")

            assertFailsWith<SQLException>("only a type the code knows") {
                execute("UPDATE ramp.merchants SET merchant_type = 'CORPORATE'")
            }
            assertFailsWith<SQLException>("a limit is above zero or absent") {
                execute("UPDATE ramp.merchants SET order_limit_minor = 0")
            }
        }

    // The settings document

    @Test
    fun `engine settings are seeded once, changed by version, and every version is kept`() =
        runBlocking<Unit> {
            val alerts = RecordingAlerts()
            val book = EngineBook(engineRows, alerts, TestClock(now), Duration.ZERO)

            val seeded = book.of(TESTNET, Direction.ON_RAMP)

            assertEquals(1L to EngineSettings.defaults(TESTNET), seeded.version to seeded.settings)
            assertEquals(EngineMode.SHADOW, book.of(Network.MAINNET, Direction.ON_RAMP).settings.mode)
            assertEquals(4, count("SELECT count(*) FROM ramp.engine_settings"), "two networks, two directions")
            assertEquals(4, count("SELECT count(*) FROM ramp.engine_settings_history"), "the seeds are versions too")
            engineRows.seed(seeded.copy(settings = seeded.settings.copy(alpha = 4.0)))
            assertEquals(
                1.0,
                engineRows
                    .load()
                    .getValue(TESTNET to Direction.ON_RAMP)
                    .settings.alpha,
                "seeded once",
            )

            val wanted = seeded.settings.copy(mode = EngineMode.SHADOW, alpha = 2.0, smallOrderMinor = 500_000)
            val after =
                engineRows.update(
                    TESTNET,
                    Direction.ON_RAMP,
                    wanted,
                    1,
                    "staff-1",
                    "try it",
                    now.plusSeconds(5),
                )

            assertNotNull(after)
            assertEquals(2L to wanted, after.version to after.settings)
            assertEquals(
                Triple("staff-1", "try it", now.plusSeconds(5)),
                Triple(after.updatedBy, after.reason, after.updatedAt),
            )
            assertEquals(after, engineRows.load().getValue(TESTNET to Direction.ON_RAMP))
            assertEquals(
                EngineSettings.defaults(TESTNET),
                engineRows.load().getValue(TESTNET to Direction.OFF_RAMP).settings,
                "the other direction is its own row",
            )
            assertNull(
                engineRows.update(TESTNET, Direction.ON_RAMP, wanted.copy(alpha = 3.0), 1, "staff-2", "stale", now),
                "the version it was read at is gone",
            )
            val third =
                engineRows.update(
                    TESTNET,
                    Direction.ON_RAMP,
                    wanted.copy(alpha = 3.0),
                    null,
                    "staff-2",
                    "x",
                    now,
                )
            assertEquals(3L, third!!.version, "with no version, over whatever is there")

            assertEquals(1.0, engineRows.version(TESTNET, Direction.ON_RAMP, 1)!!.settings.alpha)
            val second = engineRows.version(TESTNET, Direction.ON_RAMP, 2)!!
            assertEquals(2.0 to "try it", second.settings.alpha to second.reason)
            assertEquals(3.0, engineRows.version(TESTNET, Direction.ON_RAMP, 3)!!.settings.alpha)
            assertNull(engineRows.version(TESTNET, Direction.ON_RAMP, 4))
            assertEquals(6, count("SELECT count(*) FROM ramp.engine_settings_history"))
        }

    @Test
    fun `a document from an older build still opens - a missing number is its default, an unknown key is ignored`() =
        runBlocking<Unit> {
            EngineBook(engineRows, RecordingAlerts(), TestClock(now), Duration.ZERO).of(TESTNET, Direction.ON_RAMP)
            execute(
                "UPDATE ramp.engine_settings SET document = " +
                    "'{\"mode\":\"ROUND_ROBIN\",\"alpha\":2.5,\"somethingRetired\":7}' " +
                    "WHERE network = 'TESTNET' AND direction = 'ON_RAMP'",
            )
            execute(
                "UPDATE ramp.engine_settings SET document = '{}' WHERE network = 'MAINNET' AND direction = 'ON_RAMP'",
            )

            val rows = engineRows.load()

            val old = rows.getValue(TESTNET to Direction.ON_RAMP).settings
            assertEquals(EngineSettings.defaults(TESTNET).copy(mode = EngineMode.ROUND_ROBIN, alpha = 2.5), old)
            val empty = rows.getValue(Network.MAINNET to Direction.ON_RAMP).settings
            assertEquals(EngineSettings.defaults(Network.MAINNET), empty, "even the mode: the network's own default")
        }

    // The routing log

    private fun facts(merchantId: MerchantId, floatMinor: Long? = 100_000_000) =
        CandidateFacts(
            merchantId = merchantId,
            approved = true,
            kycCleared = true,
            online = true,
            hasKeys = true,
            paymentsIssue = false,
            offersDirection = true,
            limitMinor = 3_000_000,
            floatMinor = floatMinor,
            lastAssignedAt = now.minusSeconds(900),
            completedTrades = 4,
            stats = MerchantStats(6, 4, 1, 0, 0, 12, 40),
        )

    private fun decision(
        at: Instant,
        pool: List<CandidateFacts>,
        network: Network = TESTNET,
        direction: Direction = Direction.ON_RAMP,
    ): RoutingDecision {
        val order = RoutingOrder(direction, 130_000, 9_800_000)
        val plan = Router.plan(order, pool, EngineSettings.defaults(TESTNET), now, Random(3))
        return RoutingDecision(
            decisionId = Uuid7.generate(at),
            quoteId = QuoteId.new(),
            orderId = null,
            merchantId = null,
            network = network,
            order = order,
            settingsVersion = 7,
            plan = plan,
            outcome = RoutingOutcome.PENDING,
            steps = emptyList(),
            createdAt = at,
        )
    }

    @Test
    fun `a routing decision reads back as it was written - inputs, scores, exclusions, order and steps`() =
        runBlocking<Unit> {
            val a = MerchantId.new()
            val b = MerchantId.new()
            val short = MerchantId.new()
            val written = decision(now, listOf(facts(a), facts(b), facts(short, floatMinor = 1_000)))
            routingLog.insert(written)

            val pending = routingLog.find(written.decisionId)!!
            assertEquals(written, pending, "every input, score and exclusion, the ranking and the draw")
            assertEquals(
                Exclusion.FLOAT_SHORT,
                pending.plan.candidates
                    .single { it.facts.merchantId == short }
                    .exclusion,
            )
            assertEquals(setOf(a, b), pending.plan.ranked.toSet())
            assertEquals(pending.plan.ranked, pending.plan.engineRanked)

            val orderId = RampOrderId.new()
            val steps =
                listOf(
                    RoutingStep(a, RampOrderId.new(), "prompt_refused", now.plusSeconds(1)),
                    RoutingStep(b, orderId, "prompted", now.plusSeconds(2)),
                )
            routingLog.finish(written.decisionId, orderId, b, RoutingOutcome.ROUTED, steps)

            val finished = routingLog.find(written.decisionId)!!
            assertEquals(RoutingOutcome.ROUTED, finished.outcome)
            assertEquals(orderId to b, finished.orderId to finished.merchantId)
            assertEquals(steps, finished.steps)
            assertEquals(written.plan, finished.plan, "finishing leaves the decision itself alone")
            assertNull(routingLog.find(Uuid7.generate(now)))
        }

    @Test
    fun `decisions are listed newest first by keyset, narrowed by merchant, quote and time, and pruned in chunks`() =
        runBlocking<Unit> {
            val a = MerchantId.new()
            val b = MerchantId.new()
            val written =
                (0 until 5).map { index ->
                    val row = decision(now.minusSeconds(100L * index), listOf(facts(a), facts(b)))
                    routingLog.insert(row)
                    routingLog.finish(
                        row.decisionId,
                        RampOrderId.new(),
                        if (index % 2 ==
                            0
                        ) {
                            a
                        } else {
                            b
                        },
                        RoutingOutcome.ROUTED,
                        emptyList(),
                    )
                    row
                }
            val elsewhere = decision(now, listOf(facts(a)), network = Network.MAINNET)
            routingLog.insert(elsewhere)
            val all = RoutingFilter(TESTNET)

            val firstPage = routingLog.list(all, null, 2)
            assertEquals(written.take(2).map { it.decisionId }, firstPage.map { it.decisionId })
            val cursor = PageCursor(firstPage.last().createdAt, firstPage.last().decisionId)
            val rest = routingLog.list(all, cursor, 10)
            assertEquals(written.drop(2).map { it.decisionId }, rest.map { it.decisionId })

            assertEquals(3, routingLog.list(all.copy(merchantId = a), null, 10).size, "the ones that ended with A")
            assertEquals(2, routingLog.list(all.copy(merchantId = b), null, 10).size)
            assertEquals(
                listOf(written[3].decisionId),
                routingLog.list(all.copy(quoteId = written[3].quoteId), null, 10).map { it.decisionId },
            )
            val window = all.copy(from = now.minusSeconds(250), to = now.minusSeconds(50))
            assertEquals(
                listOf(written[1].decisionId, written[2].decisionId),
                routingLog.list(window, null, 10).map { it.decisionId },
                "from inclusive, to exclusive",
            )
            assertEquals(
                listOf(elsewhere.decisionId),
                routingLog.list(RoutingFilter(Network.MAINNET), null, 10).map {
                    it.decisionId
                },
            )

            val before = now.minusSeconds(150)
            assertEquals(0, routingLog.prune(TESTNET, Direction.OFF_RAMP, before, 10), "another direction's")
            assertEquals(2, routingLog.prune(TESTNET, Direction.ON_RAMP, before, 2), "a chunk")
            assertEquals(1, routingLog.prune(TESTNET, Direction.ON_RAMP, before, 2), "the rest")
            assertEquals(0, routingLog.prune(TESTNET, Direction.ON_RAMP, before, 2))
            assertEquals(written.take(2).map { it.decisionId }, routingLog.list(all, null, 10).map { it.decisionId })
            assertEquals(1, routingLog.list(RoutingFilter(Network.MAINNET), null, 10).size, "the other network's stays")
        }
}
