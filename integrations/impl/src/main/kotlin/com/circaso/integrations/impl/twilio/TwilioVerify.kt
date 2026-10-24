package com.circaso.integrations.impl.twilio

import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.CodeCheck
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.PhoneVerificationGateway
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Twilio Verify: Twilio makes the code, texts it, expires it and checks it. `POST /Verifications` starts one for a number;
 * `POST /VerificationCheck` asks whether a code is right. [http] is the shared `HttpClients.named("twilio")` client (base URL
 * `https://verify.twilio.com`), so timeouts, retries, the circuit breaker and the vendor metrics come with it, and the number and
 * the code never reach a log. Twilio's answers, in our terms: a wrong code is [CodeCheck.DENIED]; `404` (nothing pending: expired,
 * used, or out of tries) is [CodeCheck.EXPIRED]; a number it will not text (`400`) is `Rejected`; its own limits (`429`) are
 * [SharedError.RateLimited]; bad credentials, an outage, an open circuit or an unreadable reply are `Unavailable`.
 */
class TwilioVerify(
    accountSid: String,
    authToken: String,
    private val serviceSid: String,
    private val http: HttpClient,
    private val locale: String? = null,
) : PhoneVerificationGateway {
    private val log = LoggerFactory.getLogger(TwilioVerify::class.java)
    private val authorization = "Basic " + Base64.getEncoder().encodeToString("$accountSid:$authToken".toByteArray())

    override val managesCodes: Boolean = true

    override suspend fun start(to: PhoneNumber, appHash: String?): Resource<Unit> {
        val form = Parameters.build {
            append("To", to.e164)
            append("Channel", "sms")
            locale?.let { append("Locale", it) }
            appHash?.takeIf { it.isNotBlank() }?.let { append("AppHash", it) }
        }
        return when (val r = post("/v2/Services/$serviceSid/Verifications", "start_verification", form)) {
            is Resource.Error -> Resource.error(r.error, r.cause)
            is Resource.Success -> if (r.data.status in 200..299) Resource.success(Unit) else failure(r.data, "start")
        }
    }

    override suspend fun check(to: PhoneNumber, code: String): Resource<CodeCheck> {
        val form = Parameters.build {
            append("To", to.e164)
            append("Code", code)
        }
        return when (val r = post("/v2/Services/$serviceSid/VerificationCheck", "check_verification", form)) {
            is Resource.Error -> Resource.error(r.error, r.cause)
            is Resource.Success -> when (r.data.status) {
                in 200..299 -> {
                    val json = runCatching { Json.parseToJsonElement(r.data.body).jsonObject }.getOrNull()
                    val approved = json?.get("status")?.jsonPrimitive?.content == "approved" || json?.get("valid")?.jsonPrimitive?.booleanOrNull == true
                    if (json == null) Resource.error(IntegrationError.Unavailable("Twilio")) else Resource.success(if (approved) CodeCheck.APPROVED else CodeCheck.DENIED)
                }
                404 -> Resource.success(CodeCheck.EXPIRED)
                else -> failure(r.data, "check")
            }
        }
    }

    /** Twilio's numeric error code (60200 invalid number, 21608 trial account and unverified number, ...), from its reply. Never the message: it can hold the number. */
    private fun errorCode(body: String): Int? =
        runCatching { Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.intOrNull }.getOrNull()

    private fun <T> failure(answer: Answer, step: String): Resource<T> = when (val status = answer.status) {
        400 -> {
            log.warn("twilio would not text this number: step={} twilioCode={}", step, errorCode(answer.body))
            Resource.error(IntegrationError.Rejected("That number cannot receive the code."))
        }
        401, 403 -> {
            log.error("twilio refused: status={} step={} twilioCode={}", status, step, errorCode(answer.body))
            Resource.error(IntegrationError.Unavailable("Twilio"))
        }
        429 -> {
            log.warn("twilio rate limited: step={} twilioCode={}", step, errorCode(answer.body))
            Resource.error(SharedError.RateLimited(600))
        }
        else -> {
            log.warn("twilio answered {}: step={} twilioCode={}", status, step, errorCode(answer.body))
            Resource.error(IntegrationError.Unavailable("Twilio"))
        }
    }

    private class Answer(val status: Int, val body: String)

    private suspend fun post(path: String, operation: String, form: Parameters): Resource<Answer> =
        try {
            val response = http.post(path) {
                operation(operation)
                header(HttpHeaders.Authorization, authorization)
                header(HttpHeaders.Accept, "application/json")
                setBody(FormDataContent(form))
            }
            Resource.success(Answer(response.status.value, response.bodyAsText()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class only: a message can carry the URL. The shared client has already logged the attempt.
            log.warn("twilio unreachable: {}", e::class.simpleName)
            Resource.error(IntegrationError.Unavailable("Twilio"), e)
        }
}
