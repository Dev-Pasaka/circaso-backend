package com.circaso.gateway

import com.circaso.infrastructure.api.RedisScope
import com.circaso.integrations.api.WebhookVendor
import com.circaso.shared.DomainError
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.web.DefaultResponse
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger("com.circaso.gateway.Webhooks")
private val REPLAY_WINDOW: Duration = Duration.ofMinutes(5)
private val SEEN_FOR: Duration = Duration.ofDays(7)

/**
 * `POST /v1/hooks/{vendor}`: the gateway's guards, then the vendor's own translation. The guards are the vendor's
 * signature, a timestamp window against replays, a record of delivery ids so a repeat is processed once, and an
 * IP allowlist where the vendor publishes one. The answer is `200` at once; the verified body is handed to the
 * vendor's translation afterwards, so a slow translation never makes the vendor retry.
 */
fun Route.webhookRoutes(
    vendors: List<WebhookVendor>,
    config: GatewayConfig,
    redis: RedisScope,
    scope: CoroutineScope,
) {
    val byName = vendors.associateBy { it.name }

    route("/hooks") {
        post("{vendor}") {
            val name = call.parameters["vendor"].orEmpty()
            val vendor = byName[name]
            if (vendor == null) {
                call.respondDefault(SharedError.NotFound.toResponse())
                return@post
            }
            config.webhookAllowedIps[name]?.let { allowed ->
                if (call.clientIp() !in allowed) {
                    call.respondDefault(SharedError.ScopeInsufficient.toResponse())
                    return@post
                }
            }

            val body = call.receive<ByteArray>()
            val headers =
                call.request.headers
                    .entries()
                    .associate { it.key.lowercase() to it.value.first() }

            if (!vendor.verifySignature(headers, body)) {
                call.respondDefault(SharedError.TokenInvalid.toResponse())
                return@post
            }
            vendor.sentAt(headers, body)?.let { sentAt ->
                if (Duration.between(sentAt, Instant.now()).abs() > REPLAY_WINDOW) {
                    call.respondDefault(SharedError.TokenInvalid.toResponse())
                    return@post
                }
            }
            val duplicate =
                vendor.deliveryId(headers, body)?.let { id -> !redis.setIfAbsent("hook:$name:$id", "1", SEEN_FOR) }
                    ?: false

            call.respondDefault(DefaultResponse<Unit>(HttpStatusCode.OK.value, true, "Received"))
            if (!duplicate) {
                scope.launch {
                    val result =
                        runCatching {
                            vendor.translate(
                                body,
                            )
                        }.getOrElse { Resource.error(SharedError.Internal, it) }
                    if (result is Resource.Error) {
                        log.warn(
                            "webhook not translated: vendor={} code={}",
                            name,
                            result.error.code,
                            result.cause,
                        )
                    }
                }
            }
        }.docs<NoBody, NoBody>(
            "Receive a vendor's callback",
            "Webhooks",
            listOf("auth.token_invalid", "auth.scope_insufficient", "request.not_found"),
            notes =
                "`{vendor}` names the sender (for example `africastalking`). There is no user token: the call must " +
                    "carry the vendor's signature, " +
                    "a timestamp inside a 5-minute window and a delivery id not seen before, and come from the " +
                    "vendor's allowlisted address where it publishes one. " +
                    "A repeated delivery is acknowledged but processed once. Answers `200` at once; the body is " +
                    "handled afterwards.",
        )
    }
}

private fun DomainError.toResponse(): DefaultResponse<Unit> = toDefaultResponse()
