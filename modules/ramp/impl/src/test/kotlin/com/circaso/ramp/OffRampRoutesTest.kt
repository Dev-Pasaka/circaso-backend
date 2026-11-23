package com.circaso.ramp

import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.presentation.controllers.rampController
import com.circaso.ramp.presentation.services.RampService
import com.circaso.shared.DeviceId
import com.circaso.shared.Scope
import com.circaso.web.CallPrincipal
import com.circaso.web.CallPrincipalKey
import com.circaso.web.installJson
import com.circaso.web.installRequestId
import com.circaso.web.installStatusPages
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Selling on the app's routes (decision 2026-11-23): R2 for a sale, R7, R1's `offRamp`, R4 with the deposit to make,
 * and R6 before the transfer, in the shapes `docs/mobile-integration.md` gives the app.
 */
class OffRampRoutesTest {
    private val ramp = Harness()
    private val service =
        RampService(
            ramp.status,
            ramp.createQuote,
            ramp.startOnRamp,
            ramp.getOrder,
            ramp.listOrders,
            ramp.cancelOrder,
            ramp.startKyc,
            ramp.getKyc,
            ramp.startOffRamp,
        )

    private fun ApplicationTestBuilder.serve() {
        application {
            installRequestId()
            installJson()
            installStatusPages()
            intercept(ApplicationCallPipeline.Plugins) {
                val principal = CallPrincipal(ramp.amina, DeviceId.new(), UUID.randomUUID(), Scope.APP)
                call.attributes.put(CallPrincipalKey, principal)
            }
            routing { route("/v1") { rampController(service) } }
        }
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        header("X-Wallet-Network", "TESTNET")
        setBody(body)
    }

    private suspend fun HttpResponse.envelope(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.data(): JsonObject = envelope().getValue("data").jsonObject

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonElement.amount(): Triple<String, String, String> =
        jsonObject.let { Triple(it.text("unit"), it.text("amountMinor"), it.text("amount")) }

    private suspend fun ApplicationTestBuilder.quote(body: String): HttpResponse =
        client.post("/v1/ramp/quotes") { json(body) }

    @Test
    fun `R2 prices a sale in USDC sent, the shillings received and both fees`() =
        testApplication {
            serve()
            ramp.openSelling()

            val sale = quote("""{"direction":"OFF_RAMP","blockchain":"BASE-SEPOLIA","crypto":{"amount":"10"}}""")

            assertEquals(HttpStatusCode.OK, sale.status, sale.bodyAsText())
            val data = sale.data()
            assertEquals("OFF_RAMP", data.text("direction"))
            assertEquals(Triple("USDC", "10000000", "10"), data.getValue("pay").amount())
            assertEquals(Triple("KES", "126900", "1269"), data.getValue("receive").amount())
            assertEquals(Triple("KES", "2600", "26"), data.getValue("fee").amount())
            assertEquals(Triple("KES", "500", "5"), data.getValue("providerFee").amount())
            assertEquals(Triple("KES", "130000", "1300"), data.getValue("worth").amount())

            val buy = quote("""{"direction":"ON_RAMP","blockchain":"BASE-SEPOLIA","fiat":{"amount":"1300"}}""").data()
            assertEquals(JsonNull, buy.getValue("providerFee"), "a purchase has no payout fee")
        }

    @Test
    fun `R7 answers the deposit to make, R4 shows it until the USDC arrives, and the receipt once paid`() =
        testApplication {
            serve()
            ramp.openSelling()
            val quoteId =
                quote("""{"direction":"OFF_RAMP","blockchain":"BASE-SEPOLIA","crypto":{"amount":"10"}}""")
                    .data()
                    .text("quoteId")

            val sold =
                client.post(
                    "/v1/ramp/offramp/orders",
                ) { json("""{"quoteId":"$quoteId","phone":"${ramp.phone.e164}"}""") }

            assertEquals(HttpStatusCode.OK, sold.status, sold.bodyAsText())
            val order = sold.data()
            assertEquals("AWAITING_TRANSFER", order.text("state"))
            val deposit = order.getValue("deposit").jsonObject
            assertEquals(ramp.walletOf(ramp.crynce).address, deposit.text("address"))
            assertEquals("BASE-SEPOLIA" to "10000000", deposit.text("blockchain") to deposit.text("amountMinor"))
            assertEquals("USDC", deposit.text("asset"))
            assertEquals(Triple("KES", "500", "5"), order.getValue("providerFee").amount())
            assertEquals("Crynce", order.text("merchantName"))

            val orderId = order.text("orderId")
            ramp.transfer(ramp.orders.rows.getValue(RampOrderId.parse(orderId)!!))
            val paying = client.get("/v1/ramp/orders/$orderId") { json("") }.data()
            assertEquals("PAYING_OUT", paying.text("state"))
            assertEquals(JsonNull, paying.getValue("deposit"), "nothing more to send")

            ramp.collections.payoutPaid(orderId, receipt = "QPAPP1")
            ramp.payoutCallback(ramp.orders.rows.getValue(RampOrderId.parse(orderId)!!))
            val done = client.get("/v1/ramp/orders/$orderId") { json("") }.data()
            assertEquals("COMPLETED" to "QPAPP1", done.text("state") to done.text("receipt"))
        }

    @Test
    fun `R1 says selling is open and a sale can start, R6 cancels before the transfer`() =
        testApplication {
            serve()
            ramp.openSelling()

            val status = client.get("/v1/ramp") { json("") }.data()
            val offRamp = status.getValue("offRamp").jsonObject
            assertTrue(
                offRamp.getValue("open").jsonPrimitive.boolean && offRamp.getValue("canSell").jsonPrimitive.boolean,
            )
            assertTrue(status.getValue("offRampAvailable").jsonPrimitive.boolean)
            assertEquals(
                Triple("KES", "1000", "10"),
                offRamp
                    .getValue("limits")
                    .jsonObject
                    .getValue("minOrder")
                    .amount(),
            )

            val order = ramp.sale()
            val cancelled = client.post("/v1/ramp/orders/${order.orderId}/cancel") { json("{}") }
            assertEquals("CANCELLED", cancelled.data().text("state"), cancelled.bodyAsText())
        }

    @Test
    fun `selling closed answers ramp offramp_unavailable on R2 and R7`() =
        testApplication {
            serve()

            val closed = quote("""{"direction":"OFF_RAMP","blockchain":"BASE-SEPOLIA","crypto":{"amount":"10"}}""")

            assertEquals(HttpStatusCode.Conflict, closed.status)
            assertEquals("ramp.offramp_unavailable", closed.envelope().text("code"))
        }
}
