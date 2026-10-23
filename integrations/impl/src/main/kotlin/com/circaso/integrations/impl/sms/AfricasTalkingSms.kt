package com.circaso.integrations.impl.sms

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SmsGateway
import com.circaso.integrations.api.SmsPurpose
import com.circaso.integrations.api.SmsReceipt
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Sends through Africa's Talking's SMS API (`POST /version1/messaging`). The username `sandbox` goes to their sandbox
 * (nothing is delivered to a phone there), any other username to the live service. [senderId] is optional: without it
 * Africa's Talking sends from its shared short code. The number, the text (it holds the code) and the API key are never logged.
 *
 * What Africa's Talking answers per recipient: `100` processed, `101` sent, `102` queued are success. A number it cannot
 * or will not reach (`403`, `404`, `406`, `407`) is `Rejected`. A problem on our side or theirs (`401` risk hold, `402`
 * sender id, `405` balance, `5xx`) is `Unavailable` and logged as an error so it gets fixed, because the person did nothing wrong.
 */
class AfricasTalkingSms(
    private val username: String,
    private val apiKey: String,
    private val senderId: String? = null,
    private val baseUrl: String = if (username == "sandbox") "https://api.sandbox.africastalking.com" else "https://api.africastalking.com",
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : SmsGateway {
    private val log = LoggerFactory.getLogger(AfricasTalkingSms::class.java)

    override suspend fun send(to: PhoneNumber, text: String, purpose: SmsPurpose): Resource<SmsReceipt> {
        val form = buildList {
            add("username" to username)
            add("to" to to.e164)
            add("message" to text)
            senderId?.takeIf { it.isNotBlank() }?.let { add("from" to it) }
        }.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/version1/messaging"))
            .timeout(Duration.ofSeconds(10))
            .header("apiKey", apiKey)
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()

        val response = try {
            withContext(Dispatchers.IO) { client.send(request, HttpResponse.BodyHandlers.ofString()) }
        } catch (e: IOException) {
            log.warn("africastalking unreachable: purpose={}", purpose, e)
            return Resource.error(IntegrationError.Unavailable("Africa's Talking"), e)
        }

        val status = response.statusCode()
        if (status == 401 || status == 403) {
            log.error("africastalking refused our credentials: status={}", status)
            return Resource.error(IntegrationError.Unavailable("Africa's Talking"))
        }
        if (status >= 500 || status == 429) return Resource.error(IntegrationError.Unavailable("Africa's Talking"))
        if (status !in 200..299) {
            log.warn("africastalking answered {}: purpose={}", status, purpose)
            return Resource.error(IntegrationError.Rejected("The message could not be sent."))
        }

        val recipient = runCatching {
            Json.parseToJsonElement(response.body()).jsonObject["SMSMessageData"]!!.jsonObject["Recipients"]!!.jsonArray.firstOrNull()?.jsonObject
        }.getOrNull()
        val code = recipient?.get("statusCode")?.jsonPrimitive?.intOrNull
        val messageId = recipient?.get("messageId")?.jsonPrimitive?.content

        return when (code) {
            100, 101, 102 -> Resource.success(SmsReceipt(messageId ?: "unknown", "africastalking"))
            403, 404, 406, 407 -> {
                log.info("africastalking would not deliver: code={} purpose={}", code, purpose)
                Resource.error(IntegrationError.Rejected("That number cannot receive the message."))
            }
            else -> {
                // 401 risk hold, 402 sender id, 405 balance, 5xx, or an answer we do not understand: ours to fix, not the person's.
                log.error("africastalking could not send: code={} purpose={}", code, purpose)
                Resource.error(IntegrationError.Unavailable("Africa's Talking"))
            }
        }
    }
}
