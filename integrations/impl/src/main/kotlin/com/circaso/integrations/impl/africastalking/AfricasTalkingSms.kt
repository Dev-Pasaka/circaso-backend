package com.circaso.integrations.impl.africastalking

import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SmsGateway
import com.circaso.integrations.api.SmsPurpose
import com.circaso.integrations.api.SmsReceipt
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/** The base URL for [username]: the sandbox (nothing reaches a phone) for `sandbox`, the live service otherwise. */
fun africasTalkingBaseUrl(username: String): String =
    if (username == "sandbox") "https://api.sandbox.africastalking.com" else "https://api.africastalking.com"

/**
 * Sends through Africa's Talking's SMS API (`POST /version1/messaging`). [http] is the shared `HttpClients.named("africastalking")` client
 * with that base URL, so timeouts, the circuit breaker and the vendor metrics come with it, and the number, the text (it holds the code)
 * and the API key never reach a log. This request carries no idempotency key, so the shared client does not retry it: a slow network never
 * sends the same code twice. [senderId] is optional: without it Africa's Talking sends from its shared short code.
 *
 * Per recipient: `100` processed, `101` sent, `102` queued are success. A number it cannot or will not reach (`403`, `404`, `406`, `407`)
 * is `Rejected`. A problem on our side or theirs (`401` risk hold, `402` sender id, `405` balance, `5xx`, an open circuit) is `Unavailable`
 * and logged as an error so it gets fixed, because the person did nothing wrong.
 */
class AfricasTalkingSms(
    private val username: String,
    private val apiKey: String,
    private val http: HttpClient,
    private val senderId: String? = null,
) : SmsGateway {
    private val log = LoggerFactory.getLogger(AfricasTalkingSms::class.java)

    override suspend fun send(
        to: PhoneNumber,
        text: String,
        purpose: SmsPurpose,
    ): Resource<SmsReceipt> {
        val form =
            Parameters.build {
                append("username", username)
                append("to", to.e164)
                append("message", text)
                senderId?.takeIf { it.isNotBlank() }?.let { append("from", it) }
            }
        val (status, body) =
            try {
                val response =
                    http.post("/version1/messaging") {
                        operation("send_sms")
                        header("apiKey", apiKey)
                        header(HttpHeaders.Accept, "application/json")
                        setBody(FormDataContent(form))
                    }
                response.status.value to response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("africastalking unreachable: purpose={} error={}", purpose, e::class.simpleName)
                return Resource.error(IntegrationError.Unavailable("Africa's Talking"), e)
            }

        if (status == 401 || status == 403) {
            log.error("africastalking refused our credentials: status={}", status)
            return Resource.error(IntegrationError.Unavailable("Africa's Talking"))
        }
        if (status >= 500 || status == 429) return Resource.error(IntegrationError.Unavailable("Africa's Talking"))
        if (status !in 200..299) {
            log.warn("africastalking answered {}: purpose={}", status, purpose)
            return Resource.error(IntegrationError.Rejected("The message could not be sent."))
        }

        val recipient =
            runCatching {
                Json
                    .parseToJsonElement(
                        body,
                    ).jsonObject["SMSMessageData"]!!
                    .jsonObject["Recipients"]!!
                    .jsonArray
                    .firstOrNull()
                    ?.jsonObject
            }.getOrNull()
        val code = recipient?.get("statusCode")?.jsonPrimitive?.intOrNull
        val messageId = recipient?.get("messageId")?.jsonPrimitive?.content

        return when (code) {
            100, 101, 102 -> {
                Resource.success(SmsReceipt(messageId ?: "unknown", "africastalking"))
            }

            403, 404, 406, 407 -> {
                log.info("africastalking would not deliver: code={} purpose={}", code, purpose)
                Resource.error(IntegrationError.Rejected("That number cannot receive the message."))
            }

            else -> {
                log.error("africastalking could not send: code={} purpose={}", code, purpose)
                Resource.error(IntegrationError.Unavailable("Africa's Talking"))
            }
        }
    }
}
