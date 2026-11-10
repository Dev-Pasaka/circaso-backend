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

    // ---- groups in the history (2026-11-10) ----

    private fun chama(chatId: String, name: String) =
        """{"chatId":"$chatId","name":"$name","asset":"USDC","target":"500","contributed":"120","paidOut":"0",""" +
            """"held":"120","myContribution":"30","progressPercent":24,"memberCount":4,"isAdmin":false,""" +
            """"isTreasurer":false,"openRequests":[]}"""

    private fun groups() {
        answer(
            "/v1/agent/chama",
            """{"chamas":[${chama(TRIP_CHAT, "Dubai trip")},${chama(LAND_CHAT, "Ruiru land")}]}""",
        )
        answer(
            "/v1/agent/wallet/wallets",
            """{"wallets":[{"walletId":"$ME","type":"PERSON","status":"ACTIVE","canSign":true,"blockchains":[]},""" +
                """{"walletId":"$TRIP_WALLET","type":"GROUP","status":"ACTIVE","canSign":false,""" +
                """"blockchains":["BASE"],"chatId":"$TRIP_CHAT"}]}""",
        )
        answer(
            "/v1/agent/wallet/transactions",
            """{"transactions":[{"id":"t1","direction":"OUT","state":"CONFIRMED","asset":"USDC","amount":"30",""" +
                """"blockchain":"BASE","counterparty":{"userId":"$TRIP_WALLET","groupId":"$TRIP_CHAT"},""" +
                """"at":"2026-10-01T00:00:00Z"}]}""",
        )
    }

    private fun history() = requests.single { it.url.encodedPath == "/v1/agent/wallet/transactions" }.url.parameters

    private fun summary() =
        requests.single { it.url.encodedPath == "/v1/agent/wallet/transactions/summary" }.url.parameters

    @Test
    fun `a group's name finds the user's own payments into its wallet, and the group is named in the answer`() {
        groups()

        val text = (call("listTransactions", """{"groupName":"dubai trip"}""") as Resource.Success).data

        assertEquals(TRIP_WALLET, history()["counterparty"], "the user's payments with the group's wallet")
        assertEquals(null, history()["walletId"], "the user's own history, not the group's")
        assertTrue(""""groupId":"$TRIP_CHAT","groupName":"Dubai trip"""" in text, text)
        assertEquals(1, requests.count { it.url.encodedPath == "/v1/agent/chama" }, "the chamas are read once")
        assertTrue(requests.all { it.method.value == "GET" })
    }

    @Test
    fun `a group put in counterpartyName, matching no contact, is found as the group`() {
        groups()
        answer("/v1/agent/users/contacts/search", """{"contacts":[]}""")
        answer(
            "/v1/agent/wallet/transactions/summary",
            """{"count":1,"totals":[],"topCounterparties":[{"counterparty":{"userId":"$TRIP_WALLET",""" +
                """"groupId":"$TRIP_CHAT"},"asset":"USDC","totalIn":"0","totalOut":"30","count":1}],"byMonth":[]}""",
        )

        val text = (call("summarizeTransactions", """{"counterpartyName":"Dubai trip"}""") as Resource.Success).data

        assertEquals(TRIP_WALLET, summary()["counterparty"])
        assertTrue(""""groupName":"Dubai trip"""" in text, text)
    }

    @Test
    fun `the group wallet's own history is read by its wallet id, narrowed to one member when named`() {
        groups()
        answer("/v1/agent/users/contacts/search", """{"contacts":[{"userId":"u-mum","displayName":"Mum"}]}""")

        call("listTransactions", """{"groupId":"$TRIP_CHAT","groupWallet":true,"direction":"OUT"}""")
        assertEquals(TRIP_WALLET, history()["walletId"])
        assertEquals(null, history()["counterparty"])
        assertEquals("OUT", history()["direction"])

        requests.clear()
        call("listTransactions", """{"groupName":"Dubai trip","counterpartyName":"Mum"}""")
        assertEquals(TRIP_WALLET, history()["walletId"], "a member named inside a group reads the group's wallet")
        assertEquals("u-mum", history()["counterparty"])

        requests.clear()
        call("summarizeTransactions", """{"groupId":"$TRIP_WALLET","groupWallet":true}""")
        assertEquals(TRIP_WALLET, summary()["walletId"], "the wallet's own id names the group too")
    }

    @Test
    fun `a group that can't be found is answered to the model, and no history is read for a guess`() {
        groups()

        val unknown = (call("listTransactions", """{"groupName":"Mombasa"}""") as Resource.Success).data
        val noWallet = (call("listTransactions", """{"groupId":"$LAND_CHAT"}""") as Resource.Success).data
        val made = (call("listTransactions", """{"groupId":"the trip"}""") as Resource.Success).data
        val nameless = (call("summarizeTransactions", """{"groupWallet":true}""") as Resource.Success).data

        assertTrue("No chama is called" in unknown && "end-to-end encrypted" in unknown, unknown)
        assertTrue("no wallet" in noWallet, noWallet)
        assertTrue("not a group's chatId" in made, made)
        assertTrue("Say which group" in nameless, nameless)
        assertTrue(requests.none { it.url.encodedPath.startsWith("/v1/agent/wallet/transactions") })
    }

    @Test
    fun `two chamas matching a name go back to the model to choose`() {
        answer(
            "/v1/agent/chama",
            """{"chamas":[${chama(TRIP_CHAT, "Dubai trip 2025")},${chama(LAND_CHAT, "Dubai trip 2026")}]}""",
        )

        val text = (call("listTransactions", """{"groupName":"dubai"}""") as Resource.Success).data

        assertTrue("\"candidates\"" in text && "Dubai trip 2026" in text, text)
        assertTrue(requests.none { it.url.encodedPath.startsWith("/v1/agent/wallet") })
    }

    @Test
    fun `any answer naming a group gets the group's name, and a group wallet's payment opens with its wallet`() {
        groups()

        val listed = (call("listTransactions") as Resource.Success).data
        assertTrue(""""groupName":"Dubai trip"""" in listed, listed)

        answer(
            "/v1/agent/wallet/transactions/$PAYMENT",
            """{"transaction":{"id":"t1","direction":"IN","state":"CONFIRMED","asset":"USDC","amount":"30",""" +
                """"blockchain":"BASE","counterparty":{"userId":"$TRIP_WALLET","groupId":"$TRIP_CHAT"},""" +
                """"at":"2026-10-01T00:00:00Z"}}""",
        )
        val one = call("getTransaction", """{"id":"$PAYMENT","walletId":"$TRIP_WALLET"}""") as Resource.Success
        assertTrue(""""groupName":"Dubai trip"""" in one.data, one.data)
        val opened = requests.last { it.url.encodedPath.endsWith(PAYMENT) }
        assertEquals(TRIP_WALLET, opened.url.parameters["walletId"])

        assertEquals(
            "Checking your payments with Dubai trip…",
            tools.label("listTransactions", """{"groupName":"Dubai trip"}"""),
        )
        assertEquals(
            "Adding up Dubai trip's wallet payments…",
            tools.label("summarizeTransactions", """{"groupName":"Dubai trip","groupWallet":true}"""),
        )
    }
}

private const val ME = "0192e3a4-0000-7000-8000-0000000000aa"
private const val TRIP_CHAT = "0192e3a4-0000-7000-8000-0000000000c1"
private const val LAND_CHAT = "0192e3a4-0000-7000-8000-0000000000c2"
private const val TRIP_WALLET = "0192e3a4-0000-7000-8000-0000000000f1"
private const val PAYMENT = "0192e3a4-0000-7000-8000-000000000001"
