package com.circaso.infrastructure.api

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.AttributeKey
import java.time.Duration

const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

/** Thrown without calling the vendor while its circuit is open; integrations turn it into `ErrorKind.UPSTREAM`. */
class CircuitOpenException(val vendor: String) : RuntimeException("The '$vendor' circuit is open")

/** Names the vendor operation (`send_sms`) on a request, for the duration metric and the log line. */
val HttpOperation = AttributeKey<String>("circaso.http.operation")

fun HttpRequestBuilder.operation(name: String) = attributes.put(HttpOperation, name)

/** Settings of one vendor's client. The defaults are the architecture's: 5 s to connect, 15 s per request. */
class VendorHttpConfig {
    var baseUrl: String? = null
    var connectTimeout: Duration = Duration.ofSeconds(5)
    var requestTimeout: Duration = Duration.ofSeconds(15)

    /** Attempts in all, the first included. */
    var maxAttempts: Int = 3

    /** Failures in a row that open the circuit, and for how long it stays open. */
    var circuitFailureThreshold: Int = 5
    var circuitOpenFor: Duration = Duration.ofSeconds(30)
}

/**
 * One named client per vendor, used by integrations. It brings timeouts, retries, a circuit breaker and logging
 * that hides phone numbers, tokens and amounts. A POST is retried only when it carries an `Idempotency-Key`
 * (or when the vendor never received it), so a slow network never sends the same SMS or transfer twice.
 */
interface HttpClients {
    /** The client for [name] (`africastalking`); [configure] applies the first time the name is asked for. */
    fun named(name: String, configure: VendorHttpConfig.() -> Unit = {}): HttpClient
}
