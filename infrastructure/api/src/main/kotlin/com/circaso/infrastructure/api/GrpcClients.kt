package com.circaso.infrastructure.api

import io.grpc.ManagedChannel
import java.time.Duration

/** Settings of one gRPC peer. The defaults mirror the HTTP clients: failures fast, retries only where safe. */
class GrpcClientConfig {
    /** `host:port`, or any target gRPC's name resolver understands. */
    var target: String? = null
    var tls: Boolean = true

    /** Applied to every call that doesn't set its own deadline. */
    var deadline: Duration = Duration.ofSeconds(15)

    /** Attempts in all, the first included. Only `UNAVAILABLE` is retried: the request never reached the peer. */
    var maxAttempts: Int = 3

    var circuitFailureThreshold: Int = 5
    var circuitOpenFor: Duration = Duration.ofSeconds(30)
}

/**
 * Outbound gRPC, the counterpart of [HttpClients]: one named channel per peer, with a default deadline, retries
 * of `UNAVAILABLE` only, the same circuit breaker, a log line and a metric per call (never a message body).
 * Generated stubs are built on the returned channel. The inbound gRPC server is the `grpc` module's, not this.
 */
interface GrpcClients {
    /** The channel for [name]; [configure] applies the first time the name is asked for. */
    fun channel(name: String, configure: GrpcClientConfig.() -> Unit = {}): ManagedChannel
}
