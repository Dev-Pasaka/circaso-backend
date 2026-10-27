package com.circaso.web

import com.circaso.shared.DomainError
import com.circaso.shared.DomainException
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind
import com.circaso.shared.FieldError
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.MDC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebEnvelopeTest {
    private class Taken : DomainError {
        override val code = "identity.phone_taken"
        override val message = "This number already has a Circaso account."
        override val kind = ErrorKind.CONFLICT
    }

    private class Locked : DomainError {
        override val code = "identity.locked"
        override val message = "Too many attempts. Try again later."
        override val kind = ErrorKind.RATE_LIMITED
        override val details = ErrorDetails(retryAfterSeconds = 600)
    }

    private class ProfileMissing : DomainError {
        override val code = "users.profile_not_found"
        override val message = "No such profile."
        override val kind = ErrorKind.NOT_FOUND
    }

    @Serializable
    private data class Hello(val name: String)

    @Serializable
    private data class Name(val displayName: String)

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                installRequestId()
                installJson()
                installStatusPages()
                routing {
                    get("/ok") { call.respondDefault(Resource.success(Hello("Amina")).toDefaultResponse { it }) }
                    get("/created") {
                        call.respondDefault(
                            Resource.success(Hello("Amina")).toDefaultResponse(HttpStatusCode.Created) { it },
                        )
                    }
                    get(
                        "/taken",
                    ) { call.respondDefault(Resource.error(Taken()).toDefaultResponse<Hello, Hello> { it }) }
                    get(
                        "/locked",
                    ) { call.respondDefault(Resource.error(Locked()).toDefaultResponse<Hello, Hello> { it }) }
                    get(
                        "/missing",
                    ) { call.respondDefault(Resource.error(ProfileMissing()).toDefaultResponse<Hello, Hello> { it }) }
                    get("/bad-field") {
                        val fields = listOf(FieldError("displayName", "required"), FieldError("about", "too long"))
                        call.respondDefault(SharedError.Malformed(fields).toDefaultResponse<Hello>())
                    }
                    get("/throws-domain") { throw DomainException(Taken()) }
                    get("/boom") { error("secret vendor detail: api-key-123") }
                    get("/busy") { throw DomainException(SharedError.ServiceUnavailable) }
                    post(
                        "/body",
                    ) {
                        call.respondDefault(
                            Resource.success(call.receive<Name>()).toDefaultResponse { it.displayName },
                        )
                    }
                    get("/mdc") {
                        val inHandler = MDC.get(REQUEST_ID_MDC_KEY)
                        val afterSwitch = withContext(Dispatchers.Default) { MDC.get(REQUEST_ID_MDC_KEY) }
                        call.respondDefault(
                            Resource.success(listOf(inHandler, afterSwitch, call.callId)).toDefaultResponse { it },
                        )
                    }
                }
            }
            block()
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `a success is the envelope with status true and the data`() =
        app {
            val response = client.get("/ok")
            val body = response.json()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(200, body["httpStatusCode"]!!.jsonPrimitive.int)
            assertEquals("true", body["status"]!!.jsonPrimitive.content)
            assertEquals("Amina", body["data"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, body["code"])
        }

    @Test
    fun `a success can carry a created status`() =
        app {
            val response = client.get("/created")

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(201, response.json()["httpStatusCode"]!!.jsonPrimitive.int)
        }

    @Test
    fun `an error takes its status from its kind and keeps its code and message`() =
        app {
            val response = client.get("/taken")
            val body = response.json()

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(409, body["httpStatusCode"]!!.jsonPrimitive.int)
            assertEquals("false", body["status"]!!.jsonPrimitive.content)
            assertEquals("identity.phone_taken", body["code"]!!.jsonPrimitive.content)
            assertEquals("This number already has a Circaso account.", body["message"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, body["data"])
        }

    @Test
    fun `a rate limit sets Retry-After and the details`() =
        app {
            val response = client.get("/locked")
            val body = response.json()

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("600", response.headers[HttpHeaders.RetryAfter])
            assertEquals(600, body["details"]!!.jsonObject["retryAfterSeconds"]!!.jsonPrimitive.int)
        }

    @Test
    fun `field errors are a list of field and problem`() =
        app {
            val response = client.get("/bad-field")
            val details = Json.decodeFromString<DefaultResponse<Hello>>(response.bodyAsText()).details

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                listOf(FieldErrorDto("displayName", "required"), FieldErrorDto("about", "too long")),
                details?.fields,
            )
            assertTrue(
                """"fields":[{"field":"displayName","problem":"required"}""" in response.bodyAsText(),
                response.bodyAsText(),
            )
        }

    @Test
    fun `a not found error from a route keeps its own code`() =
        app {
            val response = client.get("/missing")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("users.profile_not_found", response.json()["code"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a thrown DomainException becomes the same envelope as a returned error`() =
        app {
            val response = client.get("/throws-domain")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("identity.phone_taken", response.json()["code"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a pool with no free connection answers 503 service unavailable`() =
        app {
            val response = client.get("/busy")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("service.unavailable", response.json()["code"]!!.jsonPrimitive.content)
        }

    @Test
    fun `an unexpected exception is a 500 that leaks nothing`() =
        app {
            val response = client.get("/boom")
            val text = response.bodyAsText()
            val body = response.json()

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("internal.error", body["code"]!!.jsonPrimitive.content)
            assertEquals("Something went wrong", body["message"]!!.jsonPrimitive.content)
            assertFalse(text.contains("api-key-123"))
            assertFalse(text.contains("IllegalStateException"))
        }

    @Test
    fun `malformed JSON and a missing field are a 400 request malformed`() =
        app {
            val garbage =
                client.post("/body") {
                    contentType(ContentType.Application.Json)
                    setBody("{not json")
                }
            val missing =
                client.post("/body") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            for (response in listOf(garbage, missing)) {
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertEquals("request.malformed", response.json()["code"]!!.jsonPrimitive.content)
            }
        }

    @Test
    fun `an unknown route is a 404 request not_found in the envelope`() =
        app {
            val response = client.get("/no/such/route")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("request.not_found", response.json()["code"]!!.jsonPrimitive.content)
            assertEquals(404, response.json()["httpStatusCode"]!!.jsonPrimitive.int)
        }

    @Test
    fun `every response carries a request id that is never taken from the client`() =
        app {
            val response = client.get("/ok") { headers.append("X-Request-Id", "attacker-chosen") }
            val requestId = response.json()["requestId"]!!.jsonPrimitive.content

            assertTrue(Regex("[0-9a-f]{32}").matches(requestId), requestId)
        }

    @Test
    fun `the request id is in the logging MDC, also after a coroutine switches threads`() =
        app {
            val body = client.get("/mdc").json()
            val ids =
                body["data"]!!
                    .let {
                        Json.parseToJsonElement(
                            it.toString(),
                        )
                    }.let { (it as JsonArray) }
                    .map { it.jsonPrimitive.content }
            val requestId = body["requestId"]!!.jsonPrimitive.content

            assertEquals(listOf(requestId, requestId, requestId), ids)
            assertNotNull(requestId)
        }
}
