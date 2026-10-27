package com.circaso.integrations.impl.circle

import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import io.ktor.client.request.HttpRequestData
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64

/**
 * Circle's API over MockEngine for the vendor tests. Answers are set per `"METHOD /path"` in [replies]; a user token
 * request always succeeds with a fresh JWT (`token-1`, `token-2`, ... in its `jti`) that expires 60 minutes after
 * [now]. Anything not set answers Circle's 404.
 */
internal class FakeCircle {
    var now: Instant = Instant.parse("2026-10-27T08:00:00Z")
    val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?) = this

            override fun instant(): Instant = now
        }

    val replies = mutableMapOf<String, Pair<Int, String>>()
    var tokensIssued = 0
    var down = false

    val clients =
        FakeHttpClients { request ->
            if (down) throw IOException("down")
            val route = "${request.method.value} ${request.url.encodedPath}"
            if (route == "POST /v1/w3s/users/token") {
                tokensIssued += 1
                json(tokenReply(tokensIssued))
            } else {
                val (status, body) = replies[route] ?: (404 to """{"code":-1,"message":"Resource not found"}""")
                json(body, status)
            }
        }

    val client = CircleClient(clients.named("circle") { baseUrl = "https://api.circle.com" }, API_KEY)
    val settings = CircleSettings(appId = "app-1", usdcTokenAddress = USDC_ADDRESS)
    val custody = CircleCustody(client, settings, clock)

    fun reply(
        route: String,
        body: String,
        status: Int = 200,
    ) {
        replies[route] = status to body
    }

    /** Requests to [route], token requests included when asked for. */
    fun requests(route: String): List<HttpRequestData> =
        clients.requests.filter { "${it.method.value} ${it.url.encodedPath}" == route }

    fun tokenOf(number: Int): String = jwt(number, now)

    private fun tokenReply(number: Int): String =
        """{"data":{"userToken":"${jwt(number, now)}","encryptionKey":"key-$number"}}"""

    companion object {
        const val API_KEY = "TEST_API_KEY:abc:def"
        const val USDC_ADDRESS = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
        const val USDC_TOKEN_ID = "bdf128b4-827b-5267-8f9e-243694989b5f"

        val TOKEN_LIFETIME: Duration = Duration.ofMinutes(60)

        /** A JWT shaped like Circle's: only `exp` and `jti` matter here, and the signature is never checked. */
        fun jwt(number: Int, issuedAt: Instant): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encodeToString("""{"alg":"RS256"}""".toByteArray())
            val exp = issuedAt.plus(TOKEN_LIFETIME).epochSecond
            val payload =
                encoder.encodeToString(
                    """{"iat":${issuedAt.epochSecond},"exp":$exp,"jti":"token-$number"}""".toByteArray(),
                )
            return "$header.$payload.signature"
        }

        /** Circle's token lookup for USDC on Base Sepolia, as the testnet answered it. */
        const val USDC_TOKEN_REPLY =
            """{"data":{"token":{"id":"$USDC_TOKEN_ID","blockchain":"BASE-SEPOLIA",""" +
                """"tokenAddress":"0x036cbd53842c5426634e7929541ec2318f3dcf7e","standard":"ERC20","name":"USDC",""" +
                """"symbol":"USDC","decimals":6,"isNative":false}}}"""
    }
}
