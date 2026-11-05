package com.circaso.assistant

import com.circaso.assistant.data.tools.HttpAgentTools
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Asha's tools against the other modules' agent routes, typed both ways (decisions 2026-10-27 and 2026-10-31): each
 * tool's route, the agent token and currency on every call, the caps, and names turned into people.
 */
class HttpAgentToolsTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val answers = mutableMapOf<String, String>()
    private var status = HttpStatusCode.OK
    private val user = UserId.new()
    private val context = ToolContext(user, "agent-tok", "KES")
    private val tools =
        HttpAgentTools(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    val body = answers[request.url.encodedPath] ?: envelope("""{"contacts":[]}""")
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
            "http://localhost:8081",
        )

    private fun envelope(data: String) =
        """{"httpStatusCode":200,"status":true,"message":"Success","requestId":"r1","data":$data}"""

    private fun answer(path: String, data: String) {
        answers[path] = envelope(data)
    }

    private fun call(
        name: String,
        arguments: String = "{}",
        with: ToolContext = context,
    ) = runBlocking { tools.call(name, arguments, with) }

    @Test
    fun `a search reads its query from the model's arguments and gives the model only the route's data`() {
        answer(
            "/v1/agent/users/contacts/search",
            """{"contacts":[{"userId":"u1","displayName":"Mum Wanjiku","handle":"mum","extra":1}]}""",
        )

        val result = call("searchContacts", """{"query":"Mum"}""")

        val request = requests.single()
        assertEquals("Mum", request.url.parameters["q"])
        assertEquals("5", request.url.parameters["limit"])
        assertEquals("Bearer agent-tok", request.headers[HttpHeaders.Authorization])
        assertEquals(
            """{"contacts":[{"userId":"u1","displayName":"Mum Wanjiku","handle":"mum"}],"nextCursor":null}""",
            (result as Resource.Success).data,
            "the envelope, and anything the route adds, never reach the model",
        )
    }

    @Test
    fun `a list keeps its limit within bounds, and arguments that don't read fall back to the defaults`() {
        call("listContacts", """{"limit":500}""")
        call("listContacts", """not json""")
        call("listContacts", """{"limit":"many"}""")
        call("listChats", """{"limit":1000}""")

        assertEquals(listOf("50", "20", "20", "100"), requests.map { it.url.parameters["limit"] })
    }

    @Test
    fun `each tool reads its own route with the agent token, and money tools ask in the user's currency`() {
        val expected =
            mapOf(
                "getProfile" to "/v1/agent/users/me",
                "listBlocked" to "/v1/agent/users/blocks",
                "getSecurityOverview" to "/v1/agent/identity/security",
                "listChats" to "/v1/agent/messaging/chats",
                "getWallet" to "/v1/agent/wallet",
                "listTransactions" to "/v1/agent/wallet/transactions",
                "summarizeTransactions" to "/v1/agent/wallet/transactions/summary",
                "getTransaction" to "/v1/agent/wallet/transactions/0192e3a4-0000-7000-8000-000000000001",
                "listChamas" to "/v1/agent/chama",
                "getChama" to "/v1/agent/chama/0192e3a4-0000-7000-8000-000000000002",
                "listWallets" to "/v1/agent/wallet/wallets",
                "getAssetValues" to "/v1/agent/wallet/values",
                "getFeeQuote" to "/v1/agent/wallet/fee",
                "getMyMoments" to "/v1/agent/status/moments",
            )
        val arguments =
            mapOf(
                "getTransaction" to """{"id":"0192e3a4-0000-7000-8000-000000000001"}""",
                "getChama" to """{"chatId":"0192e3a4-0000-7000-8000-000000000002"}""",
                "getFeeQuote" to """{"amount":"20","blockchain":"BASE","toAddress":"0xabc"}""",
            )

        expected.keys.forEach { call(it, arguments[it] ?: "{}") }

        assertEquals(expected.values.toList(), requests.map { it.url.encodedPath })
        assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer agent-tok" })
        val money =
            setOf(
                "getWallet",
                "listTransactions",
                "summarizeTransactions",
                "getTransaction",
                "getAssetValues",
                "getFeeQuote",
            )
        requests.zip(expected.keys).forEach { (request, tool) ->
            assertEquals(if (tool in money) "KES" else null, request.url.parameters["currency"], tool)
        }
    }

    @Test
    fun `without an agent token nothing is called and no token-bound tool is offered`() {
        val result = call("getWallet", with = ToolContext(user, null, "KES"))

        assertIs<Resource.Error>(result)
        assertTrue(requests.isEmpty())
        assertTrue(tools.specs(hasAgentToken = false).isEmpty())
        assertEquals(16, tools.specs(hasAgentToken = true).size)
    }

    @Test
    fun `the history filters reach the route, and a person's name becomes their id first`() {
        answer(
            "/v1/agent/users/contacts/search",
            """{"contacts":[{"userId":"u-mum","displayName":"Mum"},{"userId":"u-mumbi","displayName":"Mumbi"}]}""",
        )
        answer("/v1/agent/wallet/transactions", """{"transactions":[]}""")

        call(
            "listTransactions",
            """{"month":"2026-10","direction":"OUT","counterpartyName":"mum","asset":"USDC","limit":99}""",
        )

        val (search, list) = requests
        assertEquals("/v1/agent/users/contacts/search", search.url.encodedPath)
        val parameters = list.url.parameters
        assertEquals("u-mum", parameters["counterparty"], "the exact name wins over a partial match")
        assertEquals("2026-10", parameters["month"])
        assertEquals("OUT", parameters["direction"])
        assertEquals("USDC", parameters["asset"])
        assertEquals("50", parameters["limit"])
    }

    @Test
    fun `a name that matches several contacts, or none, goes back to the model instead of a lookup`() {
        answer(
            "/v1/agent/users/contacts/search",
            """{"contacts":[{"userId":"a","displayName":"John Kamau"},{"userId":"b","displayName":"John Otieno"}]}""",
        )

        val several = call("summarizeTransactions", """{"counterpartyName":"John"}""")

        assertTrue((several as Resource.Success).data.contains("\"candidates\""))
        assertEquals(1, requests.size, "no summary is read for a guess")

        answer("/v1/agent/users/contacts/search", """{"contacts":[]}""")
        val none = call("summarizeTransactions", """{"counterpartyName":"Zawadi"}""")
        assertTrue((none as Resource.Success).data.contains("No contact matches"))
    }

    @Test
    fun `an answer longer than the cap is cut, and the model is told`() {
        val row =
            """{"id":"x","direction":"OUT","state":"CONFIRMED","asset":"USDC","amount":"1","blockchain":"BASE",""" +
                """"counterparty":{"displayName":"${"N".repeat(200)}"},"at":"2026-10-01T00:00:00Z"}"""
        answer("/v1/agent/wallet/transactions", """{"transactions":[${List(50) { row }.joinToString(",")}]}""")

        val text = (call("listTransactions") as Resource.Success).data

        assertTrue(text.length <= 12_000, "was ${text.length}")
        assertTrue(text.startsWith("""{"note":"""), text.take(40))
    }

    @Test
    fun `an id that isn't one is answered without a call`() {
        val result = call("getTransaction", """{"id":"../../admin"}""")

        assertTrue((result as Resource.Success).data.contains("not a payment id"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `an answer that isn't the route's shape is an error, not an empty success`() {
        answer("/v1/agent/users/contacts/search", """{"contacts":"not a list"}""")

        assertIs<Resource.Error>(call("searchContacts", """{"query":"x"}"""))
    }

    @Test
    fun `a refused lookup is an error carrying the route's code`() {
        status = HttpStatusCode.NotFound
        answers["/v1/agent/wallet/transactions/0192e3a4-0000-7000-8000-000000000001"] =
            """{"httpStatusCode":404,"status":false,"code":"wallet.transfer_not_found","message":"No"}"""

        val result = call("getTransaction", """{"id":"0192e3a4-0000-7000-8000-000000000001"}""")

        assertTrue((result as Resource.Error).error.message.contains("wallet.transfer_not_found"))
    }

    @Test
    fun `the status line is the server's own, with the user's words short and quoted`() {
        assertEquals("Checking your balance…", tools.label("getWallet", "{}"))
        assertEquals(
            "Checking your payments with Mum for 2026-10…",
            tools.label("listTransactions", """{"counterpartyName":"Mum","month":"2026-10"}"""),
        )
        assertEquals("Looking for “Mum” in your contacts…", tools.label("searchContacts", """{"query":"Mum"}"""))
    }

    @Test
    fun `a value is asked of the wallet's own rate, in the user's currency unless the model names one`() {
        answer(
            "/v1/agent/wallet/values",
            """{"currency":"KES","source":"circaso","values":[{"asset":"USDC","priced":true,"usdPrice":"1",""" +
                """"unitValue":"129.50","value":"2590.00"}],"amount":"20",""" +
                """"rate":{"currency":"KES","usdRate":"129.5","asOf":"2026-11-05T06:00:00Z"}}""",
        )

        val text = (call("getAssetValues", """{"assets":["usdc"," "],"amount":"20"}""") as Resource.Success).data
        call("getAssetValues", """{"currency":"UGX"}""")

        val (first, second) = requests
        assertEquals("usdc", first.url.parameters["assets"])
        assertEquals("20", first.url.parameters["amount"])
        assertEquals("KES", first.url.parameters["currency"])
        assertEquals(null, second.url.parameters["assets"], "no names asks for every supported asset")
        assertEquals("UGX", second.url.parameters["currency"])
        assertTrue(""""source":"circaso"""" in text, text)
        assertTrue(""""value":"2590.00"""" in text, text)
    }

    @Test
    fun `a fee quote turns a name into a person and passes the payment, reading only`() {
        answer(
            "/v1/agent/users/contacts/search",
            """{"contacts":[{"userId":"u-mum","displayName":"Mum"}]}""",
        )
        answer(
            "/v1/agent/wallet/fee",
            """{"blockchain":"BASE","asset":"USDC","amount":"20","fee":"0.1","rateBps":50,"minimum":false,""" +
                """"total":"20.1","balance":"100","maxAmount":"99.5",""" +
                """"limit":{"used":"0","windowHours":24,"dailyMax":"500","left":"500"}}""",
        )

        val text =
            (
                call(
                    "getFeeQuote",
                    """{"amount":"20","blockchain":"BASE","counterpartyName":"Mum","walletId":"w-1"}""",
                ) as Resource.Success
            ).data

        val fee = requests.last()
        assertEquals("/v1/agent/wallet/fee", fee.url.encodedPath)
        assertEquals("u-mum", fee.url.parameters["toUserId"])
        assertEquals("20", fee.url.parameters["amount"])
        assertEquals("BASE", fee.url.parameters["blockchain"])
        assertEquals("w-1", fee.url.parameters["walletId"])
        assertEquals("KES", fee.url.parameters["currency"])
        assertTrue(requests.all { it.method.value == "GET" }, "every agent call is a read")
        assertTrue(""""fee":"0.1"""" in text, text)
    }

    @Test
    fun `a group wallet is opened by its id from the list, and a made-up id is answered without a call`() {
        call("getWallet", """{"walletId":"0192e3a4-0000-7000-8000-000000000009"}""")
        val made = (call("getWallet", """{"walletId":"the chama one"}""") as Resource.Success).data

        assertEquals("0192e3a4-0000-7000-8000-000000000009", requests.single().url.parameters["walletId"])
        assertTrue("listWallets" in made, made)
    }

    @Test
    fun `own moments are read as counts only`() {
        answer(
            "/v1/agent/status/moments",
            """{"live":1,"totalViews":4,"totalReactions":1,"moments":[{"momentId":"m1",""" +
                """"postedAt":"2026-11-05T07:00:00Z","expiresAt":"2026-11-06T07:00:00Z","hasMedia":true,""" +
                """"viewCount":4,"reactionCount":1}]}""",
        )

        val text = (call("getMyMoments") as Resource.Success).data

        assertTrue(""""totalViews":4""" in text, text)
        assertEquals("/v1/agent/status/moments", requests.single().url.encodedPath)
    }
}
