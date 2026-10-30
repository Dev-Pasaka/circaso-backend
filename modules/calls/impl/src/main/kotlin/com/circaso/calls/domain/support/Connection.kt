package com.circaso.calls.domain.support

import java.security.MessageDigest

/**
 * A short tag for a participant's current SFU session: it changes every time they (re)join, so the other phones know
 * to pull their tracks again after a reconnect, without ever seeing the session id itself.
 */
object Connection {
    fun tag(sessionId: String?): String? =
        sessionId?.let { id ->
            MessageDigest
                .getInstance("SHA-256")
                .digest(id.toByteArray())
                .take(TAG_BYTES)
                .joinToString("") { "%02x".format(it) }
        }

    private const val TAG_BYTES = 6
}
