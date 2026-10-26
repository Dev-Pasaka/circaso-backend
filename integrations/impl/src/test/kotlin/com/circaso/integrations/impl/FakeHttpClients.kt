package com.circaso.integrations.impl

import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.VendorHttpConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

/**
 * Stands in for the shared `HttpClients` in vendor tests: the client a vendor asks for is a real Ktor client over `MockEngine`, so the vendor's
 * own requests (path, headers, form or JSON body) are what the test inspects. Retries and the circuit breaker live in infrastructure and are tested there.
 * Every request is recorded in [requests]; [named] remembers the names asked for.
 */
class FakeHttpClients(private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) :
    HttpClients {
    val requests = mutableListOf<HttpRequestData>()
    val names = mutableListOf<String>()
    val configs = mutableMapOf<String, VendorHttpConfig>()
    val last: HttpRequestData get() = requests.last()

    override fun named(name: String, configure: VendorHttpConfig.() -> Unit): HttpClient {
        names += name
        val config = VendorHttpConfig().apply(configure)
        configs.putIfAbsent(name, config)
        return HttpClient(
            MockEngine { request ->
                requests += request
                handler(request)
            },
        ) {
            expectSuccess = false
            defaultRequest { config.baseUrl?.let { url(it) } }
        }
    }

    companion object {
        /** A JSON answer with [status]. */
        fun MockRequestHandleScope.json(body: String, status: Int = 200) =
            respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

/** The fields of a form body, as the vendor sent them. */
fun HttpRequestData.form(): Map<String, String> =
    (body as FormDataContent).formData.entries().associate {
        it.key to
            it.value.first()
    }

/** A JSON body, as text. */
fun HttpRequestData.text(): String = (body as? TextContent)?.text ?: String(body.toByteArrayOrEmpty())

private fun OutgoingContent.toByteArrayOrEmpty(): ByteArray =
    runCatching {
        kotlinx.coroutines.runBlocking { toByteArray() }
    }.getOrDefault(ByteArray(0))
