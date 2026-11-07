package com.circaso.opsgateway.auth

import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.operation
import com.circaso.opsgateway.AccessSettings
import com.circaso.shared.CircasoJson
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger("com.circaso.opsgateway.auth.HttpAccessKeys")

/**
 * The team's signing keys from `https://<team>/cdn-cgi/access/certs`, through the named `cloudflare-access` client
 * (timeouts, retries, circuit breaker). Kept [FRESH_FOR]; a token naming an unknown key reads them again, at most once
 * every [REFRESH_AT_MOST], so made-up key ids can't make us hammer Cloudflare. A failed read keeps the old keys.
 */
class HttpAccessKeys(clients: HttpClients, settings: AccessSettings, private val clock: Clock = Clock.systemUTC()) :
    AccessKeys {
    private val client = clients.named("cloudflare-access") { baseUrl = settings.issuer + "/" }
    private val mutex = Mutex()

    @Volatile
    private var cached: List<AccessJwk>? = null

    @Volatile
    private var readAt: Instant = Instant.EPOCH

    override suspend fun keys(refresh: Boolean): List<AccessJwk>? {
        recent(refresh)?.let { return it }
        return mutex.withLock {
            // Another caller may have read them while this one waited for the lock.
            recent(refresh)?.let { return@withLock it }
            read()?.also {
                cached = it
                readAt = clock.instant()
            } ?: cached
        }
    }

    /** The kept keys while they are fresh enough for this ask; a refresh only after [REFRESH_AT_MOST]. */
    private fun recent(refresh: Boolean): List<AccessJwk>? {
        val current = cached ?: return null
        val age = Duration.between(readAt, clock.instant())
        return current.takeIf { age < FRESH_FOR && (!refresh || age < REFRESH_AT_MOST) }
    }

    private suspend fun read(): List<AccessJwk>? =
        try {
            val response = client.get("cdn-cgi/access/certs") { operation("access_certs") }
            if (!response.status.isSuccess()) {
                log.warn("Access certificates not read: status={}", response.status.value)
                null
            } else {
                CircasoJson.decodeFromString(AccessJwks.serializer(), response.bodyAsText()).keys
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Access certificates not read: error={}", e.javaClass.name)
            null
        }

    private companion object {
        val FRESH_FOR: Duration = Duration.ofMinutes(10)
        val REFRESH_AT_MOST: Duration = Duration.ofSeconds(30)
    }
}
