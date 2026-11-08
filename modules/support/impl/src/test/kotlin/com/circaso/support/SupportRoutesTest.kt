package com.circaso.support

import com.circaso.shared.DeviceId
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.support.api.NewTicket
import com.circaso.support.api.TicketCategory
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketSource
import com.circaso.support.api.TicketStatus
import com.circaso.support.presentation.controllers.ticketController
import com.circaso.web.CallPrincipal
import com.circaso.web.CallPrincipalKey
import com.circaso.web.installJson
import com.circaso.web.installStatusPages
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SU1-SU4 through Ktor, as the app calls them, with the caller set as the gateway would. */
class SupportRoutesTest {
    private val amina = UserId.new()
    private val agent = UUID.randomUUID()
    private val harness = SupportHarness()

    private fun ApplicationTestBuilder.serve(caller: UserId = amina, scope: Scope = Scope.APP) {
        application {
            installJson()
            installStatusPages()
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(CallPrincipalKey, CallPrincipal(caller, DeviceId.new(), UUID.randomUUID(), scope))
            }
            routing { route("/v1") { ticketController(harness.service, harness.config) } }
        }
    }

    private suspend fun ApplicationTestBuilder.open(body: String): HttpResponse =
        client.post("/v1/support/tickets") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.data(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject["data"]!!.jsonObject

    private suspend fun HttpResponse.code(): String =
        Json
            .parseToJsonElement(bodyAsText())
            .jsonObject["code"]!!
            .jsonPrimitive.content

    private val valid =
        """{"subject":"Payment not received","description":"I sent 20 USDC to Baraka and it never arrived.",""" +
            """"category":"payments"}"""

    @Test
    fun `SU1 opens a ticket and answers it with its number, SU2 lists it and SU3 reads it`() =
        testApplication {
            serve()
            val opened = open(valid)
            assertEquals(HttpStatusCode.OK, opened.status)
            val ticket = opened.data()["ticket"]!!.jsonObject
            assertEquals("CS-000001", ticket["number"]!!.jsonPrimitive.content)
            assertEquals("OPEN", ticket["status"]!!.jsonPrimitive.content)
            assertEquals("STAFF", ticket["waitingOn"]!!.jsonPrimitive.content)
            assertEquals("APP", ticket["source"]!!.jsonPrimitive.content)
            assertEquals("PAYMENTS", ticket["category"]!!.jsonPrimitive.content)
            val id = ticket["ticketId"]!!.jsonPrimitive.content

            val list = client.get("/v1/support/tickets").data()
            assertEquals(
                id,
                list["tickets"]!!
                    .jsonArray
                    .single()
                    .jsonObject["ticketId"]!!
                    .jsonPrimitive.content,
            )

            val read = client.get("/v1/support/tickets/$id").data()
            assertEquals(
                "I sent 20 USDC to Baraka and it never arrived.",
                read["description"]!!.jsonPrimitive.content,
            )
            assertTrue(read["messages"]!!.jsonArray.isEmpty())
        }

    @Test
    fun `SU1 refuses an unknown category, a malformed id, a short subject and a context that isn't yours`() =
        testApplication {
            serve()
            val unknown = open(valid.replace("payments", "refunds"))
            assertEquals(HttpStatusCode.BadRequest, unknown.status)
            assertEquals("support.category_invalid", unknown.code())
            assertEquals(
                "request.malformed",
                open(valid.replace("}", ""","transferId":"nope"}""")).code(),
            )
            assertEquals("support.subject_invalid", open(valid.replace("Payment not received", "Hi")).code())
            val stranger = harness.wallets.payment(from = UserId.new(), to = UserId.new())
            assertEquals(
                "support.context_invalid",
                open(valid.replace("}", ""","transferId":"$stranger"}""")).code(),
            )
            assertEquals("request.malformed", open("""{"subject":"x"}""").code())
        }

    @Test
    fun `the sixth ticket in a day is 429 with Retry-After`() =
        testApplication {
            serve()
            repeat(5) { assertEquals(HttpStatusCode.OK, open(valid).status) }
            val refused = open(valid)
            assertEquals(HttpStatusCode.TooManyRequests, refused.status)
            assertEquals("support.rate_limited", refused.code())
            assertEquals("86400", refused.headers[HttpHeaders.RetryAfter])
        }

    @Test
    fun `SU3 shows staff as Circaso Support and never a note, SU4 reopens, and a closed ticket is 409`() =
        testApplication {
            serve()
            val id =
                open(valid)
                    .data()["ticket"]!!
                    .jsonObject["ticketId"]!!
                    .jsonPrimitive.content
            val ticketId = TicketId.parse(id)!!
            harness.desk.reply(ticketId, agent, "Can you share the transfer id?")
            harness.desk.note(ticketId, agent, "Internal: check the Base RPC")

            val read = client.get("/v1/support/tickets/$id")
            val text = read.bodyAsText()
            assertFalse("Internal" in text)
            assertFalse(agent.toString() in text)
            val message =
                read
                    .data()["messages"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("STAFF", message["author"]!!.jsonPrimitive.content)
            assertEquals("Circaso Support", message["authorName"]!!.jsonPrimitive.content)
            assertEquals(
                "PENDING_USER",
                read
                    .data()["ticket"]!!
                    .jsonObject["status"]!!
                    .jsonPrimitive.content,
            )

            val reply =
                client.post("/v1/support/tickets/$id/replies") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"body":"Here: 0193abcd"}""")
                }
            val replied = reply.data()
            assertEquals("OPEN", replied["ticket"]!!.jsonObject["status"]!!.jsonPrimitive.content)
            assertEquals(2, replied["messages"]!!.jsonArray.size)

            harness.desk.changeStatus(ticketId, TicketStatus.CLOSED)
            val closed =
                client.post("/v1/support/tickets/$id/replies") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"body":"Hello?"}""")
                }
            assertEquals(HttpStatusCode.Conflict, closed.status)
            assertEquals("support.ticket_closed", closed.code())
        }

    @Test
    fun `someone else's ticket, or an id that isn't one, is 404`() =
        testApplication {
            serve()
            val request =
                NewTicket(
                    UserId.new(),
                    "Can't sign in",
                    "The app says my session is not valid every time.",
                    TicketCategory.ACCOUNT,
                    source = TicketSource.APP,
                )
            val other = harness.service.open(request).data()

            val theirs = client.get("/v1/support/tickets/${other.ticketId}")
            assertEquals(HttpStatusCode.NotFound, theirs.status)
            assertEquals("support.ticket_not_found", theirs.code())
            assertEquals("support.ticket_not_found", client.get("/v1/support/tickets/not-an-id").code())
        }

    @Test
    fun `SU2 pages with an opaque cursor and refuses a bad one or a limit out of range`() =
        testApplication {
            serve()
            repeat(3) { open(valid) }
            val first = client.get("/v1/support/tickets?limit=2").data()
            assertEquals(2, first["tickets"]!!.jsonArray.size)
            val cursor = first["nextCursor"]!!.jsonPrimitive.content
            val second = client.get("/v1/support/tickets?limit=2&cursor=$cursor").data()
            assertEquals(1, second["tickets"]!!.jsonArray.size)
            assertEquals("null", second["nextCursor"].toString())

            assertEquals("request.malformed", client.get("/v1/support/tickets?cursor=AAAA").code())
            assertEquals("request.malformed", client.get("/v1/support/tickets?limit=0").code())
            assertEquals("request.malformed", client.get("/v1/support/tickets?limit=201").code())
        }

    @Test
    fun `an agent token can't reach the routes`() =
        testApplication {
            serve(scope = Scope.AGENT)
            assertEquals(HttpStatusCode.Forbidden, client.get("/v1/support/tickets").status)
        }
}
