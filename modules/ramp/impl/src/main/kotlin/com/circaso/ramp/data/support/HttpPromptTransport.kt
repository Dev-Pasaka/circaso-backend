package com.circaso.ramp.data.support

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.ramp.domain.ports.PromptTransport
import com.circaso.shared.Resource
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

/**
 * Reads a failed prompt's cause (the exception the integration kept on the error) for the few failures that prove the
 * request never left: the shared client's circuit was open, or no connection could be made at all. Anything else,
 * a timeout above all, proves nothing, and the answer is false. (A connect *timeout* is left as "maybe sent" too: the
 * client's own exception for it isn't a type this module sees, and the cautious answer costs only a failover.)
 */
class HttpPromptTransport : PromptTransport {
    override fun neverSent(failure: Resource.Error): Boolean = causes(failure.cause).any(::provesUnsent)

    private fun causes(first: Throwable?): Sequence<Throwable> =
        generateSequence(first) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_DEPTH)

    private fun provesUnsent(e: Throwable): Boolean =
        e is CircuitOpenException || e is ConnectException || e is UnknownHostException ||
            e is NoRouteToHostException || e is UnresolvedAddressException

    private companion object {
        const val MAX_DEPTH = 8
    }
}
