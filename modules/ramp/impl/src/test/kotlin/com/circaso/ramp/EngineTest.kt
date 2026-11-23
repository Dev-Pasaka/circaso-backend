package com.circaso.ramp

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.integrations.api.CollectionState
import com.circaso.integrations.api.CollectionStatus
import com.circaso.integrations.api.IntegrationError
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.QuoteId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.EngineMode
import com.circaso.ramp.domain.models.EngineSettings
import com.circaso.ramp.domain.models.EngineSettingsVersion
import com.circaso.ramp.domain.models.Exclusion
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantType
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.RoutingOrder
import com.circaso.ramp.domain.models.RoutingOutcome
import com.circaso.ramp.domain.models.RoutingQuery
import com.circaso.ramp.domain.models.SimulationCommand
import com.circaso.ramp.domain.support.OrderFlow
import com.circaso.ramp.domain.support.Prompted
import com.circaso.ramp.domain.support.Router
import com.circaso.ramp.domain.support.RoutingCommand
import com.circaso.shared.ErrorKind
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.test.runTest
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The merchant engine in the purchase's own flow (decision 2026-11-23): who is eligible and why not, what each mode
 * picks, the failover (a timeout above all), the routing log, the statistics an order leaves behind, the settings
 * staff change, the simulation, and what a merchant is shown.
 */
class EngineTest {
    private val ramp = Harness()
    private var users = 0

    private fun <T> Resource<T>.error() = (this as Resource.Error).error

    private fun <T> Resource<T>.data() = (this as Resource.Success).data

    private val crynce get() = ramp.crynce.merchantId

    /** A new buyer with their own number: a user has one open order at a time. */
    private fun buyer(): Pair<UserId, PhoneNumber> {
        users += 1
        val number = "+2547${(10_000_000 + users)}"
        return ramp.user(number) to PhoneNumber.parse(number)!!
    }

    private suspend fun buyAs(shillings: Long = 1_300): Resource<Order> {
        val (user, phone) = buyer()
        return when (val bought = ramp.buy(user, phone, shillings)) {
            is Resource.Success -> Resource.success(bought.data.order)
            is Resource.Error -> bought
        }
    }

    private fun active(change: (EngineSettings) -> EngineSettings = { it }) =
        ramp.engineSettings { change(it.copy(mode = EngineMode.ACTIVE)) }

    /** A merchant with so much float that the size of an order never matters. */
    private fun deep(name: String): Merchant = ramp.merchant(name, floatMinor = 1_000_000_000_000)

    private fun noAnswer(name: String, cause: Throwable = SocketTimeoutException("timed out")) {
        ramp.collections.unanswered["$name-user"] = cause
    }

    // Eligibility, in the flow

    @Test
    fun `the routing log says who was looked at, why each was out, how the rest scored and who took it`() =
        runTest {
            active()
            val bidii = ramp.merchant("Bidii", floatMinor = 100_000_000)
            val asleep = ramp.merchant("Asleep", floatMinor = 100_000_000)
            ramp.merchants.setOnlineUntil(asleep.merchantId, null)
            val poor = ramp.merchant("Poor", floatMinor = 1_000_000)
            val locked = ramp.merchant("Locked", floatMinor = 100_000_000)
            ramp.sessions.keys.remove(locked.merchantId)

            val order = buyAs().data()

            val decision = ramp.lastRouting()
            assertEquals(order.quoteId, decision.quoteId)
            assertEquals(TESTNET, decision.network)
            assertEquals(RoutingOrder(Direction.ON_RAMP, 130_000, 9_800_000), decision.order)
            assertEquals(1L, decision.settingsVersion)
            assertEquals(EngineMode.ACTIVE, decision.plan.mode)
            assertEquals(ramp.clock.instant(), decision.createdAt)

            val byMerchant = decision.plan.candidates.associateBy { it.facts.merchantId }
            assertEquals(5, byMerchant.size, "everyone with a wallet on the chain")
            assertEquals(Exclusion.OFFLINE, byMerchant.getValue(asleep.merchantId).exclusion)
            assertEquals(Exclusion.FLOAT_SHORT, byMerchant.getValue(poor.merchantId).exclusion)
            assertEquals(
                1_000_000L,
                byMerchant.getValue(poor.merchantId).facts.floatMinor,
                "with the float that fell short",
            )
            assertEquals(Exclusion.NO_KEYS, byMerchant.getValue(locked.merchantId).exclusion)
            listOf(crynce, bidii.merchantId).forEach { id ->
                val eligible = byMerchant.getValue(id)
                assertNull(eligible.exclusion)
                assertEquals(100_000_000L, eligible.facts.floatMinor)
                assertEquals(3_000_000L, eligible.facts.limitMinor, "an individual's KES 30 000")
                assertNotNull(eligible.facts.stats, "the record it was scored on")
                val score = assertNotNull(eligible.score)
                assertTrue(score.weight > 0)
                assertEquals(1.0, score.hunger, "never assigned: the whole boost")
                assertEquals(0.5, score.newcomer, "no trades yet")
            }

            assertEquals(setOf(crynce, bidii.merchantId), decision.plan.ranked.toSet())
            assertEquals(decision.plan.ranked, decision.plan.engineRanked, "active: the draw is the order")
            assertEquals(decision.plan.ranked.first(), order.merchantId, "the pick took it")
            assertEquals(RoutingOutcome.ROUTED, decision.outcome)
            assertEquals(order.merchantId to order.orderId, decision.merchantId to decision.orderId)
            val step = decision.steps.single()
            assertEquals(
                Triple(order.merchantId, order.orderId, "prompted"),
                Triple(step.merchantId, step.orderId, step.result),
            )
        }

    @Test
    fun `nobody eligible is logged too, with the reason for each`() =
        runTest {
            ramp.merchants.setOnlineUntil(crynce, null)

            assertEquals(RampError.NoMerchantAvailable, buyAs().error())

            val decision = ramp.lastRouting()
            assertEquals(RoutingOutcome.NO_CANDIDATE, decision.outcome)
            assertEquals(
                Exclusion.OFFLINE,
                decision.plan.candidates
                    .single()
                    .exclusion,
            )
            assertTrue(decision.plan.ranked.isEmpty())
            assertTrue(decision.steps.isEmpty())
            assertNull(decision.merchantId)
            assertTrue(ramp.orders.rows.isEmpty())
        }

    @Test
    fun `an order above a merchant's limit never reaches them - their own limit first, else their type's`() =
        runTest {
            // Staff give Crynce a limit of their own: KES 1 000, below this order.
            ramp.setTier(crynce, MerchantType.INDIVIDUAL, 100_000).data()
            assertEquals(RampError.NoMerchantAvailable, buyAs(1_300).error())
            assertEquals(
                Exclusion.OVER_LIMIT,
                ramp
                    .lastRouting()
                    .plan.candidates
                    .single()
                    .exclusion,
            )
            assertIs<Resource.Success<*>>(buyAs(1_000), "at the limit is within it")

            // Without one, the type decides: individuals KES 1 000 here, businesses KES 5 000.
            ramp.setTier(crynce, MerchantType.INDIVIDUAL, null).data()
            ramp.engineSettings { it.copy(individualLimitMinor = 100_000, businessLimitMinor = 500_000) }
            assertEquals(RampError.NoMerchantAvailable, buyAs(1_300).error())

            val record = ramp.setTier(crynce, MerchantType.BUSINESS, null).data()
            assertEquals(MerchantType.BUSINESS to null, record.merchant.merchantType to record.merchant.orderLimitMinor)
            assertEquals(crynce, buyAs(1_300).data().merchantId)

            assertEquals(
                RampError.MerchantNotFound,
                ramp.setTier(MerchantId.new(), MerchantType.BUSINESS, null).error(),
            )
            val invalid = ramp.setTier(crynce, MerchantType.BUSINESS, 0).error()
            assertEquals("ramp.settings_invalid", invalid.code)
        }

    @Test
    fun `a merchant who switched buying off gets no order, and one left out stays as it is`() =
        runTest {
            val before = ramp.setOffers(crynce, TESTNET, null, true).data()
            assertTrue(before.merchant.offersOnramp, "buying is on to start with, and wasn't named")
            assertTrue(before.merchant.offersOfframp)

            ramp.setOffers(crynce, TESTNET, false, null).data()

            assertEquals(RampError.NoMerchantAvailable, buyAs().error())
            assertEquals(
                Exclusion.DIRECTION_OFF,
                ramp
                    .lastRouting()
                    .plan.candidates
                    .single()
                    .exclusion,
            )
            assertFalse(ramp.status(ramp.amina, TESTNET).data().merchantOnline, "nobody is selling")
            assertTrue(ramp.merchants.find(crynce)!!.offersOfframp, "the other switch was left alone")

            ramp.setOffers(crynce, TESTNET, true, null).data()
            assertEquals(crynce, buyAs().data().merchantId)
        }

    @Test
    fun `under the merchant's row lock everything is checked again`() =
        runTest {
            val need = RoutingOrder(Direction.ON_RAMP, 130_000, 9_800_000)
            val settings = ramp.book.of(TESTNET)
            val template = ramp.prompted()
            ramp.collections.fail(template)
            ramp.callback(template)

            suspend fun reserveAfter(change: suspend () -> Unit): Resource<Order> {
                val routing = ramp.engine.route(RoutingCommand(QuoteId.new(), TESTNET, CHAIN, need), settings)
                assertEquals(listOf(crynce), routing.plan.ranked, "eligible when the facts were read")
                change()
                val (user, _) = buyer()
                return ramp.engine.reserve(routing, routing.ranked.single(), settings) {
                    template.copy(
                        orderId = RampOrderId.new(),
                        quoteId = QuoteId.new(),
                        userId = user,
                        state = OrderState.RESERVED,
                    )
                }
            }

            assertEquals(
                RampError.NoMerchantAvailable,
                reserveAfter { ramp.merchants.setOffers(crynce, false, false) }.error(),
                "switched the direction off meanwhile",
            )
            ramp.merchants.setOffers(crynce, true, false)
            assertEquals(
                RampError.NoMerchantAvailable,
                reserveAfter { ramp.merchants.setTier(crynce, MerchantType.INDIVIDUAL, 100_000) }.error(),
                "their limit was lowered meanwhile",
            )
            ramp.merchants.setTier(crynce, MerchantType.INDIVIDUAL, null)
            assertEquals(
                RampError.NoMerchantAvailable,
                reserveAfter { ramp.merchants.setOnlineUntil(crynce, null) }.error(),
                "went offline meanwhile",
            )
            ramp.merchants.setOnlineUntil(crynce, ramp.clock.instant().plus(Duration.ofHours(8)))
            assertEquals(
                RampError.NoMerchantAvailable,
                reserveAfter {
                    // another order took the float in between: 100 USDC, 95 of it now promised
                    ramp.reservations.rows[RampOrderId.new()] =
                        MemoryReservations.Row(crynce, TESTNET, 95_000_000, "OPEN")
                }.error(),
                "the float was promised to someone else meanwhile",
            )
            ramp.reservations.rows.clear()
            assertIs<Resource.Success<*>>(reserveAfter { }, "and nothing changed: reserved")
        }

    // Modes

    @Test
    fun `in shadow the engine's draw is logged and round robin still picks`() =
        runTest {
            ramp.engineSettings { it.copy(mode = EngineMode.SHADOW) }
            val bidii = deep("Bidii")
            val first = buyAs().data()
            assertEquals(crynce, first.merchantId, "neither was ever assigned: the first in line")

            ramp.clock.advance(60)
            val second = buyAs().data()

            assertEquals(bidii.merchantId, second.merchantId, "round robin: the one never assigned")
            val decision = ramp.lastRouting()
            assertEquals(EngineMode.SHADOW, decision.plan.mode)
            assertEquals(listOf(bidii.merchantId, crynce), decision.plan.ranked)
            assertEquals(
                setOf(bidii.merchantId, crynce),
                decision.plan.engineRanked!!.toSet(),
                "what the engine would do",
            )
            assertTrue(decision.plan.candidates.all { it.score != null }, "scored, though the score picked nobody")
        }

    @Test
    fun `in active the draw picks - not round robin - and in round robin nothing is scored`() =
        runTest {
            // Equal weights: no hunger, no newcomer boost, floats far above any order.
            active { it.copy(hungerMax = 0.0, newcomerMax = 0.0) }
            ramp.treasury.balances[ramp.walletOf(ramp.crynce).custodyWalletId] = 1_000_000_000_000
            val bidii = deep("Bidii")
            var againstTheTurn = 0
            val picks = mutableMapOf<MerchantId, Int>()

            repeat(40) {
                ramp.clock.advance(1)
                val order = buyAs().data()
                val decision = ramp.lastRouting()
                assertEquals(decision.plan.engineRanked!!.first(), order.merchantId, "the draw's first is the pick")
                val turn = Router.roundRobin(decision.plan.candidates.filter { it.isEligible }).first()
                if (turn != order.merchantId) againstTheTurn += 1
                picks.merge(order.merchantId, 1, Int::plus)
            }

            assertTrue(againstTheTurn > 0, "forty draws never once differed from taking turns")
            assertEquals(setOf(crynce, bidii.merchantId), picks.keys, "and both got orders: $picks")

            ramp.engineSettings { it.copy(mode = EngineMode.ROUND_ROBIN) }
            buyAs().data()
            val plain = ramp.lastRouting()
            assertNull(plain.plan.engineRanked)
            assertTrue(plain.plan.candidates.all { it.score == null && it.facts.stats == null })
        }

    @Test
    fun `a better record gets more orders, and a worse one is never starved`() =
        runTest {
            active { it.copy(hungerMax = 0.0, newcomerMax = 0.0, priorStrength = 0.0) }
            ramp.treasury.balances[ramp.walletOf(ramp.crynce).custodyWalletId] = 1_000_000_000_000
            val bidii = deep("Bidii")
            // Crynce: three completed trades. Bidii: one completed, three refused by their provider.
            ramp.engineSettings { it.copy(mode = EngineMode.ROUND_ROBIN) }
            ramp.merchants.setOnlineUntil(bidii.merchantId, null)
            repeat(3) { complete() }
            ramp.merchants.setOnlineUntil(bidii.merchantId, ramp.clock.instant().plus(Duration.ofHours(8)))
            ramp.merchants.setOnlineUntil(crynce, null)
            complete()
            ramp.collections.refusals["Bidii-user"] = IntegrationError.Rejected("no")
            repeat(3) { assertEquals(RampError.MerchantUnavailable, buyAs().error()) }
            ramp.collections.refusals.clear()
            ramp.merchants.setOnlineUntil(crynce, ramp.clock.instant().plus(Duration.ofHours(8)))
            active { it.copy(hungerMax = 0.0, newcomerMax = 0.0, priorStrength = 0.0) }

            val picks = mutableMapOf<MerchantId, Int>()
            repeat(200) {
                val (user, phone) = buyer()
                val order = ramp.prompted(user, phone)
                picks.merge(order.merchantId, 1, Int::plus)
                // the buyer lets it lapse: their doing, so neither record moves
                ramp.collections.fail(order)
                ramp.callback(order)
            }

            val scores =
                ramp
                    .lastRouting()
                    .plan.candidates
                    .associate { it.facts.merchantId to it.score!! }
            assertEquals(1.0, scores.getValue(crynce).completion)
            assertEquals(
                0.25,
                scores.getValue(bidii.merchantId).completion,
                "1 of 4 counted; the 200 lapsed ones aren't",
            )
            assertEquals(0.75, scores.getValue(bidii.merchantId).failure)
            assertTrue(scores.getValue(crynce).weight > scores.getValue(bidii.merchantId).weight)
            val share = picks.getValue(crynce) / 200.0
            val expected = scores.getValue(crynce).weight / scores.values.sumOf { it.weight }
            assertTrue(abs(share - expected) < 0.1, "Crynce got $share of the orders, the weights say $expected")
            assertTrue(picks.getValue(bidii.merchantId) > 0, "the worse record still gets orders")
        }

    private suspend fun complete(): Order {
        val (user, phone) = buyer()
        val order = ramp.paid(user, phone)
        ramp.clock.advance(20)
        ramp.release()
        ramp.treasury.settleAll()
        ramp.release()
        ramp.clock.advance(5)
        return ramp.now(order)
    }

    // Failover

    @Test
    fun `failover stops at the settings' attempts`() =
        runTest {
            listOf("Bidii", "Chui", "Duma").forEach { ramp.merchant(it, floatMinor = 100_000_000) }
            listOf("Crynce", "Bidii", "Chui", "Duma").forEach {
                ramp.collections.refusals["$it-user"] = IntegrationError.Rejected("The channel is not active.")
            }

            val error = buyAs().error()

            assertEquals(RampError.MerchantUnavailable, error)
            assertEquals(3, ramp.orders.rows.size, "three merchants tried, the fourth never asked")
            assertEquals(
                listOf(1, 2, 3),
                ramp.orders.rows.values
                    .map { it.attempt },
            )
            val decision = ramp.lastRouting()
            assertEquals(RoutingOutcome.EXHAUSTED, decision.outcome)
            assertEquals(4, decision.plan.ranked.size, "though four could have taken it")
            assertEquals(List(3) { OrderFlow.PROMPT_REFUSED }, decision.steps.map { it.result })
            assertNull(decision.merchantId)

            ramp.engineSettings { it.copy(maxAttempts = 1) }
            assertEquals(RampError.MerchantUnavailable, buyAs().error())
            assertEquals(1, ramp.lastRouting().steps.size)
        }

    @Test
    fun `a provider that provably never got the request is passed over like a refusal`() =
        runTest {
            val bidii = ramp.merchant("Bidii", floatMinor = 100_000_000)
            noAnswer("Crynce", CircuitOpenException("payhero"))

            val order = buyAs().data()

            assertEquals(bidii.merchantId, order.merchantId)
            assertEquals(2, order.attempt)
            val first =
                ramp.orders.rows.values
                    .single { it.merchantId == crynce }
            assertEquals(OrderState.FAILED to OrderFlow.PROVIDER_UNREACHABLE, first.state to first.failureReason)
            assertTrue(ramp.collections.statusQueries.isEmpty(), "nothing left, so there is nothing to ask about")
            assertEquals(listOf(OrderState.PAYMENT_REQUESTED), ramp.frames.states, "the buyer saw one order only")
            assertEquals(
                listOf(OrderFlow.PROVIDER_UNREACHABLE, "prompted"),
                ramp.lastRouting().steps.map { it.result },
            )

            // nobody reachable at all: the answer is the provider's, a 502
            noAnswer("Bidii", RuntimeException(ConnectException("refused")))
            val error = buyAs().error()
            assertEquals(RampError.CollectionsUnavailable to ErrorKind.UPSTREAM, error to error.kind)
            assertEquals(RoutingOutcome.EXHAUSTED, ramp.lastRouting().outcome)
        }

    @Test
    fun `after a timeout the provider is asked first - a prompt it says is over lets the next merchant be asked`() =
        runTest {
            val bidii = ramp.merchant("Bidii", floatMinor = 100_000_000)
            noAnswer("Crynce")
            ramp.collections.unansweredState = CollectionState.FAILED

            val order = buyAs().data()

            assertEquals(bidii.merchantId, order.merchantId)
            val first =
                ramp.orders.rows.values
                    .single { it.merchantId == crynce }
            assertEquals(OrderState.FAILED to OrderFlow.PROMPT_FAILED, first.state to first.failureReason)
            assertEquals(
                listOf(first.orderId.toString()),
                ramp.collections.statusQueries,
                "the first order's status was asked, by our own reference, before anyone else was prompted",
            )
            assertEquals(1, ramp.collections.prompts.size, "and only then the second merchant's prompt went out")
            assertEquals(listOf(OrderFlow.PROMPT_FAILED, "prompted"), ramp.lastRouting().steps.map { it.result })
        }

    @Test
    fun `after a timeout, a prompt that went out after all is the order's prompt - nobody else is asked`() =
        runTest {
            val bidii = ramp.merchant("Bidii", floatMinor = 100_000_000)
            noAnswer("Crynce")
            ramp.collections.unansweredState = CollectionState.QUEUED

            val order = buyAs().data()

            assertEquals(crynce, order.merchantId)
            assertEquals(OrderState.PAYMENT_REQUESTED, order.state)
            assertEquals(order.orderId.toString(), order.providerReference, "the reference the provider answered to")
            assertTrue(
                ramp.orders.rows.values
                    .none { it.merchantId == bidii.merchantId },
                "no second order",
            )
            assertTrue(ramp.collections.prompts.isEmpty(), "and no second prompt")
            assertEquals(RoutingOutcome.ROUTED, ramp.lastRouting().outcome)

            // the buyer pays it: verified against the provider by that same reference
            ramp.collections.byOurReference[order.orderId.toString()] = CollectionState.SUCCESS
            ramp.callback(order)
            assertEquals(OrderState.PAID, ramp.now(order).state)
        }

    @Test
    fun `after a timeout nobody can explain, the purchase stops - a buyer never gets two prompts for one quote`() =
        runTest {
            val bidii = ramp.merchant("Bidii", floatMinor = 100_000_000)
            noAnswer("Crynce")

            val error = buyAs().error()

            assertEquals(RampError.CollectionsUnavailable, error)
            val order =
                ramp.orders.rows.values
                    .single()
            assertEquals(OrderState.FAILED to OrderFlow.PROMPT_UNCONFIRMED, order.state to order.failureReason)
            assertEquals(crynce, order.merchantId)
            assertTrue(
                ramp.orders.rows.values
                    .none { it.merchantId == bidii.merchantId },
                "Bidii was never asked",
            )
            assertTrue(ramp.collections.prompts.isEmpty())
            assertEquals(listOf(order.orderId.toString()), ramp.collections.statusQueries, "the provider was asked")
            assertTrue(OrderFlow.PROMPT_UNCONFIRMED in ramp.alerts.stuck, "a prompt may be open: people are told")
            assertEquals(0, ramp.reservations.openTotal(crynce, TESTNET), "the float is free")
            val decision = ramp.lastRouting()
            assertEquals(RoutingOutcome.STOPPED, decision.outcome)
            assertEquals(listOf(OrderFlow.PROMPT_UNCONFIRMED), decision.steps.map { it.result })

            // and if that prompt did go out and the provider rings about it, people are told again
            ramp.callback(order)
            assertTrue("callback_after_unconfirmed_prompt" in ramp.alerts.stuck)
            assertEquals(OrderState.FAILED, ramp.now(order).state, "an unsigned callback moves nothing")
        }

    @Test
    fun `an answer about somebody else's reference explains nothing`() =
        runTest {
            ramp.merchant("Bidii", floatMinor = 100_000_000)
            noAnswer("Crynce")
            // The provider answers the status query, but about a payment that doesn't name this order.
            ramp.collections.failure = null
            ramp.collections.unansweredState = null
            val other = UUID.randomUUID().toString()
            ramp.collections.statuses[other] = CollectionStatus(CollectionState.FAILED, null, null, other, null)

            assertEquals(RampError.CollectionsUnavailable, buyAs().error())
            assertEquals(RoutingOutcome.STOPPED, ramp.lastRouting().outcome)
        }

    @Test
    fun `a merchant whose keys are gone when the prompt must go out is passed over`() =
        runTest {
            val reserved = ramp.prompted()
            val again = reserved.copy(orderId = RampOrderId.new(), state = OrderState.RESERVED)
            ramp.orders.rows[again.orderId] = again
            ramp.sessions.keys.remove(crynce)

            val prompted = ramp.flow.requestPayment(again)

            val notSent = assertIs<Prompted.NotSent>(prompted)
            assertEquals(OrderFlow.MERCHANT_OFFLINE to RampError.MerchantUnavailable, notSent.reason to notSent.error)
            assertEquals(OrderState.FAILED, ramp.now(again).state)
        }

    // The timeline and the statistics

    @Test
    fun `every move of an order is in its timeline, with its time and its reason`() =
        runTest {
            val created = ramp.clock.instant()
            val order = ramp.prompted()
            ramp.clock.advance(30)
            ramp.collections.pay(order)
            ramp.callback(order)
            val paid = ramp.clock.instant()
            ramp.clock.advance(7)
            ramp.release()
            ramp.treasury.settleAll()
            ramp.clock.advance(5)
            ramp.release()

            val timeline = ramp.orders.timeline(order.orderId)

            assertEquals(
                listOf(
                    OrderState.RESERVED to created,
                    OrderState.PAYMENT_REQUESTED to created,
                    OrderState.PAID to paid,
                    OrderState.RELEASING to paid.plusSeconds(7),
                    OrderState.COMPLETED to paid.plusSeconds(12),
                ),
                timeline.map { it.state to it.at },
            )

            val (user, phone) = buyer()
            val lapsed = ramp.prompted(user, phone)
            ramp.collections.fail(lapsed)
            ramp.callback(lapsed)
            val ended = ramp.orders.timeline(lapsed.orderId).last()
            assertEquals(OrderState.FAILED to "payment_failed", ended.state to ended.detail)
        }

    @Test
    fun `a merchant's record counts what was down to them - never a buyer who ignored or cancelled the prompt`() =
        runTest {
            // two completed (12 and 40 seconds from the verified payment to the USDC arriving)
            handled(12)
            handled(40)
            // the buyer lets one prompt lapse and cancels another before it is sent
            val (user, phone) = buyer()
            val lapsed = ramp.prompted(user, phone)
            ramp.collections.fail(lapsed)
            ramp.callback(lapsed)
            // the merchant's provider refuses one
            ramp.collections.refusals["Crynce-user"] = IntegrationError.Rejected("The channel is not active.")
            assertEquals(RampError.MerchantUnavailable, buyAs().error())
            ramp.collections.refusals.clear()
            // and one times out with nobody able to say what happened: the provider's, not the merchant's
            noAnswer("Crynce")
            assertEquals(RampError.CollectionsUnavailable, buyAs().error())
            ramp.collections.unanswered.clear()

            val performance = ramp.performance(crynce, TESTNET).data()

            assertEquals(MerchantType.INDIVIDUAL, performance.merchantType)
            val buying = performance.directions.single { it.direction == Direction.ON_RAMP }
            assertTrue(buying.offered)
            assertEquals(3_000_000L, buying.orderLimitMinor)
            assertEquals(5, buying.stats.finished)
            assertEquals(2, buying.stats.completed)
            assertEquals(1, buying.stats.merchantFailures, "the refusal")
            assertEquals(3, buying.stats.counted)
            assertEquals(2, buying.stats.notCounted, "the lapsed prompt and the timeout")
            assertEquals(12L, buying.stats.medianHandlingSeconds)
            assertEquals(40L, buying.stats.p90HandlingSeconds)
            assertEquals(2, buying.trades)
            assertEquals(18, buying.newcomerTradesLeft, "the newcomer boost still applies")

            val selling = performance.directions.single { it.direction == Direction.OFF_RAMP }
            assertFalse(selling.offered, "off until the merchant switches it on")
            assertEquals(0, selling.stats.finished)

            // the engine reads the same record, and the trades fade the newcomer boost
            active()
            buyAs().data()
            val scored =
                ramp
                    .lastRouting()
                    .plan.candidates
                    .single()
            assertEquals(buying.stats, scored.facts.stats)
            assertEquals(2, scored.facts.completedTrades)
            assertEquals(0.5 * (1 - 2 / 20.0), scored.score!!.newcomer)
        }

    private suspend fun handled(seconds: Long) {
        val (user, phone) = buyer()
        ramp.paid(user, phone)
        ramp.release()
        ramp.treasury.settleAll()
        ramp.clock.advance(seconds)
        ramp.release()
    }

    @Test
    fun `the record is the last orders of the window, and only one merchant's`() =
        runTest {
            val bidii = ramp.merchant("Bidii", floatMinor = 100_000_000)
            ramp.merchants.setOnlineUntil(bidii.merchantId, null)
            ramp.collections.refusals["Crynce-user"] = IntegrationError.Rejected("no")
            assertEquals(RampError.MerchantUnavailable, buyAs().error())
            ramp.collections.refusals.clear()
            ramp.clock.advance(60)
            handled(10)
            ramp.clock.advance(60)
            handled(10)

            val all =
                ramp
                    .performance(crynce, TESTNET)
                    .data()
                    .directions
                    .first()
                    .stats
            assertEquals(Triple(3, 2, 1), Triple(all.finished, all.completed, all.merchantFailures))

            ramp.engineSettings { it.copy(statsOrders = 2) }
            val lastTwo =
                ramp
                    .performance(crynce, TESTNET)
                    .data()
                    .directions
                    .first()
                    .stats
            assertEquals(Triple(2, 2, 0), Triple(lastTwo.counted, lastTwo.completed, lastTwo.merchantFailures))

            // fifty buyers let their prompt lapse: none of it is the merchant's record, and none of it hides the record
            repeat(50) {
                val (user, phone) = buyer()
                val lapsed = ramp.prompted(user, phone)
                ramp.collections.fail(lapsed)
                ramp.callback(lapsed)
            }
            ramp.engineSettings { it.copy(statsOrders = 50) }
            val crowded =
                ramp
                    .performance(crynce, TESTNET)
                    .data()
                    .directions
                    .first()
                    .stats
            assertEquals(Triple(3, 2, 1), Triple(crowded.counted, crowded.completed, crowded.merchantFailures))
            assertEquals(50, crowded.notCounted)

            ramp.engineSettings { it.copy(statsOrders = 50, statsDays = 30) }
            ramp.clock.advance(Duration.ofDays(31).seconds)
            val aged =
                ramp
                    .performance(crynce, TESTNET)
                    .data()
                    .directions
                    .first()
            assertEquals(0, aged.stats.finished, "older than the window")
            assertEquals(2, aged.trades, "but trades are counted for ever")

            val other = ramp.performance(bidii.merchantId, TESTNET).data()
            assertTrue(other.directions.all { it.stats.finished == 0 && it.trades == 0 }, "never another merchant's")
            assertEquals(RampError.MerchantNotFound, ramp.performance(MerchantId.new(), TESTNET).error())
        }

    // Settings

    @Test
    fun `engine settings are seeded per network and direction, and a change is a new version with its reason`() =
        runTest {
            val fresh = Harness()
            val testnet = fresh.getEngineSettings(TESTNET, Direction.ON_RAMP).data()
            // (the harness itself puts the testnet's buying on ROUND_ROBIN; everything else is the seed)
            assertEquals(1L, testnet.version)
            val mainnet = fresh.getEngineSettings(Network.MAINNET, Direction.ON_RAMP).data()
            assertEquals(EngineMode.SHADOW to 1L, mainnet.settings.mode to mainnet.version)
            assertNull(mainnet.updatedBy)
            val selling = fresh.getEngineSettings(TESTNET, Direction.OFF_RAMP).data()
            assertEquals(EngineMode.ACTIVE, selling.settings.mode, "the testnet's seed")
            assertEquals(4, fresh.engineRows.rows.size, "two networks, two directions")

            val wanted =
                testnet.copy(
                    settings = testnet.settings.copy(mode = EngineMode.ACTIVE, alpha = 2.0, maxAttempts = 5),
                )
            val change = fresh.updateEngineSettings(wanted, 1, "staff-1", "sharper ranking").data()

            assertEquals(1L to 2L, change.before.version to change.after.version)
            assertEquals(2.0 to 5, change.after.settings.alpha to change.after.settings.maxAttempts)
            assertEquals("staff-1" to "sharper ranking", change.after.updatedBy to change.after.reason)
            assertEquals(fresh.clock.instant(), change.after.updatedAt)
            assertEquals(
                2.0,
                fresh.engineBook
                    .of(TESTNET, Direction.ON_RAMP)
                    .settings.alpha,
                "in force at once here",
            )
            val kept = fresh.engineRows.version(TESTNET, Direction.ON_RAMP, 1)!!
            assertEquals(1.0, kept.settings.alpha, "the version before is still there to read")
            assertEquals(
                2.0,
                fresh.engineRows
                    .version(TESTNET, Direction.ON_RAMP, 2)!!
                    .settings.alpha,
            )

            // a routing made now carries the version that made it
            val (user, phone) = fresh.user("+254700000099") to PhoneNumber.parse("+254700000099")!!
            fresh.prompted(user, phone)
            assertEquals(2L, fresh.lastRouting().settingsVersion)

            assertEquals(
                RampError.SettingsChanged,
                fresh.updateEngineSettings(wanted, 1, "staff-2", "a stale form").error(),
                "someone changed it meanwhile",
            )
            assertIs<Resource.Success<*>>(fresh.updateEngineSettings(wanted, null, "staff-2", "over whatever is there"))
            assertEquals(3L, fresh.getEngineSettings(TESTNET, Direction.ON_RAMP).data().version)
        }

    @Test
    fun `invalid engine settings are refused naming the field, and nothing changes`() =
        runTest {
            val row = ramp.getEngineSettings(TESTNET, Direction.ON_RAMP).data()

            fun invalid(change: (EngineSettings) -> EngineSettings): EngineSettingsVersion =
                row.copy(settings = change(row.settings))

            listOf(
                invalid { it.copy(weightCompletion = 0.9) } to "weightCompletion",
                invalid { it.copy(qualityFloor = 0.0) } to "qualityFloor",
                invalid { it.copy(maxAttempts = 0) } to "maxAttempts",
                invalid { it.copy(slaSeconds = 10, targetSeconds = 20) } to "slaSeconds",
                invalid { it.copy(individualLimitMinor = 0) } to "individualLimitMinor",
            ).forEach { (settings, field) ->
                val error = ramp.updateEngineSettings(settings, null, "staff-1", "why").error()
                assertEquals("ramp.settings_invalid", error.code)
                assertEquals(
                    field,
                    error.details!!
                        .fields
                        .single()
                        .field,
                )
            }
            assertEquals(row, ramp.getEngineSettings(TESTNET, Direction.ON_RAMP).data())
            assertTrue(ramp.engineRows.history.none { it.version > 1 }, "no new version was written")
        }

    // The simulation

    @Test
    fun `a simulation replays the last orders under a draft and changes nothing`() =
        runTest {
            val bidii = ramp.merchant("Bidii", floatMinor = 100_000_000)
            val asleep = ramp.merchant("Asleep", floatMinor = 100_000_000)
            ramp.merchants.setOnlineUntil(asleep.merchantId, null)
            repeat(4) {
                val (user, phone) = buyer()
                val order = ramp.prompted(user, phone)
                ramp.collections.fail(order)
                ramp.callback(order)
                ramp.clock.advance(60)
            }
            val ordersBefore = ramp.orders.rows.toMap()
            val eventsBefore = ramp.orders.events.size
            val logBefore = ramp.routingLog.rows.size
            val merchantsBefore = ramp.merchants.rows.toMap()
            val promptsBefore = ramp.collections.prompts.size
            val settingsBefore = ramp.engineRows.rows.toMap()
            val draft =
                ramp.engineBook
                    .of(TESTNET, Direction.ON_RAMP)
                    .settings
                    .copy(mode = EngineMode.ACTIVE)

            val simulation = ramp.simulate(SimulationCommand(TESTNET, Direction.ON_RAMP, draft, null, null)).data()

            assertEquals(4, simulation.orders, "the four real purchases")
            assertEquals(0, simulation.unroutable)
            assertEquals(EngineMode.ACTIVE to EngineMode.ROUND_ROBIN, simulation.mode to simulation.currentMode)
            val shares = simulation.shares.associateBy { it.merchantId }
            assertEquals(setOf(crynce, bidii.merchantId, asleep.merchantId), shares.keys)
            assertTrue(abs(simulation.shares.sumOf { it.share } - 1.0) < 1e-9, "the shares are all of the orders")
            assertTrue(abs(simulation.shares.sumOf { it.currentShare } - 1.0) < 1e-9)
            assertEquals(0.5, shares.getValue(crynce).currentShare, "round robin: an equal turn")
            assertEquals(0.5, shares.getValue(bidii.merchantId).currentShare)
            assertTrue(shares.getValue(crynce).share > 0 && shares.getValue(bidii.merchantId).share > 0)
            assertEquals(4, shares.getValue(crynce).eligibleOrders)
            assertNotNull(shares.getValue(crynce).quality)
            val out = shares.getValue(asleep.merchantId)
            assertEquals(0.0 to 0, out.share to out.eligibleOrders)
            assertEquals(mapOf(Exclusion.OFFLINE to 4), out.exclusions)
            assertNull(out.quality)

            assertEquals(ordersBefore, ramp.orders.rows.toMap(), "no order")
            assertEquals(eventsBefore, ramp.orders.events.size)
            assertEquals(logBefore, ramp.routingLog.rows.size, "no routing logged")
            assertEquals(merchantsBefore, ramp.merchants.rows.toMap(), "nobody assigned")
            assertEquals(promptsBefore, ramp.collections.prompts.size, "no prompt")
            assertEquals(settingsBefore, ramp.engineRows.rows.toMap(), "the draft was not saved")
            assertTrue(ramp.merchants.locked.size == 4, "and no row lock beyond the four real orders'")
        }

    @Test
    fun `a simulation of one amount shows what a limit or a float would do, and a bad draft is refused`() =
        runTest {
            val bidii = ramp.merchant("Bidii", floatMinor = 15_000_000)
            val draft =
                ramp.engineBook
                    .of(TESTNET, Direction.ON_RAMP)
                    .settings
                    .copy(mode = EngineMode.ACTIVE)

            // KES 3 000 buys about 22.6 USDC: Bidii's 15 USDC can't cover it
            val large = ramp.simulate(SimulationCommand(TESTNET, Direction.ON_RAMP, draft, null, 300_000)).data()
            assertEquals(1, large.orders)
            val shares = large.shares.associateBy { it.merchantId }
            assertEquals(1.0, shares.getValue(crynce).share)
            assertEquals(mapOf(Exclusion.FLOAT_SHORT to 1), shares.getValue(bidii.merchantId).exclusions)

            // a draft that halves the individual limit below the amount: nobody could take it
            val tight = draft.copy(individualLimitMinor = 100_000)
            val none = ramp.simulate(SimulationCommand(TESTNET, Direction.ON_RAMP, tight, null, 300_000)).data()
            assertEquals(1, none.unroutable)
            assertTrue(none.shares.all { it.share == 0.0 })
            assertEquals(1.0, none.shares.single { it.merchantId == crynce }.currentShare, "under what is in force")
            assertEquals(
                mapOf(Exclusion.OVER_LIMIT to 1),
                none.shares.single { it.merchantId == crynce }.exclusions,
            )

            val invalid =
                ramp.simulate(
                    SimulationCommand(TESTNET, Direction.ON_RAMP, draft.copy(alpha = 9.0), 10, null),
                )
            assertEquals("ramp.settings_invalid", invalid.error().code)

            val nothing = ramp.simulate(SimulationCommand(TESTNET, Direction.ON_RAMP, null, 10, null)).data()
            assertEquals(0, nothing.orders, "no real orders yet: nothing to replay")
        }

    // Reading and pruning the log

    @Test
    fun `the routing log is read by merchant, by any order of the purchase, and by time`() =
        runTest {
            val bidii = ramp.merchant("Bidii", floatMinor = 100_000_000)
            ramp.collections.refusals["Crynce-user"] = IntegrationError.Rejected("no")
            val start = ramp.clock.instant()
            val first = buyAs().data()
            ramp.collections.refusals.clear()
            ramp.clock.advance(600)
            val second = buyAs().data()
            assertEquals(bidii.merchantId to crynce, first.merchantId to second.merchantId)

            suspend fun ids(query: RoutingQuery) = ramp.routingDecisions(query, null, 50).data().map { it.decisionId }
            val all = ids(RoutingQuery(TESTNET))
            assertEquals(2, all.size)
            assertEquals(ramp.lastRouting().decisionId, all.first(), "newest first")

            assertEquals(listOf(all.last()), ids(RoutingQuery(TESTNET, merchantId = bidii.merchantId)))
            assertEquals(listOf(all.first()), ids(RoutingQuery(TESTNET, merchantId = crynce)), "who ended up with it")
            val refused =
                ramp.orders.rows.values
                    .single { it.state == OrderState.FAILED }
            assertEquals(
                listOf(all.last()),
                ids(RoutingQuery(TESTNET, orderId = refused.orderId)),
                "the refused attempt finds its purchase's routing",
            )
            assertEquals(listOf(all.last()), ids(RoutingQuery(TESTNET, orderId = first.orderId)))
            assertTrue(ids(RoutingQuery(TESTNET, orderId = RampOrderId.new())).isEmpty())
            assertEquals(listOf(all.last()), ids(RoutingQuery(TESTNET, from = start, to = start.plusSeconds(1))))
            assertEquals(listOf(all.first()), ids(RoutingQuery(TESTNET, from = start.plusSeconds(1))))
            assertTrue(ids(RoutingQuery(Network.MAINNET)).isEmpty())

            val one = ramp.routingDecision(all.last()).data()
            assertEquals(listOf(OrderFlow.PROMPT_REFUSED, "prompted"), one.steps.map { it.result })
            assertEquals(RampError.RoutingNotFound, ramp.routingDecision(UUID.randomUUID()).error())

            val page = ramp.routingDecisions(RoutingQuery(TESTNET), null, 1).data()
            assertEquals(listOf(all.first()), page.map { it.decisionId })
            val cursor = PageCursor(page.single().createdAt, page.single().decisionId)
            assertEquals(
                listOf(all.last()),
                ramp.routingDecisions(RoutingQuery(TESTNET), cursor, 1).data().map { it.decisionId },
            )
        }

    @Test
    fun `routing decisions are pruned after the settings' days`() =
        runTest {
            ramp.engineSettings { it.copy(logRetentionDays = 7) }
            ramp.merchants.setOnlineUntil(crynce, ramp.clock.instant().plus(Duration.ofDays(30)))
            buyAs().data()
            val old = ramp.lastRouting().decisionId
            ramp.clock.advance(Duration.ofDays(6).seconds)
            buyAs().data()
            val recent = ramp.lastRouting().decisionId

            assertEquals(0, ramp.pruneRouting(), "nothing is seven days old yet")
            ramp.clock.advance(Duration.ofDays(2).seconds)
            assertEquals(1, ramp.pruneRouting())

            assertEquals(setOf(recent), ramp.routingLog.rows.keys)
            assertNotEquals(old, recent)
            assertEquals(0, ramp.pruneRouting(), "running it again finds nothing")
        }

    @Test
    fun `the engine's dice are the injected ones - the same seed makes the same picks`() =
        runTest {
            suspend fun picks(seed: Int): List<String> {
                val h = Harness()
                h.random = Random(seed)
                h.engineSettings { it.copy(mode = EngineMode.ACTIVE, hungerMax = 0.0, newcomerMax = 0.0) }
                h.treasury.balances[h.walletOf(h.crynce).custodyWalletId] = 1_000_000_000_000
                h.merchant("Bidii", floatMinor = 1_000_000_000_000)
                return (1..12).map { index ->
                    val number = "+2547${20_000_000 + index}"
                    val order = h.prompted(h.user(number), PhoneNumber.parse(number)!!)
                    h.merchants.rows
                        .getValue(order.merchantId)
                        .displayName
                }
            }

            assertEquals(picks(1), picks(1))
            assertTrue((1..10).map { picks(it) }.toSet().size > 1, "another seed, other picks")
        }
}
