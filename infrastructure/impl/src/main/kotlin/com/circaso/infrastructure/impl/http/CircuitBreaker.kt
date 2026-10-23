package com.circaso.infrastructure.impl.http

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.infrastructure.impl.logging.kv
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.http.Circuit")

/**
 * After [threshold] failures in a row the circuit opens for [openFor] and calls fail at once, so a vendor outage
 * doesn't pile up waiting requests. When the time is up one probe call goes through: success closes the
 * circuit, failure opens it again. A failure is an exception or a 5xx answer.
 */
class CircuitBreaker(
    val vendor: String,
    private val threshold: Int,
    private val openFor: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var failures = 0
    private var openedAt: Instant? = null
    private var probing = false

    @get:Synchronized
    val isOpen: Boolean get() = openedAt != null

    @Synchronized
    fun beforeCall() {
        val opened = openedAt ?: return
        if (clock.instant().isBefore(opened.plus(openFor)) || probing) throw CircuitOpenException(vendor)
        probing = true
    }

    @Synchronized
    fun onSuccess() {
        if (openedAt != null) log.info("circuit closed", kv("kind", "http"), kv("vendor", vendor), kv("circuit", "closed"))
        failures = 0
        openedAt = null
        probing = false
    }

    @Synchronized
    fun onFailure() {
        failures++
        if (probing || (openedAt == null && failures >= threshold)) {
            openedAt = clock.instant()
            probing = false
            log.error("circuit opened", kv("kind", "http"), kv("vendor", vendor), kv("circuit", "open"), kv("failures", failures), kv("openForSeconds", openFor.seconds))
        }
    }
}

class CircuitBreakerConfig {
    lateinit var breaker: CircuitBreaker
}

/** Wraps each attempt, so when retries are installed around it every failed attempt counts. */
val CircuitBreakerPlugin = createClientPlugin("CircuitBreaker", ::CircuitBreakerConfig) {
    val breaker = pluginConfig.breaker
    on(Send) { request ->
        breaker.beforeCall()
        try {
            val call = proceed(request)
            if (call.response.status.value >= 500) breaker.onFailure() else breaker.onSuccess()
            call
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            breaker.onFailure()
            throw e
        }
    }
}
