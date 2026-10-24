package com.circaso.integrations.impl.resend

import com.circaso.infrastructure.api.IDEMPOTENCY_KEY_HEADER
import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.EmailReceipt
import com.circaso.integrations.api.EmailTemplate
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * Sends through Resend's `POST /emails` with a stored template: the subject, wording and layout live in the Resend dashboard, and this
 * class only names the template and fills its variables. [http] is the shared `HttpClients.named("resend")` client (base URL
 * `https://api.resend.com`, with the `User-Agent` Resend insists on), so timeouts, the circuit breaker and the vendor metrics come with
 * it. A request with an [idempotencyKey] is retried safely (Resend delivers it once); one without is not retried. Neither the address nor
 * the variables are logged. A request the vendor refuses (`4xx`, except `401`, `403` and `429`) is `Rejected`; our own bad key or sender
 * (`401`, `403`), a timeout, `429` (10 requests a second per team), `5xx` or an open circuit is `Unavailable`.
 */
class ResendEmail(
    private val apiKey: String,
    private val from: String,
    private val templates: Map<EmailTemplate, String>,
    private val http: HttpClient,
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

        val (status, text) = try {
            val response = http.post("/emails") {
                operation("send_email")
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header(HttpHeaders.UserAgent, "circaso-backend") // Resend answers 403 to a request with none
                idempotencyKey?.let { header(IDEMPOTENCY_KEY_HEADER, it.take(256)) }
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            response.status.value to response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("resend unreachable: template={} error={}", template, e::class.simpleName)
            return Resource.error(IntegrationError.Unavailable("Resend"), e)
        }

        return when {
            status in 200..299 -> {
                val id = runCatching { Json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
                if (id == null) {
                    log.warn("resend answered {} without an id: template={}", status, template)
                    Resource.error(IntegrationError.Unavailable("Resend"))
                } else {
                    Resource.success(EmailReceipt(id, "resend"))
                }
            }
            // 401 and 403 are our key or sender being wrong, not the caller's input: an error for us.
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
