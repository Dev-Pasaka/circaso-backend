package com.circaso.integrations.impl.resend

import com.circaso.infrastructure.api.IDEMPOTENCY_KEY_HEADER
import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.Email
import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.EmailReceipt
import com.circaso.integrations.api.EmailTemplateIds
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.CircasoJson
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** Resend's `POST /emails` body for a stored template, with [V] the template's own variables. */
@Serializable
private data class SendTemplateEmail<V>(val from: String, val to: List<String>, val template: TemplateRef<V>)

@Serializable
private data class TemplateRef<V>(val id: String, val variables: V)

/** Resend's answer to a send; only the id is read. */
@Serializable
private data class SentEmail(val id: String)

/** `new-phone-sign-in`'s and `pin-vault-opened`'s variables, under the names the Resend templates use. */
@Serializable
private data class DeviceModelVariables(
    @SerialName("DEVICE_MODEL") val deviceModel: String,
)

/** `merchant-sign-in-code`'s variables: the code, and how many minutes it lasts. */
@Serializable
private data class SignInCodeVariables(
    @SerialName("CODE") val code: String,
    @SerialName("MINUTES") val minutes: Int,
)

/** A template that takes no variables: `{}`. */
@Serializable
private data object NoVariables

/** The request body for [email] with the template [templateId]: each email's fields under Resend's variable names. */
private fun sendBody(
    from: String,
    to: String,
    templateId: String,
    email: Email,
): String =
    when (email) {
        is Email.NewPhoneSignIn -> {
            encode(
                from,
                to,
                templateId,
                DeviceModelVariables(email.deviceModel),
                DeviceModelVariables.serializer(),
            )
        }

        is Email.PinVaultOpened -> {
            encode(
                from,
                to,
                templateId,
                DeviceModelVariables(email.deviceModel),
                DeviceModelVariables.serializer(),
            )
        }

        is Email.MerchantSignInCode -> {
            encode(
                from,
                to,
                templateId,
                SignInCodeVariables(email.code, email.minutes),
                SignInCodeVariables.serializer(),
            )
        }

        Email.RecoveryStarted, Email.RecoveryCompleted, Email.Welcome, Email.MerchantTakenOffline -> {
            encode(from, to, templateId, NoVariables, NoVariables.serializer())
        }
    }

private fun <V> encode(
    from: String,
    to: String,
    templateId: String,
    variables: V,
    variablesSerializer: KSerializer<V>,
): String =
    CircasoJson.encodeToString(
        SendTemplateEmail.serializer(variablesSerializer),
        SendTemplateEmail(from, listOf(to), TemplateRef(templateId, variables)),
    )

/**
 * Sends through Resend's `POST /emails` with a stored template: the subject, wording and layout live in the Resend dashboard, and this
 * class only names the template and fills its variables. The template's id is asked from [templates] on each send (a row staff
 * set in the admin; decision 2026-11-22); one with no id yet fails that send alone with `Unavailable`, and nothing stops the server. [http] is the shared `HttpClients.named("resend")` client (base URL
 * `https://api.resend.com`, with the `User-Agent` Resend insists on), so timeouts, the circuit breaker and the vendor metrics come with
 * it. A request with an [idempotencyKey] is retried safely (Resend delivers it once); one without is not retried. Neither the address nor
 * the variables are logged. A request the vendor refuses (`4xx`, except `401`, `403` and `429`) is `Rejected`; our own bad key or sender
 * (`401`, `403`), a timeout, `429` (10 requests a second per team), `5xx` or an open circuit is `Unavailable`.
 */
class ResendEmail(
    private val apiKey: String,
    private val from: String,
    private val templates: EmailTemplateIds,
    private val http: HttpClient,
) : EmailGateway {
    private val log = LoggerFactory.getLogger(ResendEmail::class.java)

    override suspend fun send(
        to: String,
        email: Email,
        idempotencyKey: String?,
    ): Resource<EmailReceipt> {
        val template = email.template
        val templateId = templates.idOf(template)?.takeIf { it.isNotBlank() }
        if (templateId == null) {
            // Not set in the admin yet: this one email isn't sent; nothing else is affected, and Resend isn't asked.
            log.warn("resend template id not set: template={}", template)
            return Resource.error(IntegrationError.Unavailable("Resend", isTransient = false))
        }
        val body = sendBody(from, to, templateId, email)

        val (status, text) =
            try {
                val response =
                    http.post("/emails") {
                        operation("send_email")
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                        header(HttpHeaders.UserAgent, "circaso-backend") // Resend answers 403 to a request with none
                        idempotencyKey?.let { header(IDEMPOTENCY_KEY_HEADER, it.take(256)) }
                        contentType(ContentType.Application.Json)
                        setBody(body)
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
                val id = runCatching { CircasoJson.decodeFromString(SentEmail.serializer(), text).id }.getOrNull()
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
