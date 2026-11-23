package com.circaso.integrations.impl.payhero

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.TopUpLookup
import com.circaso.integrations.api.TopUpReceipt
import com.circaso.integrations.api.TopUpRejection
import com.circaso.integrations.api.TopUpRequest
import com.circaso.integrations.api.TopUpResult
import com.circaso.integrations.api.TopUpStatus
import com.circaso.integrations.api.TopUpWallet
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.API
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.API_STATUS
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.LEGACY
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.LEGACY_STATUS
import com.circaso.integrations.impl.text
import com.circaso.shared.FiatAmount
import com.circaso.shared.PhoneNumber
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A merchant's top-ups of their own three wallets: each to its own host, sent once, every answer told apart. */
class PayHeroTopUpsTest {
    private val vendor = FakePayHero()
    private val payHero = PayHeroCollections(vendor.hosts)
    private val merchant = CollectionCredentials("merchant-user", "merchant-pass", "911")
    private val service =
        TopUpRequest(
            wallet = TopUpWallet.COLLECTION_FEES,
            payer = PhoneNumber.parse("+254787677676")!!,
            amount = FiatAmount.ofWholeUnits(100, "KES"),
            reference = "topup-1",
            callbackUrl = "https://api.test/v1/hooks/payhero/crynce-9fQ2xLr7Tk3mVb8s",
            accountId = "63",
        )
    private val payment = service.copy(wallet = TopUpWallet.PAYOUT, reference = "topup-2")
    private val finoqService = service.copy(wallet = TopUpWallet.PAYOUT_FEES, reference = "topup-3")
    private val queued =
        """{"success":true,"status":"QUEUED","reference":"09b8794f-4da0","CheckoutRequestID":"ws_CO_15012024161614"}"""

    private fun send(request: TopUpRequest): TopUpResult = runBlocking { payHero.requestTopUp(merchant, request) }

    private fun lookUp(lookup: TopUpLookup): TopUpStatus = runBlocking { payHero.lookUpTopUp(merchant, lookup) }

    @Test
    fun `the service wallet's top-up is the documented call on the legacy host, with the merchant's keys`() {
        vendor.answer("$LEGACY$SERVICE_PATH", queued)

        assertEquals(TopUpResult.Accepted(TopUpReceipt("09b8794f-4da0", "ws_CO_15012024161614")), send(service))

        assertEquals(listOf("$LEGACY$SERVICE_PATH"), vendor.asked)
        val request = vendor.clients.last
        val basic = Base64.getEncoder().encodeToString("merchant-user:merchant-pass".toByteArray())
        assertEquals("Basic $basic", request.headers["Authorization"])
        assertNull(request.headers["Idempotency-Key"])
        assertEquals("""{"amount":100,"phone_number":"0787677676"}""", request.text())
    }

    @Test
    fun `the payment wallet's top-up is a wallet-based collection on FinoQ's host, with our reference`() {
        vendor.answer("$API$PAYMENT_PATH", queued.replace("}", ""","external_reference":"topup-2"}"""), 201)

        assertEquals(TopUpResult.Accepted(TopUpReceipt("09b8794f-4da0", "ws_CO_15012024161614")), send(payment))

        assertEquals(listOf("$API$PAYMENT_PATH"), vendor.asked)
        assertEquals(
            """{"amount":100,"phone_number":"0787677676","provider":"sasapay","network_code":"63902",""" +
                """"account_id":63,"external_reference":"topup-2",""" +
                """"callback_url":"https://api.test/v1/hooks/payhero/crynce-9fQ2xLr7Tk3mVb8s"}""",
            vendor.clients.last.text(),
        )
    }

    @Test
    fun `FinoQ's service wallet is topped up on FinoQ's host only, naming the account`() {
        vendor.answer("$API$SERVICE_PATH", queued, 201)

        assertEquals(TopUpResult.Accepted(TopUpReceipt("09b8794f-4da0", "ws_CO_15012024161614")), send(finoqService))

        assertEquals(listOf("$API$SERVICE_PATH"), vendor.asked)
        assertEquals("""{"amount":100,"phone_number":"0787677676","account_id":63}""", vendor.clients.last.text())
    }

    @Test
    fun `a FinoQ service top-up FinoQ has no route for is not supported, and never goes to PayHero's wallet`() {
        vendor.answer("$API$SERVICE_PATH", "", 404)
        vendor.answer("$LEGACY$SERVICE_PATH", queued)

        assertEquals(TopUpResult.Rejected(TopUpRejection.NOT_SUPPORTED), send(finoqService))
        assertEquals(listOf("$API$SERVICE_PATH"), vendor.asked, "PayHero's top-up would fill the other wallet")

        vendor.answer("$API$SERVICE_PATH", "", 403)
        assertEquals(TopUpResult.Rejected(TopUpRejection.CREDENTIALS_REFUSED), send(finoqService))
        vendor.fail("$API$SERVICE_PATH") { SocketTimeoutException("read timed out") }
        assertEquals(TopUpResult.Unknown, send(finoqService), "it may have prompted")
        assertEquals(TopUpResult.Rejected(TopUpRejection.INVALID_REQUEST), send(finoqService.copy(accountId = null)))
    }

    @Test
    fun `cents, another currency or a payment top-up without a numeric account is refused without a call`() {
        listOf(
            service.copy(amount = FiatAmount(10_050, "KES")),
            service.copy(amount = FiatAmount.ofWholeUnits(100, "USD")),
            payment.copy(accountId = null),
            payment.copy(accountId = "team-a"),
        ).forEach { assertEquals(TopUpResult.Rejected(TopUpRejection.INVALID_REQUEST), send(it)) }
        assertEquals(emptyList(), vendor.asked)
    }

    @Test
    fun `the legacy host is asked only when FinoQ's provably took nothing`() {
        vendor.answer("$API$PAYMENT_PATH", "", 404)
        vendor.answer("$LEGACY$PAYMENT_PATH", queued)
        assertEquals(TopUpResult.Accepted(TopUpReceipt("09b8794f-4da0", "ws_CO_15012024161614")), send(payment))

        vendor.fail("$API$PAYMENT_PATH") { SocketTimeoutException("read timed out") }
        assertEquals(TopUpResult.Unknown, send(payment), "it may have prompted: no second host")
        assertEquals(listOf("$API$PAYMENT_PATH", "$LEGACY$PAYMENT_PATH", "$API$PAYMENT_PATH"), vendor.asked)
    }

    @Test
    fun `every answer is told apart, and a 200 that names no prompt is Unknown`() {
        val cases =
            listOf(
                Triple(500, """{"error_message":"upstream"}""", TopUpResult.Unknown),
                Triple(200, """{"success":false,"status":"FAILED"}""", TopUpResult.Unknown),
                Triple(200, """{"status":"QUEUED"}""", TopUpResult.Unknown),
                Triple(429, "{}", TopUpResult.Unavailable),
                Triple(
                    400,
                    """{"error_message":"merchant has insufficient balance to allow you complete this transaction"}""",
                    TopUpResult.Rejected(TopUpRejection.SERVICE_BALANCE_LOW),
                ),
                Triple(
                    400,
                    """{"error_message":"You can only top up using your own phone number"}""",
                    TopUpResult.Rejected(TopUpRejection.PAYER_NOT_ALLOWED),
                ),
                Triple(
                    400,
                    """{"error_code":"invalid_arguments","invalid_arguments":[{"field":"phone_number"}]}""",
                    TopUpResult.Rejected(TopUpRejection.INVALID_REQUEST),
                ),
                Triple(400, """{"error_message":"Something else"}""", TopUpResult.Rejected(TopUpRejection.REFUSED)),
            )
        for ((status, body, expected) in cases) {
            vendor.answer("$LEGACY$SERVICE_PATH", body, status)
            assertEquals(expected, send(service), "$status $body")
        }
        vendor.answer("$LEGACY$SERVICE_PATH", "", 401)
        assertEquals(TopUpResult.Rejected(TopUpRejection.CREDENTIALS_REFUSED), send(service))
        vendor.fail("$LEGACY$SERVICE_PATH") { ConnectException("refused") }
        assertEquals(TopUpResult.Unavailable, send(service))
    }

    @Test
    fun `the status is FinoQ's lookups, and only the payment wallet's answer is checked against ours`() {
        vendor.answer("$API$API_STATUS", "", 404)
        vendor.answer(
            "$LEGACY$LEGACY_STATUS",
            """{"status":"SUCCESS","provider_reference":"QK12345","amount":100,"external_reference":"other"}""",
        )

        val serviceStatus = lookUp(TopUpLookup(TopUpWallet.COLLECTION_FEES, "topup-1", "09b8794f-4da0"))
        assertEquals(TopUpStatus.Paid("QK12345", FiatAmount.ofWholeUnits(100, "KES")), serviceStatus)
        assertEquals(TopUpStatus.Unknown, lookUp(TopUpLookup(TopUpWallet.PAYOUT, "topup-2", "09b8794f-4da0")))

        vendor.answer("$LEGACY$LEGACY_STATUS", """{"status":"FAILED","external_reference":"topup-2"}""")
        assertEquals(TopUpStatus.Failed("FAILED"), lookUp(TopUpLookup(TopUpWallet.PAYOUT, "topup-2", "ref")))
        vendor.answer("$LEGACY$LEGACY_STATUS", """{"status":"QUEUED"}""")
        assertEquals(TopUpStatus.Pending, lookUp(TopUpLookup(TopUpWallet.COLLECTION_FEES, "topup-1", "ref")))
    }

    private companion object {
        const val SERVICE_PATH = "/api/v2/topup"
        const val PAYMENT_PATH = "/api/v2/payments"
    }
}
