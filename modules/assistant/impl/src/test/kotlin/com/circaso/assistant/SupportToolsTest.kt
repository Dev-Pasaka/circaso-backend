package com.circaso.assistant

import com.circaso.assistant.data.tools.SupportTools
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.api.NewTicket
import com.circaso.support.api.OpenedTicket
import com.circaso.support.api.SupportTickets
import com.circaso.support.api.TicketCategory
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What support:api answers in these tests: a numbered ticket, or the error in [failWith]. */
class RecordingTickets : SupportTickets {
    val opened = mutableListOf<NewTicket>()
    var failWith: String? = null

    override suspend fun open(ticket: NewTicket): Resource<OpenedTicket> {
        failWith?.let { code -> return Resource.error(Refusal(code)) }
        opened += ticket
        return Resource.success(OpenedTicket(TicketId.new(), "CS-" + opened.size.toString().padStart(6, '0')))
    }

    private class Refusal(override val code: String) : DomainError {
        override val message = "refused"
        override val kind = ErrorKind.INVALID
    }
}

/** `openSupportTicket` (decision 2026-11-08): source ASHA, the number back, once per answer, refusals in words. */
class SupportToolsTest {
    private val me = UserId.new()
    private val tickets = RecordingTickets()
    private val tools = SupportTools(tickets)
    private val context = ToolContext(me, agentToken = null, currency = "KES")
    private val transferId = UUID.randomUUID()

    private val arguments =
        """{"subject":"Payment to Baraka not received","summary":"Sent 20 USDC on Base yesterday; """ +
            """the wallet says CONFIRMED but Baraka sees nothing. Checked his address.","category":"payments",""" +
            """"transferId":"$transferId"}"""

    private fun Resource<String>.json() = Json.parseToJsonElement((this as Resource.Success).data).jsonObject

    @Test
    fun `it opens a ticket for the user with source ASHA and answers its number`() =
        runTest {
            val answer = tools.call("openSupportTicket", arguments, context).json()

            assertEquals("CS-000001", answer["ticketNumber"]!!.jsonPrimitive.content)
            assertEquals("OPEN", answer["status"]!!.jsonPrimitive.content)
            val ticket = tickets.opened.single()
            assertEquals(me, ticket.userId)
            assertEquals(TicketSource.ASHA, ticket.source)
            assertEquals(TicketCategory.PAYMENTS, ticket.category)
            assertEquals(transferId, ticket.context.transferId)
            assertEquals("Payment to Baraka not received", ticket.subject)
        }

    @Test
    fun `a second call in the same answer is refused and opens nothing`() =
        runTest {
            val again = context.copy(calledBefore = listOf("getWallet", "openSupportTicket"))
            val answer = tools.call("openSupportTicket", arguments, again).json()

            assertTrue("already opened" in answer["message"]!!.jsonPrimitive.content)
            assertTrue(tickets.opened.isEmpty())
        }

    @Test
    fun `refusals reach the model as words to pass on, never as errors`() =
        runTest {
            mapOf(
                "support.rate_limited" to "most tickets allowed today",
                "support.context_invalid" to "isn't one of the user's",
                "support.subject_invalid" to "a little more detail",
                "support.description_invalid" to "a little more detail",
                "internal.error" to "couldn't be opened right now",
            ).forEach { (code, words) ->
                tickets.failWith = code
                val answer = tools.call("openSupportTicket", arguments, context).json()
                assertTrue(words in answer["message"]!!.jsonPrimitive.content, code)
            }
        }

    @Test
    fun `a long subject is cut at a word, an unknown category is OTHER, a bad id is refused`() =
        runTest {
            val long = "word ".repeat(40)
            tools.call("openSupportTicket", """{"subject":"$long","summary":"$long","category":"refunds"}""", context)
            val ticket = tickets.opened.single()
            assertTrue(ticket.subject.length <= 120 && ticket.subject.endsWith("word"))
            assertEquals(TicketCategory.OTHER, ticket.category)

            val bad = tools.call("openSupportTicket", arguments.replace("$transferId", "nope"), context).json()
            assertTrue("transferId" in bad["message"]!!.jsonPrimitive.content)
            assertEquals(1, tickets.opened.size)
        }

    @Test
    fun `it is offered with or without an agent token, and shows its own label`() {
        assertEquals(listOf("openSupportTicket"), tools.specs(hasAgentToken = false).map { it.name })
        assertEquals(listOf("openSupportTicket"), tools.specs(hasAgentToken = true).map { it.name })
        assertEquals("Opening a support ticket…", tools.label("openSupportTicket", arguments))
        assertTrue(tools.handles("openSupportTicket"))
    }
}
