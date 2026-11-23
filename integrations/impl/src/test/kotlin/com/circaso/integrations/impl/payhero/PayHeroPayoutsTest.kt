package com.circaso.integrations.impl.payhero

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.PayoutLookup
import com.circaso.integrations.api.PayoutReceipt
import com.circaso.integrations.api.PayoutReference
import com.circaso.integrations.api.PayoutRejection
import com.circaso.integrations.api.PayoutRequest
import com.circaso.integrations.api.PayoutResult
import com.circaso.integrations.api.PayoutStatus
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.API
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.API_STATUS
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.LEGACY
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.LEGACY_STATUS
import com.circaso.integrations.impl.payhero.FakePayHero.Companion.WITHDRAW
import com.circaso.integrations.impl.text
import com.circaso.shared.FiatAmount
import com.circaso.shared.PhoneNumber
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.ConnectException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PayHeroPayoutsTest {
    private val vendor = FakePayHero()
    private val payHero = PayHeroCollections(vendor.hosts)
    private val merchant = CollectionCredentials("merchant-user", "merchant-pass", "911")
    private val payee = PhoneNumber.parse("+254712345678")!!
    private val payout =
        PayoutRequest(
            payee = payee,
            amount = FiatAmount.ofWholeUnits(10, "KES"),
            reference = "order-9",
            callbackUrl = "https://api.test/v1/hooks/payhero/crynce-9fQ2xLr7Tk3mVb8s",
            accountId = "63",
        )
    private val queued =
        """{"status":"QUEUED","merchant_reference":"UFD003742176.eO","checkout_request_id":"0aaef9ef-15a6",""" +
            """"response_code":"200","conversation_id":"UFD003742176.eO","external_reference":"order-9"}"""

    private fun send(request: PayoutRequest = payout): PayoutResult =
        runBlocking { payHero.sendPayout(merchant, request) }

    private fun lookUp(providerReference: String? = "UFD003742176.eO"): PayoutStatus =
        runBlocking { payHero.lookUpPayout(merchant, PayoutLookup("order-9", providerReference)) }

    @Test
    fun `a payout goes to FinoQ's withdraw once, as documented, with the merchant's own credentials`() {
        vendor.answer("$API$WITHDRAW", queued)

        val accepted = assertIs<PayoutResult.Accepted>(send())

        assertEquals(PayoutReceipt("UFD003742176.eO", "0aaef9ef-15a6"), accepted.receipt)
        assertEquals(listOf("$API$WITHDRAW"), vendor.asked, "one request, and no second host once one has answered")
        val request = vendor.clients.last
        val basic = Base64.getEncoder().encodeToString("merchant-user:merchant-pass".toByteArray())
        assertEquals("Basic $basic", request.headers["Authorization"])
        assertNull(request.headers["Idempotency-Key"])
        assertEquals(
            """{"external_reference":"order-9","account_id":63,"amount":10,"phone_number":"0712345678",""" +
                """"account_number":"0712345678","network_code":"63902","channel":"phone",""" +
                """"recipient_type":"MSISDN","callback_url":"https://api.test/v1/hooks/payhero/crynce-9fQ2xLr7Tk3mVb8s"}""",
            request.text(),
        )
    }

    @Test
    fun `the payout clients never retry, because a repeated payout may be paid twice`() {
        assertEquals(
            1,
            vendor.clients.configs
                .getValue("finoq-payout")
                .maxAttempts,
        )
        assertEquals(
            1,
            vendor.clients.configs
                .getValue("payhero-payout")
                .maxAttempts,
        )
        assertEquals(
            PayHeroHosts.API_URL,
            vendor.clients.configs
                .getValue("finoq-payout")
                .baseUrl,
        )
        assertEquals(
            PayHeroHosts.LEGACY_URL,
            vendor.clients.configs
                .getValue("payhero-payout")
                .baseUrl,
        )
    }

    @Test
    fun `cents, another currency, nothing at all or an account id that isn't a number is refused without a call`() {
        val refused =
            listOf(
                payout.copy(amount = FiatAmount(1_050, "KES")),
                payout.copy(amount = FiatAmount.ofWholeUnits(10, "USD")),
                payout.copy(amount = FiatAmount(0, "KES")),
                payout.copy(accountId = "team-a"),
                payout.copy(accountId = ""),
            )

        refused.forEach { assertEquals(PayoutResult.Rejected(PayoutRejection.INVALID_REQUEST), send(it)) }
        assertEquals(emptyList(), vendor.asked)
    }

    @Test
    fun `a timeout is Unknown, never Unavailable, and no other host is tried`() {
        vendor.fail("$API$WITHDRAW") { HttpRequestTimeoutException("https://api.finoq.africa/api/v2/withdraw", 15_000) }
        assertEquals(PayoutResult.Unknown, send())

        vendor.fail("$API$WITHDRAW") { SocketTimeoutException("read timed out") }
        assertEquals(PayoutResult.Unknown, send())

        vendor.fail("$API$WITHDRAW") { IOException("connection reset") }
        assertEquals(PayoutResult.Unknown, send())

        assertEquals(List(3) { "$API$WITHDRAW" }, vendor.asked, "it may have been sent: the legacy host is not asked")
    }

    @Test
    fun `a server error or a 200 that names no payout is Unknown too`() {
        for (status in listOf(500, 502, 503, 504, 408)) {
            vendor.answer("$API$WITHDRAW", """{"error_message":"upstream"}""", status)
            assertEquals(PayoutResult.Unknown, send(), "$status")
        }

        vendor.answer("$API$WITHDRAW", """{"status":"QUEUED","response_code":"200"}""")
        assertEquals(PayoutResult.Unknown, send())

        vendor.answer("$API$WITHDRAW", "<html>ok</html>")
        assertEquals(PayoutResult.Unknown, send())
        assertEquals(setOf("$API$WITHDRAW"), vendor.asked.toSet())
    }

    @Test
    fun `a host that could not be reached took nothing, so the legacy host is tried, and both down is Unavailable`() {
        vendor.fail("$API$WITHDRAW") { ConnectException("refused") }
        vendor.answer("$LEGACY$WITHDRAW", queued)
        assertIs<PayoutResult.Accepted>(send())
        assertEquals(listOf("$API$WITHDRAW", "$LEGACY$WITHDRAW"), vendor.asked)

        vendor.fail("$LEGACY$WITHDRAW") { CircuitOpenException("payhero-payout") }
        assertEquals(PayoutResult.Unavailable, send())

        vendor.answer("$LEGACY$WITHDRAW", """{"error_message":"Invalid request"}""", 401)
        assertEquals(PayoutResult.Unavailable, send(), "one host is down: the credentials are not judged on the other")
    }

    @Test
    fun `credentials one host refuses are tried on the other, and refused by both is a rejection`() {
        vendor.answer("$API$WITHDRAW", """{"error_message":"unauthorized"}""", 401)
        vendor.answer("$LEGACY$WITHDRAW", queued)
        assertIs<PayoutResult.Accepted>(send())

        vendor.answer("$LEGACY$WITHDRAW", "", 403)
        assertEquals(PayoutResult.Rejected(PayoutRejection.CREDENTIALS_REFUSED), send())
    }

    @Test
    fun `a host with no such route is passed over, but a 404 that carries a refusal is the answer`() {
        assertEquals(PayoutResult.Rejected(PayoutRejection.NOT_SUPPORTED), send(), "a bare 404 from both hosts")
        assertEquals(listOf("$API$WITHDRAW", "$LEGACY$WITHDRAW"), vendor.asked)

        vendor.answer("$API$WITHDRAW", """{"error_code":"not_found","error_message":"account not found"}""", 404)
        assertEquals(PayoutResult.Rejected(PayoutRejection.REFUSED), send())
        assertEquals("$API$WITHDRAW", vendor.asked.last())
    }

    @Test
    fun `a 4xx is a permanent rejection, told apart by PayHero's own words`() {
        fun refusedWith(body: String, status: Int = 400): PayoutResult {
            vendor.answer("$API$WITHDRAW", body, status)
            return send()
        }

        assertEquals(
            PayoutResult.Rejected(PayoutRejection.AMOUNT_BELOW_COST, FiatAmount(462, "KES")),
            refusedWith(
                """{"error_code":"invalid_argument","error_message":"the transaction amount is insufficient as """ +
                    """it wont cater for cost: (4.62)","status_code":400}""",
            ),
            "about the amount, not the merchant's balance; the cost is handed on",
        )
        assertEquals(
            PayoutResult.InsufficientBalance,
            refusedWith("""{"error_message":"merchant has insufficient balance to allow you complete this"}"""),
        )
        assertEquals(
            PayoutResult.InsufficientBalance,
            refusedWith("""{"error_code":"invalid_argument","error_message":"Insufficient funds in wallet"}"""),
        )
        assertEquals(
            PayoutResult.Rejected(PayoutRejection.INVALID_REQUEST),
            refusedWith(
                """{"error_code":"invalid_argument","error_message":"account_id is required","status_code":400,""" +
                    """"invalid_arguments":[{"field":"account_id","value":"","tag":"required","message":"x"}]}""",
            ),
        )
        assertEquals(
            PayoutResult.Rejected(PayoutRejection.REFUSED),
            refusedWith("""{"error_message":"full KYC information is required for the transaction"}""", 422),
        )
        assertEquals(PayoutResult.Rejected(PayoutRejection.REFUSED), refusedWith("<html>Bad Request</html>"))
        assertEquals(PayoutResult.Unavailable, refusedWith("""{"error_message":"slow down"}""", 429))
        assertEquals(setOf("$API$WITHDRAW"), vendor.asked.toSet(), "a refusal is final: no other host is asked")
    }

    @Test
    fun `the status is asked of the new lookup by the provider's reference`() {
        vendor.answer(
            "$API$API_STATUS",
            """{"success":true,"status":"success","reference":"UFD003742176.eO","external_reference":"order-9",""" +
                """"amount":10,"currency":"KES","transaction_type":"outbound_payment",""" +
                """"provider_reference":"UFUSB1OXWV","provider":"sasapay"}""",
        )

        val paid = assertIs<PayoutStatus.Paid>(lookUp())

        assertEquals("UFUSB1OXWV", paid.providerReceipt)
        assertEquals(FiatAmount(1_000, "KES"), paid.amount)
        assertEquals("order-9", paid.reference)
        assertEquals(listOf("$API$API_STATUS"), vendor.asked)
        assertEquals("""{"request_id":"UFD003742176.eO"}""", vendor.clients.last.text())
        assertEquals("Paid(***)", paid.toString())
    }

    @Test
    fun `FinoQ's M-Pesa layout is paid only with result code 0 and a receipt, and never read as failed`() {
        fun mpesa(code: String, receipt: String) =
            """{"CheckoutRequestID":"0aaef9ef-15a6","ErrorMessage":"","MerchantRequestID":"m-1",""" +
                """"RequestId":"UFD003742176.eO","ResponseCode":"0","ResponseDescription":"Accepted",""" +
                """"ResultCode":"$code","ResultDesc":"done","TransactionReceipt":"$receipt"}"""

        vendor.answer("$API$API_STATUS", mpesa("0", "TKN3H2X1QP"))
        val paid = assertIs<PayoutStatus.Paid>(lookUp())
        assertEquals("TKN3H2X1QP", paid.providerReceipt)
        assertNull(paid.amount, "the layout carries no amount")

        vendor.answer("$API$API_STATUS", mpesa("", ""))
        assertEquals(PayoutStatus.Pending, lookUp(), "no result yet")

        vendor.answer("$API$API_STATUS", mpesa("0", ""))
        assertIs<PayoutStatus.Unknown>(lookUp(), "code 0 without a receipt is not proof of payment")

        vendor.answer("$API$API_STATUS", mpesa("2001", ""))
        vendor.answer("$LEGACY$LEGACY_STATUS", "", 404)
        assertIs<PayoutStatus.Unknown>(lookUp(), "a non-zero code is never read as failed, which would allow a resend")
    }

    @Test
    fun `when the new lookup gives nothing the legacy one is asked, and its words are read the same`() {
        vendor.answer(
            "$LEGACY$LEGACY_STATUS",
            """{"transaction_date":"2026-11-23T10:00:00Z","provider":"m-pesa","success":true,"status":"SUCCESS",""" +
                """"reference":"UFD003742176.eO","third_party_reference":"SKL4XYZ123","amount":"10.00"}""",
        )
        val paid = assertIs<PayoutStatus.Paid>(lookUp())
        assertEquals("SKL4XYZ123", paid.providerReceipt)
        assertEquals(FiatAmount(1_000, "KES"), paid.amount)
        assertNull(paid.reference, "not echoed, so there is nothing to check it against")
        assertEquals(listOf("$API$API_STATUS", "$LEGACY$LEGACY_STATUS"), vendor.asked)
        assertEquals("UFD003742176.eO", vendor.clients.last.url.parameters["reference"])

        vendor.answer("$LEGACY$LEGACY_STATUS", """{"status":"QUEUED"}""")
        assertEquals(PayoutStatus.Pending, lookUp())

        vendor.answer("$LEGACY$LEGACY_STATUS", """{"status":"Failed"}""")
        assertEquals(PayoutStatus.Failed("FAILED"), lookUp())
    }

    @Test
    fun `a payout neither lookup knows is NotFound, by the reference that was asked with`() {
        assertEquals(PayoutStatus.NotFound(PayoutReference.PROVIDER), lookUp(), "bare 404s")
        assertEquals(
            listOf("$API$API_STATUS", "$LEGACY$LEGACY_STATUS", "$API$API_STATUS", "$LEGACY$LEGACY_STATUS"),
            vendor.asked,
            "by the provider's reference, then by ours",
        )

        vendor.answer("$API$API_STATUS", """{"error_code":"not_found","error_message":"transaction not found"}""", 400)
        vendor.answer("$LEGACY$LEGACY_STATUS", """{"success":false,"message":"No transaction matches"}""")
        assertEquals(PayoutStatus.NotFound(PayoutReference.OURS), lookUp(providerReference = null))
        assertEquals("""{"request_id":"order-9"}""", vendor.last("$API$API_STATUS").text())
        assertEquals("order-9", vendor.last("$LEGACY$LEGACY_STATUS").url.parameters["reference"])
    }

    @Test
    fun `after an Unknown send, a payout found by our own reference is read like any other`() {
        vendor.answer("$LEGACY$LEGACY_STATUS", """{"status":"QUEUED","external_reference":"order-9"}""")

        assertEquals(PayoutStatus.Pending, lookUp(providerReference = null))
    }

    @Test
    fun `an answer that can't be read is Unknown, never NotFound and never paid`() {
        vendor.answer("$API$API_STATUS", """{"error_message":"upstream"}""", 500)
        assertEquals(PayoutStatus.Unknown, lookUp(), "one source failed, so 'not found' can't be claimed")

        vendor.fail("$API$API_STATUS") { HttpRequestTimeoutException("https://api.finoq.africa", 15_000) }
        vendor.fail("$LEGACY$LEGACY_STATUS") { ConnectException("refused") }
        assertEquals(PayoutStatus.Unknown, lookUp())

        vendor.answer("$API$API_STATUS", "", 401)
        vendor.answer("$LEGACY$LEGACY_STATUS", "", 401)
        assertEquals(PayoutStatus.Unknown, lookUp())

        vendor.answer("$API$API_STATUS", """{"status":"SETTLING"}""")
        vendor.answer("$LEGACY$LEGACY_STATUS", """{"success":true}""")
        assertEquals(PayoutStatus.Unknown, lookUp(), "a word this client doesn't know, and a bare success flag")

        assertEquals(PayoutStatus.Unknown, runBlocking { payHero.lookUpPayout(merchant, PayoutLookup("", null)) })
    }

    @Test
    fun `a wrapper's own status is never read as the payout's`() {
        vendor.answer("$API$API_STATUS", """{"success":true,"status":"success","data":{"status":"PENDING"}}""")
        assertEquals(PayoutStatus.Pending, lookUp())

        vendor.answer("$API$API_STATUS", """{"success":true,"status":"success","data":{"state":"created"}}""")
        vendor.answer("$LEGACY$LEGACY_STATUS", """{"status":true,"response":{"Status":"Success","Amount":10}}""")
        val paid =
            assertIs<PayoutStatus.Paid>(lookUp(), "the legacy lookup's nested layout, after the new one was unread")
        assertEquals(FiatAmount(1_000, "KES"), paid.amount)

        vendor.answer("$LEGACY$LEGACY_STATUS", "", 404)
        assertEquals(PayoutStatus.Unknown, lookUp(), "'success' on the wrapper is the query's, not the payout's")
    }

    @Test
    fun `an answer about another reference is not used`() {
        vendor.answer("$API$API_STATUS", """{"status":"success","external_reference":"order-OTHER","amount":10}""")
        vendor.answer("$LEGACY$LEGACY_STATUS", "", 404)

        assertEquals(PayoutStatus.Unknown, lookUp())
    }

    @Test
    fun `refusals are logged with every digit masked and amounts are parsed exactly`() {
        assertEquals(
            "invalid_argument: the transaction amount is insufficient as it wont cater for cost: (#.#)",
            PayHeroPayouts.masked(
                PayHeroPayouts
                    .refusal(
                        """{"error_code":"invalid_argument","error_message":"the transaction amount is """ +
                            """insufficient as it wont cater for cost: (4.62)"}""",
                    ).said,
            ),
        )
        assertEquals("phone # is not valid", PayHeroPayouts.masked("phone 254712345678 is  not\nvalid"))
        assertEquals("-", PayHeroPayouts.masked(""))
        assertEquals(FiatAmount(1_510_136, "KES"), PayHeroPayouts.amountOf("15101.36", null))
        assertEquals(FiatAmount(1_000, "UGX"), PayHeroPayouts.amountOf(" 10 ", "ugx"))
        assertNull(PayHeroPayouts.amountOf("ten", "KES"))
        assertNull(PayHeroPayouts.amountOf(null, "KES"))
        assertEquals("IN_PROGRESS", PayHeroPayouts.word(" in-progress "))
    }
}
