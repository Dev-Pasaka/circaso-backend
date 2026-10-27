package com.circaso.integrations.api

import com.circaso.shared.Resource

interface PushGateway {
    /** Used by the gateway only, when a user has no open socket. */
    suspend fun send(target: PushTarget, message: PushMessage): Resource<PushReceipt>
}

enum class PushPlatform { ANDROID, IOS }

/** [token] is the FCM or APNs token the phone registered. */
data class PushTarget(val platform: PushPlatform, val token: String)

/**
 * [title] and [body] are what the phone shows. A [dataOnly] push wakes the app instead (decision 2026-10-25): Android
 * gets a data message and nothing is shown until the app shows it; iOS, which can't wake an app silently and reliably,
 * gets an alert with `mutable-content`, so its Notification Service Extension can decrypt and replace it, and the
 * title and body are the fallback shown if it can't. Chat pushes are data-only, so no chat text ever goes to a vendor.
 */
data class PushMessage(
    val title: String,
    val body: String,
    val data: PushPayload = PushPayload(),
    val dataOnly: Boolean = false,
)

/**
 * What a push carries for the app to route on when it wakes or is opened: ids only, never text from a chat. Each
 * vendor encodes it in its own shape (FCM's `data` is a map of strings); a field left null is not sent.
 */
data class PushPayload(val chatId: String? = null, val messageId: String? = null)

data class PushReceipt(val messageId: String, val provider: String)
