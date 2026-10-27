package com.circaso.infrastructure.api

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.AttributeKey
import java.time.Duration

const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

/** Thrown without calling the vendor while its circuit is open; integrations turn it into `ErrorKind.UPSTREAM`. */
class CircuitOpenException(val vendor: String) : RuntimeException("The '$vendor' circuit is open")

/** Thrown when a vendor's socket doesn't open within its handshake timeout; integrations treat it as `Unavailable`. */
class HandshakeTimeoutException(val vendor: String) : RuntimeException("The '$vendor' socket didn't open in time")

/**
 * Thrown when a vendor answers a socket's upgrade with anything but `101 Switching Protocols` (Workers AI's AI Gateway
 * refuses some live models with a `400`); integrations treat it as `Unavailable`. The attempt log line has the status.
 */
class HandshakeRefusedException(val vendor: String, val status: Int) :
    RuntimeException("The '$vendor' socket was refused with status $status")

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

    /**
     * Installs Ktor's WebSockets plugin, for a vendor reached over a live socket (Workers AI live voice). On such a
     * client a WebSocket upgrade is never retried and never counts toward the circuit: a socket that fails half way
     * through a conversation can't be replayed, and the caller decides whether to open another. [requestTimeout]
     * doesn't apply to a socket either; only its opening is bounded, by [connectTimeout] and [handshakeTimeout].
     */
    var webSockets: Boolean = false

    /** How long opening a socket may take, from the first byte to the `101 Switching Protocols`. */
    var handshakeTimeout: Duration = Duration.ofSeconds(10)

    /** How often an open socket pings the vendor, so neither side nor a proxy takes a quiet socket for a dead one. */
    var pingInterval: Duration = Duration.ofSeconds(20)
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
