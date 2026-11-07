package com.circaso.integrations.impl.anthropic

import ai.koog.http.client.KoogHttpClientException
import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.integrations.api.IntegrationError

/**
 * What a failed Claude call means, in our language. Koog wraps every failure in its own exceptions; the HTTP status,
 * when there was one, is on the [KoogHttpClientException] in the cause chain. A refusal of what we sent (400, 404,
 * 413, 422) is [IntegrationError.Rejected]; everything else (429, 5xx, 529 overloaded, 401/403 a key problem, a
 * dropped connection, a timeout, an open circuit, an `error` event mid-answer) is [IntegrationError.Unavailable].
 * Either way the caller may fall back to another model.
 *
 * [reason] is what the log line says: the status or the exception's class, never its message, which can carry the
 * vendor's error body and with it part of the conversation.
 */
internal class AnthropicFailure(val error: IntegrationError, val reason: String, val status: Int?) {
    val isKeyProblem: Boolean get() = status == 401 || status == 403

    companion object {
        const val VENDOR = "Anthropic"

        fun of(failure: Throwable): AnthropicFailure {
            val chain = generateSequence(failure) { it.cause }.take(MAX_CAUSES).toList()
            // an `error` event inside a 200 answer carries the 200: that is not a refusal of the request
            val status = chain.firstNotNullOfOrNull { it as? KoogHttpClientException }?.statusCode?.takeIf { it !in OK }
            if (status != null) {
                val error =
                    if (status in REJECTED) {
                        IntegrationError.Rejected("The model provider refused the request.")
                    } else {
                        IntegrationError.Unavailable(VENDOR)
                    }
                return AnthropicFailure(error, "status_$status", status)
            }
            val reason =
                when {
                    chain.any { it is CircuitOpenException } -> "circuit_open"
                    chain.any { "Anthropic error:" in it.message.orEmpty() } -> "error_event"
                    else -> chain.last()::class.simpleName ?: "unknown"
                }
            return AnthropicFailure(IntegrationError.Unavailable(VENDOR), reason, null)
        }

        private val REJECTED = setOf(400, 404, 413, 422)
        private val OK = 200..299
        private const val MAX_CAUSES = 8
    }
}
