package com.circaso.ramp

import com.circaso.identity.api.AccountStatus
import com.circaso.integrations.api.CollectionState
import com.circaso.integrations.api.CollectionStatus
import com.circaso.integrations.api.IntegrationError
import com.circaso.ramp.api.OrderFailed
import com.circaso.ramp.api.QuoteId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.KycStanding
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.OrderView
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.PreviewAccess
import com.circaso.ramp.domain.models.QuoteCommand
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.domain.models.StartOnRampCommand
import com.circaso.ramp.domain.models.UnlockedKeys
import com.circaso.ramp.domain.support.Providers
import com.circaso.ramp.domain.support.SettingsBook
import com.circaso.shared.FiatAmount
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.getOrNull
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app's side of an on-ramp: the gates, the quote, the order and its prompt, and what the provider's answer does. */
class RampTest {
    private val ramp = Harness()

    private fun <T> Resource<T>.error() = (this as Resource.Error).error

    private fun phoneOf(number: String) = PhoneNumber.parse(number)!!

    // The payment: only the provider's own answer moves an order

    @Test
    fun `a payment the provider confirms makes the order PAID, and the app releases nothing itself`() =
        runTest {
            val order = ramp.prompted()
            assertEquals(OrderState.PAYMENT_REQUESTED, order.state)
            val prompt = ramp.collections.prompts.single()
            assertEquals(FiatAmount.ofWholeUnits(1_300, "KES"), prompt.amount)
            assertEquals(ramp.phone, prompt.payer)
            assertEquals(order.orderId.toString(), prompt.reference)
            assertEquals("https://api.test/v1/hooks/payhero/crynce-0123456789abcdef", prompt.callbackUrl)
            assertEquals(
                "Crynce-user",
                ramp.collections.promptedWith
                    .single()
                    .username,
                "the merchant's own keys",
            )
            assertEquals(9_800_000, ramp.reservations.openTotal(ramp.crynce.merchantId, TESTNET))

            ramp.collections.pay(order)
            ramp.callback(order)

            val paid = ramp.now(order)
            assertEquals(OrderState.PAID, paid.state)
            assertTrue(ramp.treasury.sends.isEmpty(), "only the ops process signs")
            assertTrue(ramp.bus.published.isEmpty(), "nothing is announced until the chain confirms")
            assertEquals(listOf(OrderState.PAYMENT_REQUESTED, OrderState.PAID), ramp.frames.states)
            assertEquals(9_800_000, ramp.reservations.openTotal(ramp.crynce.merchantId, TESTNET), "still held")
        }

    @Test
    fun `a callback is only a doorbell - nothing moves until the provider itself says the payment succeeded`() =
        runTest {
            val order = ramp.prompted()

            ramp.callback(order)

            assertEquals(OrderState.PAYMENT_REQUESTED, ramp.now(order).state)
            assertEquals(listOf("PH-1"), ramp.collections.statusQueries)
        }

    @Test
    fun `a callback on a key nobody was given, or naming something that isn't an order, asks nobody`() =
        runTest {
            val order = ramp.prompted()
            ramp.collections.pay(order)

            ramp.paymentCallback("crynce-ffffffffffffffff", order.orderId.toString())
            ramp.paymentCallback(ramp.crynce.callbackKey!!, "not-an-id")
            ramp.paymentCallback(ramp.crynce.callbackKey!!, RampOrderId.new().toString())

            assertTrue(ramp.collections.statusQueries.isEmpty())
            assertEquals(OrderState.PAYMENT_REQUESTED, ramp.now(order).state)
        }

    @Test
    fun `a merchant's callback address rings only for that merchant's orders`() =
        runTest {
            val order = ramp.prompted()
            ramp.collections.pay(order)
            val other = ramp.merchant("Bidii", floatMinor = 50_000_000)

            ramp.paymentCallback(other.callbackKey!!, order.orderId.toString())

            assertTrue(ramp.collections.statusQueries.isEmpty(), "another merchant's address can't touch it")
            assertEquals(OrderState.PAYMENT_REQUESTED, ramp.now(order).state)

            ramp.callback(order)
            assertEquals(OrderState.PAID, ramp.now(order).state)
        }

    @Test
    fun `two callbacks and the sweep mark an order paid exactly once`() =
        runTest {
            val order = ramp.prompted()
            ramp.collections.pay(order)

            ramp.callback(order)
            ramp.callback(order)
            ramp.clock.advance(60)
            ramp.sweep(TESTNET)

            assertEquals(OrderState.PAID, ramp.now(order).state)
            assertEquals(1, ramp.frames.states.count { it == OrderState.PAID })
        }

    @Test
    fun `a payment the provider says failed ends the order and gives the float back`() =
        runTest {
            val order = ramp.prompted()
            ramp.collections.fail(order)

            ramp.callback(order)

            val failed = ramp.now(order)
            assertEquals(OrderState.FAILED, failed.state)
            assertEquals("payment_failed", failed.failureReason)
            assertEquals(0, ramp.reservations.openTotal(ramp.crynce.merchantId, TESTNET))
            assertEquals(0, ramp.counters.daily.getValue(ramp.amina to TESTNET))
            assertEquals(listOf(OrderFailed.TYPE), ramp.bus.types())
            assertEquals(ramp.outbox.written, ramp.bus.published)
        }

    @Test
    fun `a success that doesn't match the order's amount, payer or reference goes to a person, unpaid`() =
        runTest {
            val wrongAmount = ramp.prompted()
            ramp.collections.statuses["PH-1"] =
                CollectionStatus(CollectionState.SUCCESS, FiatAmount.ofWholeUnits(13, "KES"), ramp.phone, null, "SKL1")
            ramp.callback(wrongAmount)

            val bob = ramp.user("+254700000002")
            val wrongPayer = ramp.prompted(bob, phoneOf("+254700000002"))
            ramp.collections.statuses["PH-2"] =
                CollectionStatus(CollectionState.SUCCESS, wrongPayer.fiat, ramp.phone, null, "SKL2")
            ramp.callback(wrongPayer)

            val carol = ramp.user("+254700000003")
            val wrongReference = ramp.prompted(carol, phoneOf("+254700000003"))
            ramp.collections.statuses["PH-3"] =
                CollectionStatus(CollectionState.SUCCESS, null, null, "another-order", "SKL3")
            ramp.callback(wrongReference)

            listOf(wrongAmount, wrongPayer, wrongReference).forEach { order ->
                assertEquals(OrderState.REVIEW, ramp.now(order).state)
                assertEquals("payment_mismatch", ramp.now(order).failureReason)
                assertEquals(
                    "OPEN",
                    ramp.reservations.rows
                        .getValue(order.orderId)
                        .state,
                    "held for the person who looks",
                )
            }
            assertEquals(List(3) { "payment_mismatch" }, ramp.alerts.reasons)
            assertEquals(0, ramp.release(), "nothing in REVIEW is ever released")
            assertTrue(ramp.treasury.sends.isEmpty())
        }

    @Test
    fun `a success that reports only the state is matched by the reference it was asked with`() =
        runTest {
            val order = ramp.prompted()
            ramp.collections.statuses["PH-1"] = CollectionStatus(CollectionState.SUCCESS, null, null, null, null)

            ramp.callback(order)

            assertEquals(OrderState.PAID, ramp.now(order).state)
            assertNull(ramp.now(order).mpesaReceipt)
        }

    @Test
    fun `one M-Pesa receipt never pays two orders`() =
        runTest {
            val first = ramp.prompted()
            ramp.collections.pay(first, receipt = "SKL4XYZ123")
            ramp.callback(first)

            val bob = ramp.user("+254700000002")
            val second = ramp.prompted(bob, phoneOf("+254700000002"))
            ramp.collections.pay(second, receipt = "SKL4XYZ123")
            ramp.callback(second)

            assertEquals(OrderState.PAID, ramp.now(first).state)
            assertEquals(OrderState.REVIEW, ramp.now(second).state)
            assertEquals("receipt_reused", ramp.now(second).failureReason)
        }

    @Test
    fun `without the merchant's keys on this node, or with the provider down, an order is never settled on a guess`() =
        runTest {
            val order = ramp.prompted()
            ramp.collections.pay(order)

            ramp.sessions.restart()
            ramp.callback(order)
            ramp.clock.advance(3_600)
            ramp.sweep(TESTNET)
            assertEquals(OrderState.PAYMENT_REQUESTED, ramp.now(order).state, "not expired, not paid: it waits")
            assertTrue(ramp.collections.statusQueries.isEmpty())

            ramp.sessions.keys[ramp.crynce.merchantId] = UnlockedKeys(PAYHERO, ramp.collections.promptedWith.single())
            ramp.collections.failure = IntegrationError.Unavailable("PayHero")
            ramp.callback(order)
            assertEquals(OrderState.PAYMENT_REQUESTED, ramp.now(order).state)
            assertEquals(9_800_000, ramp.reservations.openTotal(ramp.crynce.merchantId, TESTNET))

            ramp.collections.failure = null
            ramp.callback(order)
            assertEquals(OrderState.PAID, ramp.now(order).state)
        }

    // The collections provider is a setting

    @Test
    fun `an order keeps the provider that prompted it - a switch mid-order never asks the wrong one`() =
        runTest {
            val order = ramp.prompted()
            assertEquals(PAYHERO, order.collectionsProvider)

            ramp.settingsRows.set(TESTNET) { it.copy(collectionsProvider = DARAJA) }
            ramp.book.invalidate()
            ramp.collections.pay(order)
            ramp.callback(order)

            assertEquals(OrderState.PAID, ramp.now(order).state)
            assertEquals(listOf("PH-1"), ramp.collections.statusQueries, "asked where it was prompted")
            assertTrue(ramp.daraja.statusQueries.isEmpty() && ramp.daraja.prompts.isEmpty())
        }

    @Test
    fun `a switch applies to the next order, and only merchants connected to the new provider take it`() =
        runTest {
            ramp.settingsRows.set(TESTNET) { it.copy(collectionsProvider = DARAJA) }
            ramp.book.invalidate()

            assertEquals(RampError.NoMerchantAvailable, ramp.buy().error(), "Crynce's keys are PayHero's")
            assertFalse(ramp.status(ramp.amina, TESTNET).getOrNull()!!.merchantOnline)
            assertTrue(ramp.collections.prompts.isEmpty() && ramp.daraja.prompts.isEmpty())

            // the merchant reconnects under the new provider, and the next order is prompted there
            val keys = UnlockedKeys(DARAJA, ramp.sessions.of(ramp.crynce.merchantId)!!.credentials)
            ramp.sessions.keys[ramp.crynce.merchantId] = keys
            val order = ramp.prompted()

            assertEquals(DARAJA, order.collectionsProvider)
            assertEquals(
                order.orderId.toString(),
                ramp.daraja.prompts
                    .single()
                    .reference,
            )
            assertTrue(ramp.collections.prompts.isEmpty())
            ramp.daraja.pay(order)
            ramp.callback(order)
            assertEquals(OrderState.PAID, ramp.now(order).state)
        }

    @Test
    fun `an order whose provider this server no longer offers waits - it is never settled on a guess`() =
        runTest {
            val order = ramp.prompted()
            ramp.collections.pay(order)
            ramp.offered.remove(PAYHERO)

            ramp.callback(order)
            ramp.clock.advance(3_600)
            ramp.sweep(TESTNET)
            assertEquals(OrderState.PAYMENT_REQUESTED, ramp.now(order).state)

            ramp.offered[PAYHERO] = ramp.collections
            ramp.callback(order)
            assertEquals(OrderState.PAID, ramp.now(order).state)
        }

    @Test
    fun `a network starts on PayHero, and on log only where the server offers it - a developer's machine`() =
        runTest {
            val server =
                SettingsBook(MemorySettings(), RecordingAlerts(), ramp.providers, ramp.kycProviders, ramp.clock)
            assertEquals(PAYHERO, server.of(TESTNET).collectionsProvider)
            assertEquals(PAYHERO, server.of(Network.MAINNET).collectionsProvider)

            val local = FakeProviders(mapOf(PAYHERO to FakeCollections(), "log" to FakeCollections()))
            val rows = MemorySettings()
            val laptop = SettingsBook(rows, RecordingAlerts(), local, ramp.kycProviders, ramp.clock)
            assertEquals("log", laptop.of(TESTNET).collectionsProvider)
            assertEquals("log", rows.rows.getValue(TESTNET).collectionsProvider, "written once as the row")
            assertEquals("log" to PAYHERO, Providers.seed(local) to Providers.seed(ramp.providers))
        }

    // The sweep

    @Test
    fun `the sweep settles a paid order whose callback never came, and sends an unresolved one to a person`() =
        runTest {
            val paid = ramp.prompted()
            ramp.collections.pay(paid)
            val bob = ramp.user("+254700000002")
            val silent = ramp.prompted(bob, phoneOf("+254700000002"))

            ramp.clock.advance(31)
            ramp.sweep(TESTNET)
            assertEquals(OrderState.PAID, ramp.now(paid).state)
            assertEquals(OrderState.PAYMENT_REQUESTED, ramp.now(silent).state, "still queued: the float stays held")

            ramp.clock.advance(600)
            ramp.sweep(TESTNET)
            assertEquals(OrderState.REVIEW, ramp.now(silent).state)
            assertEquals("payment_unresolved", ramp.now(silent).failureReason)
        }

    @Test
    fun `the sweep runs for one network and leaves the other's orders alone`() =
        runTest {
            val order = ramp.prompted()
            ramp.collections.pay(order)
            ramp.clock.advance(31)

            assertEquals(0, ramp.sweep(Network.MAINNET))
            assertEquals(OrderState.PAYMENT_REQUESTED, ramp.now(order).state)
            assertEquals(1, ramp.sweep(TESTNET))
        }

    @Test
    fun `an order whose prompt never went out expires and frees its float`() =
        runTest {
            val order = ramp.prompted()
            ramp.orders.rows[order.orderId] = order.copy(state = OrderState.RESERVED, providerReference = null)

            ramp.clock.advance(60)
            ramp.sweep(TESTNET)
            assertEquals(OrderState.RESERVED, ramp.now(order).state, "its payment window isn't over")

            ramp.clock.advance(61)
            ramp.sweep(TESTNET)

            assertEquals(OrderState.EXPIRED, ramp.now(order).state)
            assertEquals(0, ramp.reservations.openTotal(ramp.crynce.merchantId, TESTNET))
            assertEquals(listOf(OrderFailed.TYPE), ramp.bus.types())
        }

    @Test
    fun `a payment that lands after the order expired goes to a person`() =
        runTest {
            val order = ramp.prompted()
            ramp.collections.pay(order)
            ramp.orders.rows[order.orderId] = order.copy(state = OrderState.EXPIRED)

            ramp.callback(order)

            assertEquals(OrderState.REVIEW, ramp.now(order).state)
            assertEquals("paid_after_expiry", ramp.now(order).failureReason)
        }

    // The state gate

    @Test
    fun `an OFF ramp answers unavailable to everyone, on every way to start a trade`() =
        runTest {
            ramp.settingsRows.set(TESTNET) { it.copy(state = RampState.OFF) }

            assertEquals(RampError.Unavailable, ramp.status(ramp.amina, TESTNET).error())
            assertEquals(RampError.Unavailable, ramp.quote().error())
            val command = StartOnRampCommand(ramp.amina, TESTNET, QuoteId.new(), ramp.phone)
            assertEquals(RampError.Unavailable, ramp.startOnRamp(command).error())
        }

    @Test
    fun `a PREVIEW ramp lets in only the people on the list, and mainnet starts OFF`() =
        runTest {
            ramp.settingsRows.set(TESTNET) { it.copy(state = RampState.PREVIEW) }
            assertEquals(RampError.Unavailable, ramp.quote().error())

            ramp.preview.grant(PreviewAccess(ramp.amina, TESTNET, "staff", ramp.clock.instant()))
            assertIs<Resource.Success<*>>(ramp.quote())
            assertIs<Resource.Success<*>>(ramp.status(ramp.amina, TESTNET))

            val bob = ramp.user("+254700000002")
            assertEquals(RampError.Unavailable, ramp.quote(bob).error())
            assertEquals(RampError.Unavailable, ramp.status(ramp.amina, Network.MAINNET).error(), "seeded OFF")
        }

    @Test
    fun `an order already open can still be read and cancelled after the ramp is switched off`() =
        runTest {
            val order = ramp.prompted()
            ramp.settingsRows.set(TESTNET) { it.copy(state = RampState.OFF) }
            ramp.book.invalidate()

            assertEquals(
                order.orderId,
                ramp
                    .getOrder(ramp.amina, order.orderId)
                    .getOrNull()
                    ?.order
                    ?.orderId,
            )
            ramp.collections.fail(order)
            assertEquals(
                OrderState.FAILED,
                ramp
                    .cancelOrder(ramp.amina, order.orderId)
                    .getOrNull()
                    ?.order
                    ?.state,
            )
        }

    // KYC

    @Test
    fun `KYC is asked for where the settings require it, and always on mainnet`() =
        runTest {
            assertEquals(KycStanding.NOT_REQUIRED, ramp.status(ramp.amina, TESTNET).getOrNull()?.kyc)
            assertIs<Resource.Success<*>>(ramp.quote())

            ramp.settingsRows.set(TESTNET) { it.copy(kycRequired = true) }
            ramp.book.invalidate()
            assertEquals(KycStanding.NONE, ramp.status(ramp.amina, TESTNET).getOrNull()?.kyc)
            assertEquals(RampError.KycRequired, ramp.quote().error())
            assertEquals(RampError.KycRequired, ramp.buyWithFreshQuoteRefused())

            ramp.kyc.verified = true
            assertEquals(KycStanding.VERIFIED, ramp.status(ramp.amina, TESTNET).getOrNull()?.kyc)
            assertIs<Resource.Success<*>>(ramp.quote())

            // a mainnet row that says "not required" (it can't: the table refuses it) is still enforced in code
            ramp.kyc.verified = false
            ramp.settingsRows.set(Network.MAINNET) { it.copy(state = RampState.ON, kycRequired = false) }
            ramp.book.invalidate()
            assertEquals(KycStanding.NONE, ramp.status(ramp.amina, Network.MAINNET).getOrNull()?.kyc)
        }

    private suspend fun Harness.buyWithFreshQuoteRefused(): Any? {
        kyc.verified = true
        val quote = (quote() as Resource.Success).data
        kyc.verified = false
        return (startOnRamp(StartOnRampCommand(amina, TESTNET, quote.quoteId, phone)) as Resource.Error).error
    }

    // Quotes

    @Test
    fun `the fee is inside what the user pays, and the quote keeps the rate and the split it was priced at`() =
        runTest {
            val quote = (ramp.quote(shillings = 1_300) as Resource.Success).data

            assertEquals(130_000, quote.fiat.amountMinor)
            assertEquals(2_600, quote.feeMinor, "2 % of 1 300 shillings is 26")
            assertEquals(9_800_000, quote.assetMinor, "what 1 274 shillings buy at 130")
            assertEquals(200 to 7_000, quote.feeBps to quote.merchantShareBps)
            assertEquals(TESTNET, quote.network)
            assertEquals(ramp.clock.instant().plusSeconds(60), quote.expiresAt)

            // staff change the fee: the quote already made, and its order, keep theirs
            ramp.settingsRows.set(TESTNET) { it.copy(feeBps = 500, merchantShareBps = 1_000) }
            ramp.book.invalidate()
            val order =
                ramp.startOnRamp(StartOnRampCommand(ramp.amina, TESTNET, quote.quoteId, ramp.phone)).getOrNull()!!.order
            assertEquals(Triple(2_600L, 200, 7_000), Triple(order.feeMinor, order.feeBps, order.merchantShareBps))
            assertEquals(BigDecimal("130"), order.rate)
        }

    @Test
    fun `a quote is refused for selling, a chain not offered, a bad amount, the limits and a pricing failure`() =
        runTest {
            fun command(
                direction: Direction = Direction.ON_RAMP,
                chain: String = CHAIN,
                fiat: Long? = 130_000,
                asset: Long? = null,
            ) = QuoteCommand(ramp.amina, TESTNET, direction, chain, fiat, asset)

            assertEquals(RampError.OffRampUnavailable, ramp.createQuote(command(Direction.OFF_RAMP)).error())
            assertEquals(RampError.ChainUnsupported, ramp.createQuote(command(chain = "SOL-DEVNET")).error())
            assertEquals(RampError.AmountInvalid, ramp.createQuote(command(fiat = null)).error())
            assertEquals(RampError.AmountInvalid, ramp.createQuote(command(asset = 1_000_000)).error())
            assertEquals(RampError.LimitExceeded, ramp.createQuote(command(fiat = 500)).error())
            assertEquals(RampError.LimitExceeded, ramp.createQuote(command(fiat = 500_100)).error())

            ramp.accounts.statuses[ramp.amina] = AccountStatus.SUSPENDED
            assertEquals(RampError.AccountRestricted, ramp.createQuote(command()).error())
            ramp.accounts.statuses[ramp.amina] = AccountStatus.ACTIVE

            ramp.pricing.failure = Resource.Error(RampError.PricingUnavailable)
            assertEquals(RampError.PricingUnavailable, ramp.createQuote(command()).error())
            assertTrue(ramp.quotes.rows.isEmpty())
        }

    // Starting an order: gates and claims

    @Test
    fun `repeating the request with the same quote returns the same order and sends no second prompt`() =
        runTest {
            val quote = (ramp.quote() as Resource.Success).data
            val command = StartOnRampCommand(ramp.amina, TESTNET, quote.quoteId, ramp.phone)

            val first = (ramp.startOnRamp(command) as Resource.Success).data
            ramp.clock.advance(90)
            val again = (ramp.startOnRamp(command) as Resource.Success).data

            assertEquals(first.order.orderId, again.order.orderId)
            assertEquals(1, ramp.collections.prompts.size)
            assertEquals("Crynce", again.merchantName)
        }

    @Test
    fun `a user has one open order at a time`() =
        runTest {
            ramp.prompted()

            assertEquals(RampError.OrderInProgress, ramp.buy().error())
            assertEquals(1, ramp.collections.prompts.size)
        }

    @Test
    fun `the gates - an active account, your own verified number, a wallet on the chain, your own live quote`() =
        runTest {
            val quote = (ramp.quote() as Resource.Success).data
            val command = StartOnRampCommand(ramp.amina, TESTNET, quote.quoteId, ramp.phone)

            ramp.accounts.statuses[ramp.amina] = AccountStatus.SUSPENDED
            assertEquals(RampError.AccountRestricted, ramp.startOnRamp(command).error())
            ramp.accounts.statuses[ramp.amina] = AccountStatus.ACTIVE

            val someoneElses = phoneOf("+254799999999")
            assertEquals(RampError.PhoneNotYours, ramp.startOnRamp(command.copy(phone = someoneElses)).error())

            val bob = ramp.user("+254700000002")
            val bobs = StartOnRampCommand(bob, TESTNET, quote.quoteId, phoneOf("+254700000002"))
            assertEquals(RampError.QuoteNotFound, ramp.startOnRamp(bobs).error())
            assertEquals(RampError.Unavailable, ramp.startOnRamp(command.copy(network = Network.MAINNET)).error())

            ramp.wallets.addresses.remove(ramp.amina to CHAIN)
            assertEquals(RampError.WalletRequired, ramp.startOnRamp(command).error())
            ramp.wallets.addresses[ramp.amina to CHAIN] = "0xuser1"

            ramp.clock.advance(61)
            assertEquals(RampError.QuoteExpired, ramp.startOnRamp(command).error())
            assertTrue(ramp.orders.rows.isEmpty())
            assertTrue(ramp.collections.prompts.isEmpty())
        }

    @Test
    fun `only prompts that reached the phone count, and a limit of 0 is no limit`() =
        runTest {
            ramp.settingsRows.set(TESTNET) { it.copy(promptsPerHour = 1) }
            ramp.collections.failure = IntegrationError.Unavailable("PayHero")
            repeat(3) { assertEquals(RampError.CollectionsUnavailable, ramp.buy().error()) }
            assertEquals(0, ramp.counters.promptsThisHour(ramp.amina), "an attempt that sent nothing is not counted")

            ramp.collections.failure = null
            val reached = assertIs<Resource.Success<OrderView>>(ramp.buy(), "step 1").data.order
            ramp.collections.fail(reached)
            ramp.callback(reached)
            assertIs<RampError.TooManyPrompts>(ramp.buy().error())

            ramp.settingsRows.set(TESTNET) { it.copy(promptsPerHour = 0) }
            ramp.clock.advance(11) // past the settings cache
            repeat(3) { round ->
                val order = assertIs<Resource.Success<OrderView>>(ramp.buy(), "no limit, round $round").data.order
                ramp.collections.fail(order)
                ramp.callback(order)
            }
        }

    @Test
    fun `the daily limit and the prompts per hour refuse, and give back what they counted`() =
        runTest {
            ramp.settingsRows.set(TESTNET) { it.copy(dailyLimitMinor = 200_000, promptsPerHour = 2) }

            val first = ramp.prompted(shillings = 1_500)
            ramp.collections.fail(first)
            ramp.callback(first)
            assertEquals(0, ramp.counters.daily.getValue(ramp.amina to TESTNET), "a failed order gives its day back")

            ramp.prompted(shillings = 1_500).also {
                ramp.collections.fail(it)
                ramp.callback(it)
            }
            assertIs<RampError.TooManyPrompts>(ramp.buy(shillings = 1_500).error())
            assertEquals(0, ramp.counters.daily.getValue(ramp.amina to TESTNET))

            val bob = ramp.user("+254700000002")
            assertEquals(RampError.LimitExceeded, ramp.buy(bob, phoneOf("+254700000002"), shillings = 2_100).error())
        }

    @Test
    fun `a prompt that can't be sent ends the order and frees the float`() =
        runTest {
            ramp.collections.failure = IntegrationError.Unavailable("PayHero")

            assertEquals(RampError.CollectionsUnavailable, ramp.buy().error())

            val order =
                ramp.orders.rows.values
                    .single()
            assertEquals(OrderState.FAILED, order.state)
            assertEquals("prompt_failed", order.failureReason)
            assertEquals(0, ramp.reservations.openTotal(ramp.crynce.merchantId, TESTNET))
            assertEquals(0, ramp.counters.daily.getValue(ramp.amina to TESTNET))
        }

    // The engine

    @Test
    fun `nobody is picked when the merchant is offline, unapproved, out of float, or unlocked on another node`() =
        runTest {
            val id = ramp.crynce.merchantId
            ramp.settingsRows.set(TESTNET) { it.copy(promptsPerHour = 20) }

            ramp.merchants.setOnlineUntil(id, ramp.clock.instant().plusSeconds(60))
            assertEquals(RampError.NoMerchantAvailable, ramp.buy().error(), "the window ends before the order would")
            ramp.merchants.setOnlineUntil(id, ramp.clock.instant().plusSeconds(3_600))

            ramp.merchants.rows[id] =
                ramp.merchants.rows
                    .getValue(id)
                    .copy(state = MerchantState.SUSPENDED)
            assertEquals(RampError.NoMerchantAvailable, ramp.buy().error())
            ramp.merchants.rows[id] =
                ramp.merchants.rows
                    .getValue(id)
                    .copy(state = MerchantState.APPROVED)

            ramp.treasury.balances[ramp.walletOf(ramp.crynce).custodyWalletId] = 9_000_000
            assertEquals(RampError.NoMerchantAvailable, ramp.buy().error())
            ramp.treasury.balances[ramp.walletOf(ramp.crynce).custodyWalletId] = 100_000_000

            val keys = ramp.sessions.keys.remove(id)!!
            assertEquals(RampError.NoMerchantAvailable, ramp.buy().error(), "this node doesn't hold the keys")
            assertFalse(ramp.status(ramp.amina, TESTNET).getOrNull()!!.merchantOnline)
            ramp.sessions.keys[id] = keys

            ramp.treasury.failure = IntegrationError.Unavailable("Circle")
            assertEquals(RampError.CustodyUnavailable, ramp.buy().error())
            ramp.treasury.failure = null

            assertTrue(ramp.orders.rows.isEmpty())
            assertEquals(0, ramp.counters.daily.getValue(ramp.amina to TESTNET))
            assertIs<Resource.Success<*>>(ramp.buy())
        }

    @Test
    fun `float promised to open orders, and Circaso's share not yet swept, can't be sold again`() =
        runTest {
            val wallet = ramp.walletOf(ramp.crynce).custodyWalletId
            ramp.treasury.balances[wallet] = 15_000_000
            ramp.prompted()

            val bob = ramp.user("+254700000002")
            assertEquals(RampError.NoMerchantAvailable, ramp.buy(bob, phoneOf("+254700000002")).error())
            assertEquals(listOf(ramp.crynce.merchantId, ramp.crynce.merchantId), ramp.merchants.locked)

            // an order completes: 9.8 USDC leaves, and 0.06 USDC of what is left is Circaso's until the sweep
            val h = Harness()
            val w = h.walletOf(h.crynce).custodyWalletId
            h.treasury.balances[w] = 19_650_000
            h.completed()
            assertEquals(9_850_000, h.treasury.balances.getValue(w))
            assertEquals(60_000, h.ledger.owedTotal(h.crynce.merchantId, TESTNET))
            val carol = h.user("+254700000003")
            assertEquals(
                RampError.NoMerchantAvailable,
                h.buy(carol, phoneOf("+254700000003")).error(),
                "9.85 - 0.06 < 9.8",
            )
        }

    @Test
    fun `equal merchants take turns, and the next one is tried when the first can't cover the amount`() =
        runTest {
            val bidii = ramp.merchant("Bidii", floatMinor = 100_000_000)
            val bob = ramp.user("+254700000002")

            val first = ramp.prompted()
            val second = ramp.prompted(bob, phoneOf("+254700000002"))
            assertEquals(setOf(ramp.crynce.merchantId, bidii.merchantId), setOf(first.merchantId, second.merchantId))

            val carol = ramp.user("+254700000003")
            ramp.treasury.balances[ramp.walletOf(ramp.crynce).custodyWalletId] = 1_000_000
            ramp.clock.advance(1)
            val third = ramp.prompted(carol, phoneOf("+254700000003"))
            assertEquals(bidii.merchantId, third.merchantId)
        }

    // Reading and cancelling

    @Test
    fun `an order is its user's alone, and their list pages by keyset on their network`() =
        runTest {
            val orders =
                (1..3).map {
                    val order = ramp.prompted()
                    ramp.collections.fail(order)
                    ramp.callback(order)
                    ramp.clock.advance(10)
                    order
                }
            val bob = ramp.user("+254700000002")

            assertEquals(RampError.OrderNotFound, ramp.getOrder(bob, orders[0].orderId).error())
            val firstPage = ramp.listOrders(ramp.amina, TESTNET, 2, null).getOrNull()!!
            assertEquals(listOf(orders[2].orderId, orders[1].orderId), firstPage.map { it.order.orderId })
            val last = firstPage.last().order
            val cursor = PageCursor(last.createdAt, last.orderId.value)
            val secondPage = ramp.listOrders(ramp.amina, TESTNET, 2, cursor).getOrNull()!!
            assertEquals(listOf(orders[0].orderId), secondPage.map { it.order.orderId })
            assertTrue(ramp.listOrders(ramp.amina, Network.MAINNET, 10, null).getOrNull()!!.isEmpty())
        }

    @Test
    fun `an order is cancelled only once the provider confirms nothing was paid`() =
        runTest {
            val order = ramp.prompted()

            assertEquals(RampError.OrderNotCancellable, ramp.cancelOrder(ramp.amina, order.orderId).error())
            assertEquals(RampError.OrderNotFound, ramp.cancelOrder(ramp.user("+254700000002"), order.orderId).error())

            ramp.collections.pay(order)
            assertEquals(RampError.OrderNotCancellable, ramp.cancelOrder(ramp.amina, order.orderId).error())
            assertEquals(OrderState.PAID, ramp.now(order).state, "the payment that landed carries on")
        }
}
