package com.circaso.integrations.impl.googlemaps

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.MapPalette
import com.circaso.integrations.api.StaticMapRequest
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.shared.Resource
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Google's Maps Static API over MockEngine. */
class GoogleStaticMapsTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private var status = HttpStatusCode.OK
    private var type = "image/png"
    private var down = false
    private val clients =
        FakeHttpClients { _ ->
            if (down) throw IOException("down")
            respond(png, status, headersOf(HttpHeaders.ContentType, type))
        }
    private val request =
        StaticMapRequest(
            -1.292066,
            36.821946,
            16,
            240,
            120,
            MapPalette("F2F2F7", "FFFFFF", "DCDCE0", "DCDCE0", "636366"),
        )

    private fun maps(secret: String? = "vNIXE0xscrmjlyV-12Nj_BvUPaw=") =
        GoogleStaticMaps(clients.named("google-maps"), key = "k", signingSecret = secret)

    @Test
    fun signsLikeGooglesOwnExample() {
        // developers.google.com/maps/documentation/maps-static/digital-signature: the sample URL and secret.
        val signature =
            UrlSigner(
                "vNIXE0xscrmjlyV-12Nj_BvUPaw=",
            ).sign("/maps/api/geocode/json?address=New+York&client=clientID")
        assertEquals("chaRF2hTJKOScPr-RQCEhZbSzIE=", signature)
    }

    @Test
    fun asksForTheStyledPictureAndSignsTheWholeQuery() =
        runBlocking {
            val result = maps().render(request)

            assertContentEquals(
                png,
                assertIs<Resource.Success<*>>(result).data.let {
                    (it as com.circaso.integrations.api.MapPicture).bytes
                },
            )
            val url = clients.last.url
            assertEquals("/maps/api/staticmap", url.encodedPath)
            val query = url.encodedQuery
            assertTrue(query.startsWith("center=-1.29207,36.82195&zoom=16&size=240x120&scale=2&format=png"), query)
            assertTrue("style=feature:poi%7Cvisibility:off" in query, query)
            assertTrue("style=element:geometry%7Ccolor:0xF2F2F7" in query, query)
            val signed = url.encodedPath + "?" + query.substringBefore("&signature=")
            assertEquals(UrlSigner("vNIXE0xscrmjlyV-12Nj_BvUPaw=").sign(signed), query.substringAfter("&signature="))
        }

    @Test
    fun withoutASecretTheKeyAloneIsSent() {
        val path = maps(secret = null).signedPath(request)
        assertTrue(path.endsWith("&key=k"), path)
    }

    @Test
    fun aRefusalOrSomethingThatIsNotAPictureIsUnavailable() =
        runBlocking {
            status = HttpStatusCode.Forbidden
            assertIs<IntegrationError.Unavailable>(assertIs<Resource.Error>(maps().render(request)).error)
            status = HttpStatusCode.OK
            type = "text/plain"
            assertIs<IntegrationError.Unavailable>(assertIs<Resource.Error>(maps().render(request)).error)
        }

    @Test
    fun anUnreachableVendorIsUnavailable() =
        runBlocking {
            down = true
            assertIs<IntegrationError.Unavailable>(assertIs<Resource.Error>(maps().render(request)).error)
        }
}
