package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.CollectionNotifications
import com.circaso.integrations.api.FeeSource
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.PayoutLookup
import com.circaso.integrations.api.PayoutReference
import com.circaso.integrations.api.PayoutRejection
import com.circaso.integrations.api.PayoutRequest
import com.circaso.integrations.api.PayoutResult
import com.circaso.integrations.api.PayoutStatus
import com.circaso.integrations.api.ProviderFee
import com.circaso.shared.FiatAmount
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CollectionsTest {
    private val fake = FakeCollections()
    private val merchant = CollectionCredentials("merchant-user", "merchant-pass", "911")
    private val payout =
        PayoutRequest(
            payee = PhoneNumber.parse("+254712345678")!!,
            amount = FiatAmount.ofWholeUnits(500, "KES"),
            reference = "order-9",
            callbackUrl = "https://api.test/v1/hooks/payhero/crynce-9fQ2xLr7Tk3mVb8s",
            accountId = "63",
        )

    private fun lookUp(providerReference: String? = "PO-1"): PayoutStatus =
        runBlocking { fake.lookUpPayout(merchant, PayoutLookup("order-9", providerReference)) }

    @Test
    fun `accepted, then paid as asked`() =
        runBlocking<Unit> {
            val accepted = assertIs<PayoutResult.Accepted>(fake.sendPayout(merchant, payout))
            assertEquals("PO-1", accepted.receipt.providerReference)
            assertEquals(PayoutStatus.Pending, lookUp())

            fake.completePayout("order-9")

            val paid = assertIs<PayoutStatus.Paid>(lookUp())
            assertEquals(payout.amount, paid.amount)
            assertEquals("order-9", paid.reference)
            assertEquals(listOf(payout), fake.payouts.toList())
        }

    @Test
    fun `accepted, then failed, and the amount is back in the payout balance`() =
        runBlocking<Unit> {
            fake.payoutBalanceMinor = 80_000
            fake.sendPayout(merchant, payout)
            assertEquals(30_000L, fake.payoutBalanceMinor)

            fake.failPayout("order-9", "CANCELLED")

            assertEquals(PayoutStatus.Failed("CANCELLED"), lookUp())
            assertEquals(80_000L, fake.payoutBalanceMinor)
        }

    @Test
    fun `unknown, then found by our own reference`() =
        runBlocking<Unit> {
            fake.nextPayout = FakePayout.UNKNOWN_SENT
            assertEquals(PayoutResult.Unknown, fake.sendPayout(merchant, payout))

            assertEquals(PayoutStatus.Pending, lookUp(providerReference = null), "the provider took it after all")
            fake.completePayout("order-9")
            assertIs<PayoutStatus.Paid>(lookUp(providerReference = null))
            assertEquals(1, fake.payouts.size)
        }

    @Test
    fun `unknown and never sent is not found, by the reference that was asked with`() =
        runBlocking<Unit> {
            fake.nextPayout = FakePayout.UNKNOWN_NOT_SENT
            assertEquals(PayoutResult.Unknown, fake.sendPayout(merchant, payout))

            assertEquals(PayoutStatus.NotFound(PayoutReference.OURS), lookUp(providerReference = null))
            assertEquals(PayoutStatus.NotFound(PayoutReference.PROVIDER), lookUp("PO-404"))
            assertEquals(emptyList(), fake.payouts.toList())
            assertEquals(2, fake.payoutLookups.size)
        }

    @Test
    fun `refusals send nothing - rejected, unavailable and a balance that can't cover it`() =
        runBlocking<Unit> {
            fake.nextPayout = FakePayout.REJECT
            assertEquals(PayoutResult.Rejected(PayoutRejection.REFUSED), fake.sendPayout(merchant, payout))
            fake.nextPayout = FakePayout.UNAVAILABLE
            assertEquals(PayoutResult.Unavailable, fake.sendPayout(merchant, payout))
            fake.nextPayout = FakePayout.INSUFFICIENT_BALANCE
            assertEquals(PayoutResult.InsufficientBalance, fake.sendPayout(merchant, payout))

            fake.nextPayout = FakePayout.ACCEPT
            fake.payoutBalanceMinor = 49_999
            assertEquals(PayoutResult.InsufficientBalance, fake.sendPayout(merchant, payout))

            assertEquals(emptyList(), fake.payouts.toList())
            assertEquals(49_999L, fake.payoutBalanceMinor)
        }

    @Test
    fun `a duplicate callback rings the bell twice for the same payout`() =
        runBlocking<Unit> {
            val rung = CopyOnWriteArrayList<Pair<String, String>>()
            val listener = listener { key, reference -> rung += key to reference }
            fake.sendPayout(merchant, payout)

            val results = fake.ringPayout(listener, "crynce-9fQ2xLr7Tk3mVb8s", "order-9", times = 2)

            assertEquals(List(2) { "crynce-9fQ2xLr7Tk3mVb8s" to "order-9" }, rung.toList())
            assertEquals(2, results.count { it is Resource.Success })
        }

    @Test
    fun `balances come with their source, and an unreadable payout balance is null`() =
        runBlocking<Unit> {
            val read = (fake.balances(merchant, "63") as Resource.Success).data
            assertEquals(FiatAmount(10_000_000, "KES"), read.payout?.amount)
            assertEquals(FiatAmount(100_000, "KES"), read.fees?.amount)
            assertEquals("fake", read.payout?.source)

            fake.payoutBalanceMinor = null
            assertNull((fake.balances(merchant, "63") as Resource.Success).data.payout)

            fake.failure = IntegrationError.Unavailable("PayHero")
            assertIs<Resource.Error>(fake.balances(merchant, "63"))
            assertEquals(PayoutStatus.Unknown, lookUp())
        }

    @Test
    fun `the log provider completes a payout after its delay and rings the payout bell with the address's key`() =
        runBlocking<Unit> {
            val rung = CompletableDeferred<Pair<String, String>>()
            val log =
                LogCollections(
                    payoutDelay = Duration.ofMillis(50),
                    notifications = { listener { key, reference -> rung.complete(key to reference) } },
                )

            assertIs<PayoutResult.Accepted>(log.sendPayout(merchant, payout))
            assertEquals(PayoutStatus.Pending, log.lookUpPayout(merchant, PayoutLookup("order-9", "PO-1")))

            assertEquals("crynce-9fQ2xLr7Tk3mVb8s" to "order-9", withTimeout(5_000) { rung.await() })
            assertIs<PayoutStatus.Paid>(log.lookUpPayout(merchant, PayoutLookup("order-9", "PO-1")))
        }

    @Test
    fun `the fake fees answer what the test set, or unknown`() =
        runBlocking<Unit> {
            val fees = FakeProviderFees()
            fees.payoutFeeMinor = 941
            val amount = FiatAmount.ofWholeUnits(300, "KES")

            assertEquals(ProviderFee(FiatAmount(941, "KES"), FeeSource.LIVE_TABLE), fees.payoutFee("payhero", amount))
            assertEquals(ProviderFee(FiatAmount(0, "KES"), FeeSource.LIVE_TABLE), fees.collectionFee("payhero", amount))

            fees.collectionFeeMinor = null
            assertEquals(ProviderFee.UNKNOWN, fees.collectionFee("payhero", amount))
            fees.source = FeeSource.UNKNOWN
            assertEquals(ProviderFee.UNKNOWN, fees.payoutFee("payhero", amount))
            assertEquals(4, fees.asked.size)
        }

    private fun listener(onPayout: (String, String) -> Unit): CollectionNotifications =
        object : CollectionNotifications {
            override suspend fun onPaymentCallback(callbackKey: String, reference: String): Resource<Unit> =
                Resource.success(Unit)

            override suspend fun onPayoutCallback(callbackKey: String, reference: String): Resource<Unit> {
                onPayout(callbackKey, reference)
                return Resource.success(Unit)
            }
        }
}
