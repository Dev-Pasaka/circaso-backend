package com.circaso.gateway

import com.circaso.infrastructure.api.RedisScope
import com.circaso.integrations.api.KeyedWebhookVendor
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
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger("com.circaso.gateway.Webhooks")
private val REPLAY_WINDOW: Duration = Duration.ofMinutes(5)
private val SEEN_FOR: Duration = Duration.ofDays(7)
private val HOOK_KEY = Regex("[A-Za-z0-9-]{8,96}")

/**
 * `POST /v1/hooks/{vendor}`: the gateway's guards, then the vendor's own translation. The guards are the vendor's
 * signature, a timestamp window against replays, a record of delivery ids so a repeat is processed once, and an
 * IP allowlist where the vendor publishes one. The answer is `200` at once and the verified body is handed to the
 * vendor's translation afterwards, so a slow translation never makes the vendor retry; except for a vendor that
 * [WebhookVendor.answersAfterTranslation] (Circle), which waits for it and answers the error when it fails, so the
 * vendor delivers again.
 */
fun Route.webhookRoutes(
    vendors: List<WebhookVendor>,
    config: GatewayConfig,
    redis: RedisScope,
    scope: CoroutineScope,
) {
    val byName = vendors.associateBy { it.name }

    route("/hooks") {
        // Circle checks a notification URL with a HEAD before it sends anything to it; nothing is verified or read.
        head("{vendor}") {
            val known = call.parameters["vendor"].orEmpty() in byName
            call.respond(if (known) HttpStatusCode.OK else HttpStatusCode.NotFound)
        }.docs<NoBody, NoBody>(
            "Check a vendor's callback address",
            "Webhooks",
            notes =
                "`200` with no body when `{vendor}` is a registered sender, `404` otherwise. Vendors that validate " +
                    "an endpoint before using it (Circle) call this; no signature is needed.",
            envelope = false,
        )
        post("{vendor}") {
            val name = call.parameters["vendor"].orEmpty()
            receiveHook(name, byName[name], config, redis, scope)
        }.docs<NoBody, NoBody>(
            "Receive a vendor's callback",
            "Webhooks",
            listOf("auth.token_invalid", "auth.scope_insufficient", "request.not_found"),
            notes =
                "`{vendor}` names the sender (for example `africastalking`). There is no user token: the call must " +
                    "carry the vendor's signature, " +
                    "a timestamp inside a 5-minute window and a delivery id not seen before, and come from the " +
                    "vendor's allowlisted address where it publishes one. " +
                    "A repeated delivery is acknowledged but processed once. Answers `200` at once and handles the " +
                    "body afterwards, except for `circle` and `didit`: it waits, and answers the error when the " +
                    "update can't be applied, so the vendor delivers it again.",
        )
        // A callback address with a key in it (a ramp merchant's own; decision 2026-11-22). Same guards, and the
        // vendor is handed the key: an address that isn't one it gave out is refused by the vendor's listener.
        post("{vendor}/{key}") {
            val name = call.parameters["vendor"].orEmpty()
            val key = call.parameters["key"].orEmpty()
            val vendor = (byName[name] as? KeyedWebhookVendor)?.takeIf { key.matches(HOOK_KEY) }?.keyed(key)
            receiveHook(name, vendor, config, redis, scope)
        }.docs<NoBody, NoBody>(
            "Receive a vendor's callback on a keyed address",
            "Webhooks",
            listOf("auth.token_invalid", "auth.scope_insufficient", "request.not_found"),
            notes =
                "For a vendor whose callback addresses carry a key (`payhero`: each ramp merchant has an address of " +
                    "their own). The same guards as the plain address; a vendor that takes no key, or a key that " +
                    "isn't well formed, answers `request.not_found`.",
        )
    }
}

/** The guards every callback passes, then the vendor's translation; a null [vendor] is an unknown sender. */
private suspend fun RoutingContext.receiveHook(
    name: String,
    vendor: WebhookVendor?,
    config: GatewayConfig,
    redis: RedisScope,
    scope: CoroutineScope,
) {
    if (vendor == null) {
        call.respondDefault(SharedError.NotFound.toResponse())
        return
    }
    config.webhookAllowedIps[name]?.let { allowed ->
        if (call.clientIp() !in allowed) {
            call.respondDefault(SharedError.ScopeInsufficient.toResponse())
            return
        }
    }

    val body = call.receive<ByteArray>()
    val headers =
        call.request.headers
            .entries()
            .associate { it.key.lowercase() to it.value.first() }

    if (!vendor.verifySignature(headers, body)) {
        call.respondDefault(SharedError.TokenInvalid.toResponse())
        return
    }
    vendor.sentAt(headers, body)?.let { sentAt ->
        if (Duration.between(sentAt, Instant.now()).abs() > REPLAY_WINDOW) {
            call.respondDefault(SharedError.TokenInvalid.toResponse())
            return
        }
    }
    val seenKey = vendor.deliveryId(headers, body)?.let { id -> "hook:$name:$id" }
    if (vendor.answersAfterTranslation) {
        answerAfterTranslation(vendor, body, seenKey, redis)
    } else {
        answerAtOnce(vendor, body, seenKey, redis, scope)
    }
}

private val RECEIVED = DefaultResponse<Unit>(HttpStatusCode.OK.value, true, "Received")

/** Most vendors: the id is claimed first, the answer is `200` at once, and the body is translated afterwards. */
private suspend fun RoutingContext.answerAtOnce(
    vendor: WebhookVendor,
    body: ByteArray,
    seenKey: String?,
    redis: RedisScope,
    scope: CoroutineScope,
) {
    val duplicate = seenKey?.let { !redis.setIfAbsent(it, "1", SEEN_FOR) } ?: false
    call.respondDefault(RECEIVED)
    if (!duplicate) scope.launch { translate(vendor, body) }
}

/**
 * A vendor that delivers again until it gets a 2xx (Circle): the id is marked as seen only once the translation has
 * succeeded. A failure, an exception or a cancelled call leaves no mark, so the next delivery runs; two deliveries at
 * once may both run, which the handlers allow (they are idempotent and ignore older versions).
 */
private suspend fun RoutingContext.answerAfterTranslation(
    vendor: WebhookVendor,
    body: ByteArray,
    seenKey: String?,
    redis: RedisScope,
) {
    if (seenKey != null && redis.exists(seenKey)) {
        call.respondDefault(RECEIVED)
        return
    }
    val result = translate(vendor, body)
    if (result is Resource.Error) {
        call.respondDefault(result.error.toResponse())
        return
    }
    seenKey?.let { redis.set(it, "1", SEEN_FOR) }
    call.respondDefault(RECEIVED)
}

private suspend fun translate(vendor: WebhookVendor, body: ByteArray): Resource<Unit> {
    val result =
        runCatching { vendor.translate(body) }.getOrElse {
            if (it is CancellationException) throw it
            Resource.error(SharedError.Internal, it)
        }
    if (result is Resource.Error) {
        log.warn("webhook not translated: vendor={} code={}", vendor.name, result.error.code, result.cause)
    }
    return result
}

private fun DomainError.toResponse(): DefaultResponse<Unit> = toDefaultResponse()
