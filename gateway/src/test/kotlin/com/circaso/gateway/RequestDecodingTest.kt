package com.circaso.gateway

import com.circaso.web.installJson
import com.circaso.web.installRequestId
import com.circaso.web.installStatusPages
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ClosedByteChannelException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A compressed request body that can't be decoded is the caller's mistake (400), not the server's (500). */
class RequestDecodingTest {
    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                installRequestId()
                installJson()
                installStatusPages()
                install(Compression) {
                    gzip()
                    deflate()
                }
                installRequestDecodingErrors()
                routing {
                    post("/echo") { call.respondText(call.receiveText()) }
                    post("/broken") { error("a bug, not the caller") }
                    post("/wrapped-bug") {
                        call.receiveText()
                        throw IllegalStateException("bug", ClosedByteChannelException(IOException("connection reset")))
                    }
                    post("/channel-bug") {
                        call.receiveText()
                        throw ClosedByteChannelException(IllegalStateException("bug"))
                    }
                }
            }
            block()
        }

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    @Test
    fun `a body that is not the gzip it claims answers request malformed`() =
        app {
            val response =
                client.post("/echo") {
                    header(HttpHeaders.ContentEncoding, "gzip")
                    setBody("this is not gzip".toByteArray())
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue("request.malformed" in response.bodyAsText())
        }

    @Test
    fun `a body that is not the deflate it claims answers request malformed`() =
        app {
            val response =
                client.post("/echo") {
                    header(HttpHeaders.ContentEncoding, "deflate")
                    setBody(ByteArray(64) { 0x7f })
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue("request.malformed" in response.bodyAsText())
        }

    @Test
    fun `a real gzip body is decoded and other failures stay 500`() =
        app {
            val decoded =
                client.post("/echo") {
                    header(HttpHeaders.ContentEncoding, "gzip")
                    setBody(gzip("hello"))
                }
            assertEquals("hello", decoded.bodyAsText())

            val broken = client.post("/broken") { setBody("x") }
            assertEquals(HttpStatusCode.InternalServerError, broken.status)
            assertTrue("internal.error" in broken.bodyAsText())
        }

    @Test
    fun `on a decoded request, channel exceptions that are not the decoder's stay 500`() =
        app {
            for (path in listOf("/wrapped-bug", "/channel-bug")) {
                val response =
                    client.post(path) {
                        header(HttpHeaders.ContentEncoding, "gzip")
                        setBody(gzip("fine"))
                    }

                assertEquals(HttpStatusCode.InternalServerError, response.status, path)
                assertTrue("internal.error" in response.bodyAsText())
            }
        }
}
