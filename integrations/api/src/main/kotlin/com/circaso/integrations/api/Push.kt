package com.circaso.integrations.api

import com.circaso.shared.Resource

interface PushGateway {
    /** Used by the gateway only: when a user has no open socket, or their phone said Circaso is in the background. */
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
    /**
     * How long the vendor may hold the push for an offline phone; null is the vendor's default. An incoming call is
     * worthless after its ring, so it is given about as long as the ring lasts.
     */
    val ttlSeconds: Long? = null,
    /**
     * A sound the app ships for this kind of push (`money_received.wav`, the coins for money received), played on iOS
     * from the app's own sounds; null is the phone's usual sound. Android picks the sound itself from a data-only
     * push, by its [PushPayload.kind] and the person's setting.
     */
    val sound: String? = null,
    /**
     * Shows nothing on either phone, ever (added 2026-11-08): a background job for the app, such as answering a moments
     * key request. Android gets a data message without its words; iOS a background push (`content-available`, no
     * alert, no sound), which iOS may hold or drop, so whatever it starts must also happen on the next open. Wins
     * over [dataOnly].
     */
    val silent: Boolean = false,
)

/**
 * What a push carries for the app to route on when it wakes or is opened: ids only, never text from a chat. Each
 * vendor encodes it in its own shape (FCM's `data` is a map of strings); a field left null is not sent.
 *
 * Calls add [callId], [kind] (`call.ringing`, `call.missed`, `call.ended`: what woke the app), and for a ring the
 * [callerName] and [callKind] (`VOICE` or `VIDEO`), so the phone can show the incoming-call screen before it is
 * back online. A profile name is not chat text.
 */
data class PushPayload(
    val chatId: String? = null,
    val messageId: String? = null,
    val callId: String? = null,
    val kind: String? = null,
    val callerName: String? = null,
    val callKind: String? = null,
    /** The space a `space.*` push is about (Spaces phase 3): a tap opens it. */
    val spaceId: String? = null,
    /**
     * Money (added 2026-11-06): the Circaso transfer and the wallet history row a `payment.received` or
     * `transfer.updated` is about, and the group wallet it moved in. Ids only: the phone looks up the amount and words
     * it in the person's own currency, and a tap opens the chat at the payment's card (or its details).
     */
    val transferId: String? = null,
    val transactionId: String? = null,
    val walletId: String? = null,
    /** A group payout request (`chama.request.*`), added 2026-11-06. */
    val requestId: String? = null,
    /**
     * The person a push is about (a group contribution's member, a contact who joined, who reacted to my moment, who
     * called), added 2026-11-06: the phone shows their photo and the name it knows them by.
     */
    val userId: String? = null,
    /** The support ticket a `support.ticket` push is about (added 2026-11-08): a tap opens it. */
    val ticketId: String? = null,
    /**
     * A new chat message (added 2026-11-13): who sent it, when, and the message's ciphertext for this phone as the inbox
     * holds it (end-to-end encrypted: the server can't read it). iOS's Notification Service Extension opens it on the
     * phone, so the notification shows the sender's name and photo and what was said without waking the app. The
     * ciphertext is left out when it would push the payload past the vendors' 4 KB; the phone then words it by name.
     */
    val senderId: String? = null,
    val sentAt: String? = null,
    val ciphertext: String? = null,
    /**
     * A reaction on my moment (`moment.loved`, added 2026-11-14): which moment, and the emoji. [userId] is the person
     * who reacted, so the phone names them as I saved them and shows their photo, as for a chat message.
     */
    val momentId: String? = null,
    val reaction: String? = null,
)

data class PushReceipt(val messageId: String, val provider: String)

/**
 * Apple PushKit VoIP pushes for incoming calls (decision 2026-11-08): the only push that wakes a closed iPhone app to
 * ring through CallKit. FCM can't deliver one, so it goes straight to APNs. The gateway sends one per `call.ringing`
 * to an offline iPhone that registered a PushKit token, and falls back to [PushGateway] when this is [enabled] false
 * or the send isn't [VoipPushResult.SENT].
 */
interface VoipPushGateway {
    /** False when no APNs key is configured: every ring then goes through [PushGateway] as before. */
    val enabled: Boolean

    /** Rings the iPhone whose PushKit token is [voipToken]. */
    suspend fun ring(voipToken: String, ring: VoipRing): VoipPushResult

    /**
     * Tells the iPhone a call it was rung for stopped ringing it (2026-11-09): the caller hung up, the call ended or
     * the ring ran out. The app ends that CallKit call, or reports and ends one at once if it has none (PushKit
     * requires a reported call for every VoIP push).
     */
    suspend fun ended(voipToken: String, ended: VoipRingEnded): VoipPushResult
}

/** Which ring stopped and why: `CANCELLED`, `MISSED`, `DECLINED` or `COMPLETED`. */
data class VoipRingEnded(val callId: String, val chatId: String, val reason: String)

/**
 * What the phone needs to report the call to CallKit before it is back online; the same ids and fields the FCM
 * `call.ringing` push carries. [callKind] is `VOICE` or `VIDEO`.
 */
data class VoipRing(
    val callId: String,
    val chatId: String,
    val callKind: String,
    val callerName: String,
)

enum class VoipPushResult {
    /** APNs accepted it. */
    SENT,

    /** APNs no longer accepts the token (`BadDeviceToken`, `Unregistered`): forget it and push through FCM. */
    TOKEN_INVALID,

    /** Anything else (APNs down, our key refused, a timeout): push through FCM this time. */
    FAILED,
}
