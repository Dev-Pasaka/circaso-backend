package com.circaso.integrations.api

import com.circaso.shared.Resource
import java.time.Instant

/**
 * A vendor's callback, understood here and received by the gateway at `/v1/hooks/{name}`. The gateway owns the
 * endpoint and the guards (signature, timestamp window, duplicate ids); the vendor only verifies its own
 * signature and translates a verified body into a Circaso event. Business modules react to the event.
 */
interface WebhookVendor {
    /** The `{vendor}` in the path. */
    val name: String

    /**
     * True if the gateway must wait for [translate] and answer non-200 when it fails, so the vendor delivers again
     * (Circle: a lost transaction update is a wrong balance). False answers `200` at once and translates afterwards.
     */
    val answersAfterTranslation: Boolean get() = false

    /**
     * True if [body] carries a valid signature in [headers] (header names lowercase). Suspends because a vendor may
     * have to fetch the public key named in the headers the first time it sees it (Circle).
     */
    suspend fun verifySignature(headers: Map<String, String>, body: ByteArray): Boolean

    /** When the vendor says this was sent, for the replay window; null if it doesn't say. */
    fun sentAt(headers: Map<String, String>, body: ByteArray): Instant?

    /** The vendor's id for this delivery, so a repeat is processed once; null if it has none. */
    fun deliveryId(headers: Map<String, String>, body: ByteArray): String?

    /** Turns the verified body into a Circaso event (`SmsDelivered`). */
    suspend fun translate(body: ByteArray): Resource<Unit>
}
