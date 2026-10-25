package com.circaso.integrations.impl.fcm

import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.api.PushMessage
import com.circaso.integrations.api.PushReceipt
import com.circaso.integrations.api.PushTarget
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.time.Clock

private const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

/**
 * Sends through Firebase Cloud Messaging's HTTP v1 API (`POST /v1/projects/{id}/messages:send`) for both phones: Firebase hands an iOS
 * message to APNs with the key uploaded in the Firebase console. [http] is the shared `HttpClients.named("fcm")` client (base URL
 * `https://fcm.googleapis.com`) and [oauth] the `named("google-oauth")` one (no base URL: it posts to the service account's own token URL). No Firebase SDK:
 * the service account signs a short JWT, Google exchanges it for an access token, and that token (cached until five minutes before it
 * expires) goes on each send. A send carries no idempotency key, so the shared client does not retry it and a push is never doubled.
 *
 * A token Firebase no longer knows (app uninstalled, token rotated, or issued for another project) is `Rejected`, so the caller can
 * drop it. Our own credentials failing (`401`, `403`), `429`, `5xx`, a timeout or an open circuit is `Unavailable`. The device token and
 * the message text are never logged.
 */
class FcmPush(
    private val account: ServiceAccount,
    private val http: HttpClient,
    private val oauth: HttpClient,
    private val clock: Clock = Clock.systemUTC(),
) : PushGateway {
    private val log = LoggerFactory.getLogger(FcmPush::class.java)
    private val lock = Mutex()
    private var accessToken: String? = null
    private var expiresAt = 0L

    override suspend fun send(target: PushTarget, message: PushMessage): Resource<PushReceipt> {
        val bearer = accessToken() ?: return Resource.error(IntegrationError.Unavailable("Firebase"))
        val (status, text) =
            try {
                val response =
                    http.post("/v1/projects/${account.projectId}/messages:send") {
                        operation("send_push")
                        header(HttpHeaders.Authorization, "Bearer $bearer")
                        contentType(ContentType.Application.Json)
                        setBody(body(target, message))
                    }
                response.status.value to response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("fcm unreachable: platform={} error={}", target.platform, e::class.simpleName)
                return Resource.error(IntegrationError.Unavailable("Firebase"), e)
            }
        return answer(status, text, target)
    }

    private suspend fun answer(
        status: Int,
        text: String,
        target: PushTarget,
    ): Resource<PushReceipt> {
        val error = errorCode(text)
        return when {
            status in 200..299 -> {
                val name =
                    runCatching {
                        Json
                            .parseToJsonElement(text)
                            .jsonObject["name"]
                            ?.jsonPrimitive
                            ?.content
                    }.getOrNull()
                if (name == null) {
                    log.warn("fcm answered {} without a message name", status)
                    Resource.error(IntegrationError.Unavailable("Firebase"))
                } else {
                    Resource.success(PushReceipt(name.substringAfterLast('/'), "fcm"))
                }
            }

            status == 404 || error == "UNREGISTERED" || error == "SENDER_ID_MISMATCH" -> {
                log.info("fcm no longer accepts a token: platform={} error={}", target.platform, error)
                Resource.error(IntegrationError.Rejected("The device token is no longer valid."))
            }

            status == 401 || status == 403 -> {
                forgetToken()
                log.error("fcm refused our credentials: status={} error={}", status, error)
                Resource.error(IntegrationError.Unavailable("Firebase"))
            }

            status == 429 || status >= 500 -> {
                log.warn("fcm unavailable: status={} error={}", status, error)
                Resource.error(IntegrationError.Unavailable("Firebase"))
            }

            else -> {
                log.warn("fcm refused the push: status={} error={}", status, error)
                Resource.error(IntegrationError.Rejected("Firebase refused the push (status $status)."))
            }
        }
    }

    /** A notification both phones show on their own, with the data for the app to route on when it is opened. */
    private fun body(target: PushTarget, message: PushMessage): String =
        buildJsonObject {
            putJsonObject("message") {
                put("token", target.token)
                putJsonObject("notification") {
                    put("title", message.title)
                    put("body", message.body)
                }
                if (message.data.isNotEmpty()) putJsonObject("data") { message.data.forEach { (k, v) -> put(k, v) } }
                putJsonObject("android") { put("priority", "HIGH") }
                putJsonObject("apns") {
                    putJsonObject("headers") { put("apns-priority", "10") }
                    putJsonObject("payload") { putJsonObject("aps") { put("sound", "default") } }
                }
            }
        }.toString()

    /** FCM's own error code (`UNREGISTERED`, `SENDER_ID_MISMATCH`, ...) from the `details` of an error body, else its `status`. */
    private fun errorCode(text: String): String? =
        runCatching {
            val error = Json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject
            val detail =
                (error["details"] as? JsonArray)
                    ?.firstNotNullOfOrNull { it.jsonObject["errorCode"]?.jsonPrimitive?.content }
            detail ?: error["status"]?.jsonPrimitive?.content
        }.getOrNull()

    private suspend fun accessToken(): String? =
        lock.withLock {
            val now = clock.instant().epochSecond
            accessToken?.takeIf { now < expiresAt - 300 }?.let { return it }
            try {
                val response =
                    oauth.post(account.tokenUri) {
                        operation("fcm_access_token")
                        setBody(
                            FormDataContent(
                                Parameters.build {
                                    append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                                    append("assertion", account.assertion(SCOPE, clock))
                                },
                            ),
                        )
                    }
                val text = response.bodyAsText()
                if (response.status.value !in 200..299) {
                    log.error("google refused the fcm service account: status={}", response.status.value)
                    return null
                }
                val json = Json.parseToJsonElement(text).jsonObject
                accessToken = json["access_token"]!!.jsonPrimitive.content
                expiresAt = now + (json["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600)
                accessToken
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("google oauth unreachable: error={}", e::class.simpleName)
                null
            }
        }

    private suspend fun forgetToken() = lock.withLock { accessToken = null }
}
