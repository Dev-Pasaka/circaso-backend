package com.circaso.integrations.impl.email

import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.EmailReceipt
import com.circaso.integrations.api.EmailTemplate
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Sends through Resend's `POST /emails` with a stored template: the subject, wording and layout live in the Resend
 * dashboard, and this class only names the template and fills its variables. Neither the address nor the variables
 * are logged. A request the vendor refuses (`4xx`, except `401`, `403` and `429`) is `Rejected`; our own bad key or sender (`401`, `403`),  a timeout, `429` (10 requests a second per team) or `5xx` is `Unavailable`.
 */
class ResendEmail(
    private val apiKey: String,
    private val from: String,
    private val templates: Map<EmailTemplate, String>,
    private val baseUrl: String = "https://api.resend.com",
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : EmailGateway {
    private val log = LoggerFactory.getLogger(ResendEmail::class.java)

    override suspend fun send(to: String, template: EmailTemplate, variables: Map<String, String>, idempotencyKey: String?): Resource<EmailReceipt> {
        val templateId = templates[template] ?: return Resource.error(IntegrationError.Rejected("No Resend template is configured for $template."))
        val body = buildJsonObject {
            put("from", from)
            put("to", buildJsonArray { add(JsonPrimitive(to)) })
            putJsonObject("template") {
                put("id", templateId)
                putJsonObject("variables") { variables.forEach { (k, v) -> put(k, v) } }
            }
        }
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/emails"))
            .apply { idempotencyKey?.let { header("Idempotency-Key", it.take(256)) } }
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            // Resend answers 403 (error 1010) to a request without a User-Agent, even with a valid key.
            .header("User-Agent", "circaso-backend")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val response = try {
            withContext(Dispatchers.IO) { client.send(request, HttpResponse.BodyHandlers.ofString()) }
        } catch (e: IOException) {
            log.warn("resend unreachable: template={}", template, e)
            return Resource.error(IntegrationError.Unavailable("Resend"), e)
        }

        val status = response.statusCode()
        return when {
            status in 200..299 -> {
                val id = runCatching { Json.parseToJsonElement(response.body()).jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
                if (id == null) {
                    log.warn("resend answered {} without an id: template={}", status, template)
                    Resource.error(IntegrationError.Unavailable("Resend"))
                } else {
                    Resource.success(EmailReceipt(id, "resend"))
                }
            }
            // 401 and 403 are our key or sender being wrong, not the caller's input: an outage from the caller's side, an error for us.
            status == 401 || status == 403 -> {
                log.error("resend refused our credentials: status={} template={}", status, template)
                Resource.error(IntegrationError.Unavailable("Resend"))
            }
            status == 429 || status >= 500 -> {
                log.warn("resend unavailable: status={} template={}", status, template)
                Resource.error(IntegrationError.Unavailable("Resend"))
            }
            else -> {
                log.warn("resend refused: status={} template={}", status, template)
                Resource.error(IntegrationError.Rejected("Resend refused the email (status $status)."))
            }
        }
    }
}
