package com.circaso.ramp

import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.UsdRate
import com.circaso.ramp.data.support.RatePricing
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.PricingSettings
import com.circaso.ramp.domain.models.RateBasis
import com.circaso.ramp.domain.models.RateMode
import com.circaso.ramp.domain.models.RatePause
import com.circaso.ramp.domain.models.RateReading
import com.circaso.ramp.domain.models.ReferenceRate
import com.circaso.ramp.domain.models.StartOnRampCommand
import com.circaso.ramp.domain.support.PricingBook
import com.circaso.ramp.domain.support.RatePolicy
import com.circaso.ramp.domain.usecases.GetRates
import com.circaso.ramp.domain.usecases.UpdatePricingSettings
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.getOrNull
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The day's reference rate, as the integration gives it; null when it can't be read. */
private class FakeRates(var rate: UsdRate?) : ExchangeRates {
    override suspend fun usdRate(currency: String): UsdRate? = rate?.takeIf { it.currency == currency }
}

/**
 * Admin-controlled rates (decision 2026-11-24): a spread on each side of the reference, or exact manual rates with an
 * expiry; a stale reference or an expired manual rate stops quoting, never a silent fallback; manual rates are
 * checked against the reference when saved; and every quote and order keeps how its rate was made.
 */
class RatesTest {
    private val clock = TestClock()
    private val now: Instant = clock.instant()
    private val testnet = Network.TESTNET
    private val reference = ReferenceRate(BigDecimal("129.25"), now.minus(Duration.ofHours(6)))
    private val rows = MemoryPricingSettings()
    private val alerts = RecordingAlerts()
    private val book = PricingBook(rows, alerts, clock, Duration.ZERO)
    private val rates = FakeRates(UsdRate("KES", reference.rate, reference.asOf))
    private val pricing = RatePricing(rates, book, clock)
    private val update = UpdatePricingSettings(rows, book, pricing, clock)
    private val getRates = GetRates(rows, book, pricing, clock)

    private fun settings(change: PricingSettings.() -> PricingSettings = { this }) =
        PricingSettings.defaults(testnet, now).change()

    private fun <T> Resource<T>.error() = (this as Resource.Error).error

    private fun ready(reading: RateReading) = assertIs<RateReading.Ready>(reading).applied

    // The policy, pure

    @Test
    fun `with no spread both sides are the reference rate exactly, as before`() {
        val buy = ready(RatePolicy.read(settings(), reference, Direction.ON_RAMP, now))
        val sell = ready(RatePolicy.read(settings(), reference, Direction.OFF_RAMP, now))

        assertEquals(BigDecimal("129.25"), buy.rate)
        assertEquals(BigDecimal("129.25"), sell.rate)
        assertEquals(RateBasis(RateMode.REFERENCE_SPREAD, BigDecimal("129.25"), 0), buy.basis)
    }

    @Test
    fun `a spread raises the buy rate and lowers the sell rate, rounded the merchant's way`() {
        val spread = settings { copy(buySpreadBps = 150, sellSpreadBps = 75) }

        // 129.25 × 1.015 = 131.18875; 129.25 × 0.9925 = 128.280625
        assertEquals(BigDecimal("131.18875"), ready(RatePolicy.read(spread, reference, Direction.ON_RAMP, now)).rate)
        assertEquals(BigDecimal("128.280625"), ready(RatePolicy.read(spread, reference, Direction.OFF_RAMP, now)).rate)

        // more than six decimals: buy rounds up, sell rounds down
        val odd = ReferenceRate(BigDecimal("129.123457"), reference.asOf)
        val oddSpread = settings { copy(buySpreadBps = 1, sellSpreadBps = 1) }
        // 129.123457 × 1.0001 = 129.1363693457; × 0.9999 = 129.1105446543
        assertEquals(BigDecimal("129.13637"), ready(RatePolicy.read(oddSpread, odd, Direction.ON_RAMP, now)).rate)
        assertEquals(BigDecimal("129.110544"), ready(RatePolicy.read(oddSpread, odd, Direction.OFF_RAMP, now)).rate)
    }

    @Test
    fun `no reference, or one older than the limit, pauses reference-based quoting`() {
        assertEquals(
            RateReading.Paused(RatePause.NO_REFERENCE),
            RatePolicy.read(settings(), null, Direction.ON_RAMP, now),
        )
        val old = reference.copy(asOf = now.minus(Duration.ofHours(37)))
        assertEquals(
            RateReading.Paused(RatePause.REFERENCE_STALE),
            RatePolicy.read(settings(), old, Direction.OFF_RAMP, now),
        )
        assertTrue(RatePolicy.isStale(settings(), old, now))
        val patient = settings { copy(maxReferenceAgeHours = 48) }
        assertIs<RateReading.Ready>(RatePolicy.read(patient, old, Direction.ON_RAMP, now))
    }

    @Test
    fun `manual rates hold until they expire, with the margin they work out to, and need no reference to quote`() {
        val manual =
            settings {
                copy(
                    mode = RateMode.MANUAL,
                    manualBuyRate = BigDecimal("132.50"),
                    manualSellRate = BigDecimal("127.00"),
                    manualExpiresAt = now.plus(Duration.ofHours(24)),
                )
            }
        val buy = ready(RatePolicy.read(manual, reference, Direction.ON_RAMP, now))
        val sell = ready(RatePolicy.read(manual, reference, Direction.OFF_RAMP, now))
        assertEquals(BigDecimal("132.5"), buy.rate)
        assertEquals(RateBasis(RateMode.MANUAL, BigDecimal("129.25"), 251), buy.basis, "3.25 over 129.25")
        assertEquals(RateBasis(RateMode.MANUAL, BigDecimal("129.25"), 174), sell.basis, "2.25 under 129.25")

        val blind = ready(RatePolicy.read(manual, null, Direction.ON_RAMP, now))
        assertEquals(RateBasis(RateMode.MANUAL, null, null), blind.basis)

        val later = now.plus(Duration.ofHours(24))
        assertEquals(
            RateReading.Paused(RatePause.MANUAL_EXPIRED),
            RatePolicy.read(manual, reference, Direction.ON_RAMP, later),
        )
    }

    @Test
    fun `settings out of range name the field`() {
        assertEquals("buySpreadBps", settings { copy(buySpreadBps = -1) }.problem(now)?.first)
        assertEquals("sellSpreadBps", settings { copy(sellSpreadBps = 2_001) }.problem(now)?.first)
        assertEquals("maxDeviationBps", settings { copy(maxDeviationBps = 0) }.problem(now)?.first)
        assertEquals("maxReferenceAgeHours", settings { copy(maxReferenceAgeHours = 0) }.problem(now)?.first)
        val manual = settings { copy(mode = RateMode.MANUAL) }
        assertEquals("manualBuyRate", manual.problem(now)?.first)
        val inverted =
            manual.copy(
                manualBuyRate = BigDecimal("128"),
                manualSellRate = BigDecimal("129"),
                manualExpiresAt = now.plusSeconds(60),
            )
        assertEquals("manualBuyRate" to "must not be below the sell rate", inverted.problem(now))
        assertEquals(
            "manualExpiresAt",
            inverted.copy(manualBuyRate = BigDecimal("130"), manualExpiresAt = now).problem(now)?.first,
        )
        assertEquals("manualSellRate", inverted.copy(manualSellRate = BigDecimal("1.1234567")).problem(now)?.first)
        assertNull(inverted.copy(manualBuyRate = BigDecimal("130")).problem(now))
    }

    // Pricing at the rates

    @Test
    fun `buying is priced at the buy rate and records how it was made`() =
        runTest {
            rows.seed(settings { copy(buySpreadBps = 200, version = 1) })
            // 129.25 × 1.02 = 131.835; 1 300 at 2 %: fee 26, 1 274 / 131.835 = 9.663594 USDC (down)
            val priced = (pricing.price(testnet, Direction.ON_RAMP, 130_000, null, 200) as Resource.Success).data

            assertEquals(BigDecimal("131.835"), priced.rate)
            assertEquals(2_600, priced.feeMinor)
            assertEquals(9_663_594, priced.assetMinor)
            assertEquals(RateBasis(RateMode.REFERENCE_SPREAD, BigDecimal("129.25"), 200), priced.rateBasis)

            val sell = (pricing.rate(testnet, Direction.OFF_RAMP) as Resource.Success).data
            assertEquals(BigDecimal("129.25"), sell.rate, "the sell spread is still 0")
        }

    @Test
    fun `a paused side is pricing_unavailable, never a fallback`() =
        runTest {
            rates.rate = UsdRate("KES", reference.rate, now.minus(Duration.ofHours(40)))
            assertEquals(RampError.PricingUnavailable, pricing.rate(testnet, Direction.ON_RAMP).error())
            assertEquals(
                RampError.PricingUnavailable,
                pricing.price(testnet, Direction.ON_RAMP, 130_000, null, 200).error(),
            )

            rows.rows[testnet] =
                settings {
                    copy(
                        mode = RateMode.MANUAL,
                        manualBuyRate = BigDecimal("131"),
                        manualSellRate = BigDecimal("128"),
                        manualExpiresAt = now.plusSeconds(60),
                    )
                }
            book.invalidate()
            assertEquals(BigDecimal("131"), (pricing.rate(testnet, Direction.ON_RAMP) as Resource.Success).data.rate)
            clock.advance(60)
            assertEquals(RampError.PricingUnavailable, pricing.rate(testnet, Direction.OFF_RAMP).error())
        }

    // Staff's changes

    @Test
    fun `manual rates are checked against the reference when saved, and expire in 24 hours unless told otherwise`() =
        runTest {
            book.of(testnet)
            val manual =
                settings {
                    copy(mode = RateMode.MANUAL, manualBuyRate = BigDecimal("136"), manualSellRate = BigDecimal("127"))
                }

            // 136 is 5.23 % over 129.25: outside the 5 % band
            val refused = update(manual, 1, "staff", "P2P is higher").error()
            assertIs<RampError.SettingsInvalid>(refused)
            assertTrue("manualBuyRate" in refused.message, refused.message)

            val inverted = manual.copy(manualBuyRate = BigDecimal("127"), manualSellRate = BigDecimal("128"))
            assertIs<RampError.SettingsInvalid>(update(inverted, 1, "staff", "typo").error())

            val saved =
                (update(manual.copy(manualBuyRate = BigDecimal("135")), 1, "staff", "P2P") as Resource.Success).data
            assertEquals(2, saved.after.version)
            assertEquals(now.plus(Duration.ofHours(24)), saved.after.manualExpiresAt)
            assertEquals("staff" to "P2P", saved.after.updatedBy to saved.after.reason)
            assertEquals(BigDecimal("135"), (pricing.rate(testnet, Direction.ON_RAMP) as Resource.Success).data.rate)

            val again = manual.copy(manualBuyRate = BigDecimal("134"))
            assertEquals(RampError.SettingsChanged, update(again, 1, "staff", "stale read").error())

            rates.rate = null
            assertIs<RampError.SettingsInvalid>(
                update(manual.copy(manualBuyRate = BigDecimal("130")), 2, "s", "r").error(),
            )
            assertEquals(
                BigDecimal("135"),
                (pricing.rate(testnet, Direction.ON_RAMP) as Resource.Success).data.rate,
                "a saved manual rate quotes without a reference",
            )
        }

    @Test
    fun `staff see the reference, its age, and each side's rate or why it is paused`() =
        runTest {
            rows.seed(settings { copy(buySpreadBps = 100, sellSpreadBps = 100) })
            val view = (getRates(testnet) as Resource.Success).data
            assertEquals(reference, view.reference)
            assertEquals(false, view.referenceStale)
            assertEquals(BigDecimal("130.5425"), ready(view.buy).rate)
            assertEquals(BigDecimal("127.9575"), ready(view.sell).rate)

            clock.advance(Duration.ofHours(31).seconds)
            val stale = (getRates(testnet) as Resource.Success).data
            assertTrue(stale.referenceStale)
            assertEquals(RateReading.Paused(RatePause.REFERENCE_STALE), stale.buy)
        }

    // Quotes and orders keep how their rate was made

    @Test
    fun `a quote and its order keep the rate's source, the reference and the spread`() =
        runTest {
            val ramp = Harness()
            val quote = (ramp.quote() as Resource.Success).data
            val basis = RateBasis(RateMode.REFERENCE_SPREAD, BigDecimal("130"), 0)
            assertEquals(basis, quote.rateBasis)
            val order =
                ramp.startOnRamp(StartOnRampCommand(ramp.amina, testnet, quote.quoteId, ramp.phone)).getOrNull()!!.order
            assertEquals(basis, order.rateBasis)
        }
}
