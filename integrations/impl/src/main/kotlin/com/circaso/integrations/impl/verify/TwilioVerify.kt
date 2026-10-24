package com.circaso.integrations.impl.verify

import com.circaso.integrations.api.CodeCheck
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.PhoneVerificationGateway
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

/**
 * Twilio Verify: Twilio makes the code, texts it, expires it and checks it. `POST /Verifications` starts one for a number;
 * `POST /VerificationCheck` asks whether a code is right. The number and the code are never logged. Twilio's answers, in our terms:
 * a code that is wrong is [CodeCheck.DENIED]; `404` (nothing pending: expired, used, or out of tries) is [CodeCheck.EXPIRED]; a number it will
 * not text (`400`) is `Rejected`; its own limits (`429`) are [SharedError.RateLimited]; bad credentials, an outage or an unreadable reply are
 * `Unavailable`, and bad credentials are logged as an error because the person did nothing wrong.
 */
class TwilioVerify(
    private val accountSid: String,
    private val authToken: String,
    private val serviceSid: String,
    private val locale: String? = null,
    private val baseUrl: String = "https://verify.twilio.com",
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : PhoneVerificationGateway {
    private val log = LoggerFactory.getLogger(TwilioVerify::class.java)
    private val authorization = "Basic " + Base64.getEncoder().encodeToString("$accountSid:$authToken".toByteArray())

    override val managesCodes: Boolean = true

    override suspend fun start(to: PhoneNumber, appHash: String?): Resource<Unit> {
        val form = buildList {
            add("To" to to.e164)
            add("Channel" to "sms")
            locale?.let { add("Locale" to it) }
            appHash?.takeIf { it.isNotBlank() }?.let { add("AppHash" to it) }
        }
        return when (val r = post("/v2/Services/$serviceSid/Verifications", form)) {
            is Resource.Error -> Resource.error(r.error, r.cause)
            is Resource.Success -> when (r.data.status) {
                in 200..299 -> Resource.success(Unit)
                else -> failure(r.data.status, "start")
            }
        }
    }

    override suspend fun check(to: PhoneNumber, code: String): Resource<CodeCheck> {
        return when (val r = post("/v2/Services/$serviceSid/VerificationCheck", listOf("To" to to.e164, "Code" to code))) {
            is Resource.Error -> Resource.error(r.error, r.cause)
            is Resource.Success -> when (r.data.status) {
                in 200..299 -> {
                    val json = runCatching { Json.parseToJsonElement(r.data.body).jsonObject }.getOrNull()
                    val approved = json?.get("status")?.jsonPrimitive?.content == "approved" || json?.get("valid")?.jsonPrimitive?.booleanOrNull == true
                    if (json == null) Resource.error(IntegrationError.Unavailable("Twilio")) else Resource.success(if (approved) CodeCheck.APPROVED else CodeCheck.DENIED)
                }
                404 -> Resource.success(CodeCheck.EXPIRED)
                else -> failure(r.data.status, "check")
            }
        }
    }

    private fun <T> failure(status: Int, step: String): Resource<T> = when (status) {
        400 -> {
            log.info("twilio would not text this number: step={}", step)
            Resource.error(IntegrationError.Rejected("That number cannot receive the code."))
        }
        401, 403 -> {
            log.error("twilio refused our credentials: status={} step={}", status, step)
            Resource.error(IntegrationError.Unavailable("Twilio"))
        }
        429 -> Resource.error(SharedError.RateLimited(600))
        else -> {
            log.warn("twilio answered {}: step={}", status, step)
            Resource.error(IntegrationError.Unavailable("Twilio"))
        }
    }

    private class Answer(val status: Int, val body: String)

    private suspend fun post(path: String, form: List<Pair<String, String>>): Resource<Answer> {
        val body = form.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", authorization)
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return try {
            val response = withContext(Dispatchers.IO) { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            Resource.success(Answer(response.statusCode(), response.body()))
        } catch (e: IOException) {
            log.warn("twilio unreachable", e)
            Resource.error(IntegrationError.Unavailable("Twilio"), e)
        }
    }
}
