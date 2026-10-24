package com.circaso.infrastructure.impl.grpc

import com.circaso.infrastructure.api.GrpcClientConfig
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.ServerCalls
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PeerGrpcClientsTest {
    private val strings =
        object : MethodDescriptor.Marshaller<String> {
            override fun stream(value: String): InputStream = ByteArrayInputStream(value.toByteArray())

            override fun parse(stream: InputStream): String = stream.readBytes().decodeToString()
        }
    private val say =
        MethodDescriptor
            .newBuilder(strings, strings)
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.Echo/Say")
            .build()

    private val serverName = "peer-${UUID.randomUUID()}"
    private val calls = AtomicInteger()
    private var server: Server? = null
    private val clients = PeerGrpcClients(builderFor = { InProcessChannelBuilder.forName(it) })

    @AfterTest
    fun stop() {
        clients.close()
        server?.shutdownNow()
    }

    /** Serves `test.Echo/Say`, answering each call from [respond] (the call number counts from 1). */
    private fun serve(respond: (call: Int, request: String) -> Result<String>) {
        val definition =
            ServerServiceDefinition
                .builder("test.Echo")
                .addMethod(
                    say,
                    ServerCalls.asyncUnaryCall<String, String> { request, observer ->
                        respond(calls.incrementAndGet(), request).fold(
                            {
                                observer.onNext(it)
                                observer.onCompleted()
                            },
                            {
                                observer.onError(
                                    if (it is StatusRuntimeException) it else Status.INTERNAL.asRuntimeException(),
                                )
                            },
                        )
                    },
                ).build()
        server =
            InProcessServerBuilder
                .forName(serverName)
                .addService(definition)
                .build()
                .start()
    }

    private fun peer(configure: GrpcClientConfig.() -> Unit = {}): ManagedChannel =
        clients.channel("echo") {
            target = serverName
            tls = false
            configure()
        }

    private fun call(channel: ManagedChannel, text: String = "hi") =
        ClientCalls.blockingUnaryCall(channel, say, CallOptions.DEFAULT, text)

    private fun unavailable() = Result.failure<String>(Status.UNAVAILABLE.asRuntimeException())

    @Test
    fun `a call goes through the named channel`() {
        serve { _, request -> Result.success("echo $request") }

        assertEquals("echo hi", call(peer()))
        assertEquals(1, calls.get())
    }

    @Test
    fun `a name always gives the same channel`() {
        serve { _, _ -> Result.success("ok") }

        assertEquals(peer(), clients.channel("echo"))
    }

    @Test
    fun `a call with no deadline gets the configured one`() {
        serve { _, _ ->
            Thread.sleep(1500)
            Result.success("late")
        }

        val failure = assertFailsWith<StatusRuntimeException> { call(peer { deadline = Duration.ofMillis(200) }) }

        assertEquals(Status.Code.DEADLINE_EXCEEDED, failure.status.code)
    }

    @Test
    fun `UNAVAILABLE is retried until it succeeds`() {
        serve { n, _ -> if (n < 3) unavailable() else Result.success("third time") }

        assertEquals("third time", call(peer()))
        assertEquals(3, calls.get())
    }

    @Test
    fun `attempts are capped at the configured number`() {
        serve { _, _ -> unavailable() }

        assertFailsWith<StatusRuntimeException> {
            call(
                peer {
                    maxAttempts = 2
                    circuitFailureThreshold = 100
                },
            )
        }

        assertEquals(2, calls.get())
    }

    @Test
    fun `other failures are not retried, because the peer did process the call`() {
        serve { _, _ -> Result.failure(Status.INTERNAL.asRuntimeException()) }

        val failure = assertFailsWith<StatusRuntimeException> { call(peer()) }

        assertEquals(Status.Code.INTERNAL, failure.status.code)
        assertEquals(1, calls.get())
    }

    @Test
    fun `repeated failures open the circuit and the next call fails without reaching the peer`() {
        serve { _, _ -> Result.failure(Status.INTERNAL.asRuntimeException()) }
        val channel = peer { circuitFailureThreshold = 3 }
        repeat(3) { assertFailsWith<StatusRuntimeException> { call(channel) } }
        assertEquals(3, calls.get())

        val failure = assertFailsWith<StatusRuntimeException> { call(channel) }

        assertEquals(Status.Code.UNAVAILABLE, failure.status.code)
        assertNotNull(failure.status.description)
        assertEquals(3, calls.get(), "an open circuit must not call the peer")
    }

    @Test
    fun `a caller's mistake does not count against the peer`() {
        serve { _, _ -> Result.failure(Status.INVALID_ARGUMENT.asRuntimeException()) }
        val channel = peer { circuitFailureThreshold = 2 }

        repeat(4) { assertFailsWith<StatusRuntimeException> { call(channel) } }

        assertEquals(4, calls.get(), "INVALID_ARGUMENT must never open the circuit")
    }

    @Test
    fun `a peer with no target is refused`() {
        assertFailsWith<IllegalArgumentException> { clients.channel("nowhere") }
    }
}
