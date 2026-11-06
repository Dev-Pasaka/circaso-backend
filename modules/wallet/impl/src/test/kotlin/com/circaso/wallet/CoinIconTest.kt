package com.circaso.wallet

import com.circaso.shared.Resource
import com.circaso.wallet.data.repositories.BundledCoinIcons
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.CoinIcon
import com.circaso.wallet.domain.repositories.CoinIconRepository
import com.circaso.wallet.domain.usecases.GetCoinIcon
import com.circaso.wallet.presentation.controllers.coinIconController
import com.circaso.wallet.presentation.services.CoinIconService
import com.circaso.web.installJson
import com.circaso.web.installStatusPages
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** IC1, coin icons by symbol (decision 2026-11-06): the vendored set, the allow-list, caching and the 404. */
class CoinIconTest {
    private class Recording : CoinIconRepository {
        val asked = mutableListOf<String>()

        override suspend fun find(symbol: String): CoinIcon? {
            asked += symbol
            return null
        }
    }

    private fun ApplicationTestBuilder.serve() {
        val icons = CoinIconService(GetCoinIcon(BundledCoinIcons()))
        application {
            installJson()
            installStatusPages()
            routing { route("/v1") { coinIconController(icons) } }
        }
    }

    @Test
    fun `the vendored set has the coins the app asks for, as clean one-colour SVG`() =
        runTest {
            val icons = BundledCoinIcons()
            listOf("USDC", "USDT", "EURC", "ETH", "POL", "MATIC", "SOL", "BTC", "WBTC", "AVAX", "DOGE").forEach {
                val svg = assertNotNull(icons.find(it), it).svg.decodeToString()
                assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\""), it)
                listOf("<script", "<style", "<image", "<filter", "<use", "href", "stroke").forEach { bad ->
                    assertTrue(bad !in svg, "$it has $bad")
                }
                assertNull(Regex("""\son\w+=""").find(svg), "$it has an event handler")
            }
            assertNull(icons.find("NOPE123"))
        }

    @Test
    fun `a symbol outside the allow-list is refused before any lookup`() =
        runTest {
            val icons = Recording()
            val get = GetCoinIcon(icons)
            listOf("", "../ETC", "US DC", "USD+", "A".repeat(16), "usdc/../eth").forEach {
                assertEquals(WalletError.IconSymbolInvalid, assertIs<Resource.Error>(get(it)).error, it)
            }
            assertTrue(icons.asked.isEmpty())
            assertEquals(WalletError.IconNotFound, assertIs<Resource.Error>(get("usdc.e")).error)
            assertEquals(listOf("USDC.E"), icons.asked)
        }

    @Test
    fun `IC1 answers the SVG, public and cacheable for a week, any case`() =
        testApplication {
            serve()

            val response = client.get("/v1/assets/icons/usdc")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType("image", "svg+xml"), response.contentType()?.withoutParameters())
            assertEquals("public, max-age=604800, immutable", response.headers[HttpHeaders.CacheControl])
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertNotNull(response.headers[HttpHeaders.ETag])
            assertTrue(response.bodyAsText().startsWith("<svg"))
        }

    @Test
    fun `IC1 answers 304 when the phone already has it`() =
        testApplication {
            serve()
            val etag = client.get("/v1/assets/icons/ETH").headers[HttpHeaders.ETag]!!

            val again = client.get("/v1/assets/icons/ETH") { header(HttpHeaders.IfNoneMatch, etag) }

            assertEquals(HttpStatusCode.NotModified, again.status)
            assertEquals("", again.bodyAsText())
        }

    @Test
    fun `IC1 answers 404 for a coin the set doesn't have and 400 for a malformed symbol`() =
        testApplication {
            serve()

            val missing = client.get("/v1/assets/icons/NOPE123")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals(
                "wallet.icon_not_found",
                Json
                    .parseToJsonElement(missing.bodyAsText())
                    .jsonObject["code"]!!
                    .jsonPrimitive.content,
            )

            val bad = client.get("/v1/assets/icons/US%20DC")
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals(
                "wallet.icon_symbol_invalid",
                Json
                    .parseToJsonElement(bad.bodyAsText())
                    .jsonObject["code"]!!
                    .jsonPrimitive.content,
            )
        }
}
