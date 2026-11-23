package com.circaso.integrations.impl.payhero

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.CollectionRequest
import com.circaso.integrations.api.CollectionState
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.PayoutRequest
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.text
import com.circaso.shared.FiatAmount
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PayHeroCollectionsTest {
    private var status = 201
    private var reply = """{"success":true,"status":"QUEUED","reference":"E8UWT7CLUW","CheckoutRequestID":"ws_CO_1"}"""
    private var down = false

    private val clients = FakeHttpClients { _ -> if (down) throw IOException("down") else json(reply, status) }
    private val payHero = PayHeroCollections(PayHeroHosts.of(clients))
    private val merchant = CollectionCredentials("merchant-user", "merchant-pass", "911")
    private val payer = PhoneNumber.parse("+254712345678")!!
    private val prompt =
        CollectionRequest(payer, FiatAmount.ofWholeUnits(1_300, "KES"), "order-1", "https://api.test/v1/hooks/payhero")

    @Test
    fun `the prompt goes to PayHero with the merchant's own credentials and no idempotency key`() =
        runBlocking<Unit> {
            val receipt = assertIs<Resource.Success<*>>(payHero.requestPayment(merchant, prompt)).data

            assertEquals("E8UWT7CLUW", (receipt as com.circaso.integrations.api.CollectionReceipt).providerReference)
            assertEquals("ws_CO_1", receipt.checkoutId)
            val request = clients.last
            assertEquals("https://backend.payhero.co.ke/api/v2/payments", request.url.toString())
            val basic = Base64.getEncoder().encodeToString("merchant-user:merchant-pass".toByteArray())
            assertEquals("Basic $basic", request.headers["Authorization"])
            assertNull(request.headers["Idempotency-Key"], "no key, so the shared client never repeats a prompt")
            assertEquals(
                """{"amount":1300,"phone_number":"0712345678","channel_id":911,"provider":"m-pesa",""" +
                    """"external_reference":"order-1","callback_url":"https://api.test/v1/hooks/payhero"}""",
                request.text(),
            )
        }

    @Test
    fun `an amount with cents, another currency or a channel that isn't a number is refused without calling PayHero`() =
        runBlocking<Unit> {
            val cents = prompt.copy(amount = FiatAmount(130_050, "KES"))
            val dollars = prompt.copy(amount = FiatAmount.ofWholeUnits(10, "USD"))
            val badChannel = CollectionCredentials("u", "p", "till-one")

            assertIs<IntegrationError.Rejected>((payHero.requestPayment(merchant, cents) as Resource.Error).error)
            assertIs<IntegrationError.Rejected>((payHero.requestPayment(merchant, dollars) as Resource.Error).error)
            assertIs<IntegrationError.Rejected>((payHero.requestPayment(badChannel, prompt) as Resource.Error).error)
            assertTrue(clients.requests.isEmpty())
        }

    @Test
    fun `the status is asked by PayHero's reference and read in our words`() =
        runBlocking<Unit> {
            status = 200
            reply =
                """{"transaction_date":"2026-11-22T18:00:00Z","provider":"m-pesa","success":true,""" +
                """"status":"SUCCESS",""" +
                """"reference":"E8UWT7CLUW","provider_reference":"SKL4XYZ123","external_reference":"order-1",""" +
                """"amount":1300,"phone_number":"254712345678"}"""

            val paid = (payHero.paymentStatus(merchant, "E8UWT7CLUW") as Resource.Success).data

            assertEquals("/api/v2/transaction-status", clients.last.url.encodedPath)
            assertEquals("E8UWT7CLUW", clients.last.url.parameters["reference"])
            assertEquals(CollectionState.SUCCESS, paid.state)
            assertEquals(FiatAmount.ofWholeUnits(1_300, "KES"), paid.amount)
            assertEquals(payer, paid.payer)
            assertEquals("order-1", paid.reference)
            assertEquals("SKL4XYZ123", paid.receipt)
        }

    @Test
    fun `a status with only what PayHero documents leaves the rest null`() =
        runBlocking<Unit> {
            status = 200
            reply = """{"status":"QUEUED","reference":"E8UWT7CLUW","CheckoutRequestID":"ws_CO_1"}"""

            val queued = (payHero.paymentStatus(merchant, "E8UWT7CLUW") as Resource.Success).data

            assertEquals(CollectionState.QUEUED, queued.state)
            assertNull(queued.amount)
            assertNull(queued.payer)
            assertNull(queued.reference)
            assertNull(queued.receipt)
        }

    @Test
    fun `a status PayHero doesn't document is Unavailable, never read as paid or failed`() =
        runBlocking<Unit> {
            status = 200
            reply = """{"status":"REVERSED","reference":"E8UWT7CLUW"}"""

            assertIs<IntegrationError.Unavailable>(
                (payHero.paymentStatus(merchant, "E8UWT7CLUW") as Resource.Error).error,
            )
        }

    @Test
    fun `refused credentials are Rejected, an outage or a server error is Unavailable`() =
        runBlocking<Unit> {
            status = 401
            reply = """{"error_message":"Invalid request"}"""
            assertIs<IntegrationError.Rejected>((payHero.verify(merchant) as Resource.Error).error)

            status = 503
            assertIs<IntegrationError.Unavailable>((payHero.requestPayment(merchant, prompt) as Resource.Error).error)

            down = true
            assertIs<IntegrationError.Unavailable>((payHero.paymentStatus(merchant, "x") as Resource.Error).error)
        }

    @Test
    fun `the superseded payout calls send nothing - payouts go through sendPayout and lookUpPayout`() =
        runBlocking<Unit> {
            val payout =
                PayoutRequest(payer, FiatAmount.ofWholeUnits(500, "KES"), "order-2", "https://api.test/hook", "63")

            assertIs<IntegrationError.NotImplemented>((payHero.payout(merchant, payout) as Resource.Error).error)
            assertIs<IntegrationError.NotImplemented>((payHero.payoutStatus(merchant, "x") as Resource.Error).error)
            assertTrue(clients.requests.isEmpty())
            assertEquals("PayoutRequest(order-2, ***)", payout.toString())
        }

    @Test
    fun `a prompt refused for the merchant's empty service wallet is told apart from any other refusal`() =
        runBlocking<Unit> {
            status = 400
            reply =
                """{"error_message":"merchant has insufficient balance to allow you complete this transaction",""" +
                """"status_code":400}"""
            assertIs<IntegrationError.ServiceBalanceLow>(
                (payHero.requestPayment(merchant, prompt) as Resource.Error).error,
            )

            reply = """{"error_message":"The passed channel is not active"}"""
            assertIs<IntegrationError.Rejected>((payHero.requestPayment(merchant, prompt) as Resource.Error).error)

            status = 502
            assertIs<IntegrationError.Unavailable>((payHero.requestPayment(merchant, prompt) as Resource.Error).error)
        }

    @Test
    fun `the service wallet's balance is read as the fees balance, as a number or as text`() =
        runBlocking<Unit> {
            status = 200
            reply =
                """{"id":7,"account_id":12,"wallet_type":"service_wallet","available_balance":49.5,"currency":"KES"}"""

            val balances = (payHero.balance(merchant) as Resource.Success).data

            assertEquals("/api/v2/wallets", clients.last.url.encodedPath)
            assertEquals("service_wallet", clients.last.url.parameters["wallet_type"])
            assertEquals(FiatAmount(4_950, "KES"), balances.fees)
            assertEquals(FiatAmount(0, "KES"), balances.payout)

            reply = """{"wallet_type":"service_wallet","available_balance":"120"}"""
            assertEquals(FiatAmount(12_000, "KES"), (payHero.balance(merchant) as Resource.Success).data.fees)
        }

    @Test
    fun `a balance PayHero doesn't give, or can't be asked for, is an error and never a zero`() =
        runBlocking<Unit> {
            status = 200
            reply = """{"wallet_type":"service_wallet"}"""
            assertIs<IntegrationError.Unavailable>((payHero.balance(merchant) as Resource.Error).error)

            status = 401
            assertIs<IntegrationError.Rejected>((payHero.balance(merchant) as Resource.Error).error)

            down = true
            assertIs<IntegrationError.Unavailable>((payHero.balance(merchant) as Resource.Error).error)
        }

    @Test
    fun `a refusal's reason is PayHero's own words, with any number masked and cut short`() {
        assertEquals(
            "The passed channel is not active",
            PayHeroCollections.refusalReason("""{"error_message":"The passed channel is not active"}"""),
        )
        assertEquals(
            "INVALID: phone # is not valid",
            PayHeroCollections.refusalReason(
                """{"error_code":"INVALID","error_message":"phone 254712345678 is  not valid","status_code":400}""",
            ),
        )
        assertEquals("not given", PayHeroCollections.refusalReason("<html>Bad Request</html>"))
        assertEquals("not given", PayHeroCollections.refusalReason("{}"))
        assertEquals(160, PayHeroCollections.refusalReason("""{"error_message":"${"x".repeat(400)}"}""").length)
    }

    @Test
    fun `credentials never show in a string`() {
        assertEquals("CollectionCredentials(***)", merchant.toString())
    }
}
