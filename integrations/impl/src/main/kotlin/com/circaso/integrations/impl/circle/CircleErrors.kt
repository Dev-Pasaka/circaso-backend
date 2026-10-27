package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory

/**
 * Circle's error codes (developers.circle.com/wallets/error-codes), which never leave this package. The few a user
 * can act on get their own [IntegrationError]; codes that mean success for a caller (an existing user, an initialized
 * user) are handled where they occur.
 */
internal object CircleErrors {
    const val USER_ALREADY_EXISTS = 155101
    const val USER_ALREADY_INITIALIZED = 155106

    /** The user token is unknown, expired or invalid: drop the cached one and ask for another. */
    val USER_TOKEN_REFUSED = setOf(155103, 155104, 155105)

    private const val PIN_LOCKED = 155119
    private const val DESTINATION_INVALID = 155219
    private val INSUFFICIENT_FUNDS = setOf(155201, 155204, 155205, 155258)

    private val log = LoggerFactory.getLogger(CircleErrors::class.java)

    /** [reply] as a failed [Resource]; never called with [CircleReply.Ok]. */
    fun <T> failure(reply: CircleReply<*>, operation: String): Resource<T> =
        when (reply) {
            is CircleReply.Ok -> error("A successful Circle reply is not a failure")
            is CircleReply.Unreachable -> Resource.error(IntegrationError.Unavailable(VENDOR), reply.cause)
            is CircleReply.Failed -> Resource.error(errorFor(reply, operation))
        }

    private fun errorFor(reply: CircleReply.Failed, operation: String): IntegrationError =
        when {
            reply.code == PIN_LOCKED -> IntegrationError.PinLocked()
            reply.code == DESTINATION_INVALID -> IntegrationError.AddressInvalid()
            reply.code in INSUFFICIENT_FUNDS -> IntegrationError.InsufficientFunds()
            reply.code in USER_TOKEN_REFUSED -> unavailable(reply, operation)
            reply.status == 401 || reply.status == 403 -> ourCredentials(reply, operation)
            reply.status == 429 || reply.status >= 500 || reply.status in 200..299 -> unavailable(reply, operation)
            else -> rejected(reply, operation)
        }

    private fun ourCredentials(reply: CircleReply.Failed, operation: String): IntegrationError {
        log.error("circle refused our API key: operation={} status={} code={}", operation, reply.status, reply.code)
        return IntegrationError.Unavailable(VENDOR)
    }

    private fun unavailable(reply: CircleReply.Failed, operation: String): IntegrationError {
        log.warn("circle unavailable: operation={} status={} code={}", operation, reply.status, reply.code)
        return IntegrationError.Unavailable(VENDOR)
    }

    private fun rejected(reply: CircleReply.Failed, operation: String): IntegrationError {
        log.warn("circle refused: operation={} status={} code={}", operation, reply.status, reply.code)
        return IntegrationError.Rejected("The custody provider refused the request.")
    }

    const val VENDOR = "Circle"
}
