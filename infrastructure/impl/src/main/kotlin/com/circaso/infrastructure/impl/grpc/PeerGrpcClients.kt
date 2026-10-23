package com.circaso.infrastructure.impl.grpc

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.infrastructure.api.GrpcClientConfig
import com.circaso.infrastructure.api.GrpcClients
import com.circaso.infrastructure.impl.http.CircuitBreaker
import com.circaso.infrastructure.impl.logging.kv
import com.circaso.infrastructure.impl.logging.millisSince
import com.circaso.observability.api.Metrics
import com.circaso.observability.api.NoopTelemetry
import com.circaso.observability.api.Telemetry
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingClientCallListener
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.grpc.Peer")

/**
 * One named channel per gRPC peer. Retries are gRPC's own, limited to `UNAVAILABLE` (the request never reached the
 * peer, so repeating is safe). A call with no deadline gets the configured one. Each call is checked by the circuit
 * breaker, logged and measured; message bodies and metadata are never logged.
 */
class PeerGrpcClients(
    private val telemetry: Telemetry = NoopTelemetry,
    private val clock: Clock = Clock.systemUTC(),
    private val builderFor: (target: String) -> ManagedChannelBuilder<*> = { ManagedChannelBuilder.forTarget(it) },
) : GrpcClients, AutoCloseable {

    private val channels = ConcurrentHashMap<String, ManagedChannel>()

    override fun channel(name: String, configure: GrpcClientConfig.() -> Unit): ManagedChannel =
        channels.computeIfAbsent(name) { build(name, GrpcClientConfig().apply(configure)) }

    override fun close() = channels.values.forEach {
        it.shutdown()
        if (!it.awaitTermination(2, TimeUnit.SECONDS)) it.shutdownNow()
    }

    private fun build(peer: String, config: GrpcClientConfig): ManagedChannel {
        val target = requireNotNull(config.target) { "gRPC peer '$peer' needs a target" }
        val breaker = CircuitBreaker(peer, config.circuitFailureThreshold, config.circuitOpenFor, clock)
        telemetry.gauge(Metrics.INTEGRATION_CIRCUIT_OPEN, mapOf("vendor" to peer)) { if (breaker.isOpen) 1.0 else 0.0 }

        val builder = builderFor(target)
        if (!config.tls) builder.usePlaintext()
        if (config.maxAttempts > 1) {
            builder.enableRetry().defaultServiceConfig(retryOnUnavailable(config.maxAttempts))
        }
        // The first interceptor added is the outermost: the deadline is set before the breaker and the log see the call.
        return ChannelWithInterceptors(
            builder.build(),
            listOf(deadlineInterceptor(config), guard(peer, breaker)),
        )
    }

    private fun retryOnUnavailable(maxAttempts: Int): Map<String, Any> = mapOf(
        "methodConfig" to listOf(
            mapOf(
                "name" to listOf(emptyMap<String, Any>()), // every service and method
                "retryPolicy" to mapOf(
                    "maxAttempts" to maxAttempts.toDouble(),
                    "initialBackoff" to "0.2s",
                    "maxBackoff" to "5s",
                    "backoffMultiplier" to 2.0,
                    "retryableStatusCodes" to listOf("UNAVAILABLE"),
                ),
            ),
        ),
    )

    private fun deadlineInterceptor(config: GrpcClientConfig) = object : ClientInterceptor {
        override fun <ReqT, RespT> interceptCall(method: MethodDescriptor<ReqT, RespT>, options: CallOptions, next: Channel): ClientCall<ReqT, RespT> {
            val withDeadline = if (options.deadline == null) options.withDeadlineAfter(config.deadline.toMillis(), TimeUnit.MILLISECONDS) else options
            return next.newCall(method, withDeadline)
        }
    }

    /** Circuit breaker, log line and metric around each call. */
    private fun guard(peer: String, breaker: CircuitBreaker) = object : ClientInterceptor {
        override fun <ReqT, RespT> interceptCall(method: MethodDescriptor<ReqT, RespT>, options: CallOptions, next: Channel): ClientCall<ReqT, RespT> {
            try {
                breaker.beforeCall()
            } catch (e: CircuitOpenException) {
                telemetry.record(Metrics.INTEGRATION_DURATION, 0.0, mapOf("vendor" to peer, "operation" to method.fullMethodName, "outcome" to "circuit_open"))
                return failedCall(Status.UNAVAILABLE.withDescription(e.message))
            }
            val start = System.nanoTime()
            return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, options)) {
                override fun start(listener: Listener<RespT>, headers: Metadata) {
                    super.start(object : ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(listener) {
                        override fun onClose(status: Status, trailers: Metadata) {
                            val failed = status.code in FAILURES
                            if (failed) breaker.onFailure() else breaker.onSuccess()
                            val outcome = if (status.isOk) "ok" else if (failed) "failure" else "client_error"
                            telemetry.record(Metrics.INTEGRATION_DURATION, (System.nanoTime() - start) / 1e9, mapOf("vendor" to peer, "operation" to method.fullMethodName, "outcome" to outcome))
                            val line = arrayOf(kv("kind", "grpc"), kv("vendor", peer), kv("method", method.fullMethodName), kv("status", status.code.name), kv("durationMs", 0L.millisSince(start)))
                            if (failed) log.warn("call", *line) else log.info("call", *line)
                            super.onClose(status, trailers)
                        }
                    }, headers)
                }
            }
        }
    }

    private fun <ReqT, RespT> failedCall(status: Status): ClientCall<ReqT, RespT> = object : ClientCall<ReqT, RespT>() {
        override fun start(listener: Listener<RespT>, headers: Metadata) = listener.onClose(status, Metadata())
        override fun request(numMessages: Int) = Unit
        override fun cancel(message: String?, cause: Throwable?) = Unit
        override fun halfClose() = Unit
        override fun sendMessage(message: ReqT) = Unit
    }

    /** The peer is unwell, as opposed to the caller having asked for something wrong. */
    private val FAILURES = setOf(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.INTERNAL, Status.Code.UNKNOWN)

    /** A channel that sends every call through [interceptors]; shutdown goes to the real one. */
    private class ChannelWithInterceptors(private val delegate: ManagedChannel, interceptors: List<ClientInterceptor>) : ManagedChannel() {
        private val intercepted = ClientInterceptors.intercept(delegate, interceptors)
        override fun shutdown() = delegate.shutdown().let { this }
        override fun isShutdown() = delegate.isShutdown
        override fun isTerminated() = delegate.isTerminated
        override fun shutdownNow() = delegate.shutdownNow().let { this }
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = delegate.awaitTermination(timeout, unit)
        override fun <ReqT, RespT> newCall(method: MethodDescriptor<ReqT, RespT>, options: CallOptions): ClientCall<ReqT, RespT> = intercepted.newCall(method, options)
        override fun authority(): String = delegate.authority()
    }
}
