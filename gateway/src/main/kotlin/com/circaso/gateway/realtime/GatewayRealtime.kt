package com.circaso.gateway.realtime

import com.circaso.calls.api.CallFrames
import com.circaso.calls.api.CallRingingFrame
import com.circaso.calls.api.CallSnapshot
import com.circaso.chama.api.ChamaFrames
import com.circaso.chama.api.ContributionAddedFrame
import com.circaso.chama.api.RequestUpdatedFrame
import com.circaso.identity.api.PushRegistrations
import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.api.PushMessage
import com.circaso.integrations.api.PushPayload
import com.circaso.integrations.api.PushPlatform
import com.circaso.integrations.api.PushTarget
import com.circaso.messaging.api.MemberAddedFrame
import com.circaso.messaging.api.MessageNewFrame
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.decodeData
import com.circaso.users.api.ConnectionAddedFrame
import com.circaso.users.api.ContactJoinedFrame
import com.circaso.users.api.UsersFrames
import com.circaso.wallet.api.PaymentReceivedFrame
import com.circaso.wallet.api.SetupRequestedFrame
import com.circaso.wallet.api.TransferUpdatedFrame
import com.circaso.wallet.api.WalletFrames
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.circaso.gateway.realtime.GatewayRealtime")

/** How many offline users get their push at the same time when one frame goes to many. */
private const val PUSH_BATCH = 100

/** Messaging's pushes wake the app, which fetches and decrypts; this text shows only if it can't. */
private const val NEW_MESSAGE = "New message"

/** An incoming call is worth nothing after its 45-second ring, so the vendor may not hold it for longer. */
private const val RING_TTL_SECONDS = 45L

/**
 * What modules call to tell a user something now: to the socket when the user has one open, otherwise to push
 * when the frame is marked push-worthy. A frame that reaches neither is recovered by the module's replay when
 * the phone reconnects.
 */
class GatewayRealtime(
    private val hub: SocketHub,
    private val push: PushGateway,
    private val registrations: PushRegistrations,
) : Realtime {
    override suspend fun deliver(userId: UserId, envelope: RealtimeEnvelope): Resource<Unit> {
        if (hub.deliver(userId, envelope)) return Resource.success(Unit)
        val message = pushFor(envelope, userId) ?: return Resource.success(Unit)
        pushTo(userId, message)
        return Resource.success(Unit)
    }

    override suspend fun deliverMany(userIds: Collection<UserId>, envelope: RealtimeEnvelope): Resource<Unit> {
        hub.deliverMany(userIds, envelope)
        // Only users this frame would push to are looked up, so receipts and typing cost one publish and no reads.
        val candidates = userIds.distinct().mapNotNull { userId -> pushFor(envelope, userId)?.let { userId to it } }
        if (candidates.isEmpty()) return Resource.success(Unit)
        val offline = hub.withoutSocket(candidates.map { it.first }).toSet()
        val pushes = candidates.filter { (userId, _) -> userId in offline }
        pushes.chunked(PUSH_BATCH).forEach { batch ->
            coroutineScope {
                batch.map { (userId, message) -> async { pushTo(userId, message) } }.awaitAll()
            }
        }
        return Resource.success(Unit)
    }

    private suspend fun pushTo(userId: UserId, message: PushMessage) {
        val target = registrations.pushTarget(userId) ?: return
        val platform = if (target.platform.equals("ios", ignoreCase = true)) PushPlatform.IOS else PushPlatform.ANDROID
        val result = push.send(PushTarget(platform, target.token), message)
        if (result is Resource.Error) log.warn("push failed: code={}", result.error.code)
    }

    /**
     * The frames worth a push when the phone is offline (Realtime and push → Events identity and users send;
     * Messaging → Socket frames; Wallet → Realtime frames). Messaging's pushes carry ids, never text from the chat;
     * the wallet's never carry an amount.
     */
    private fun pushFor(frame: RealtimeEnvelope, recipient: UserId): PushMessage? =
        when ("${frame.module}.${frame.type}") {
            "system.session.revoked" -> {
                PushMessage("Signed out", "Circaso was opened on another phone.")
            }

            "${UsersFrames.MODULE}.${UsersFrames.CONTACT_JOINED}" -> {
                val name = decode(ContactJoinedFrame.serializer(), frame)?.displayName
                PushMessage("Circaso", "${name ?: "Someone you know"} is on Circaso")
            }

            "${UsersFrames.MODULE}.${UsersFrames.CONNECTION_ADDED}" -> {
                val name = decode(ConnectionAddedFrame.serializer(), frame)?.displayName
                PushMessage("Circaso", "${name ?: "Someone"} connected with you")
            }

            "messaging.${MessageNewFrame.TYPE}" -> {
                decode(MessageNewFrame.serializer(), frame)?.let(::newMessagePush)
            }

            "messaging.${MemberAddedFrame.TYPE}" -> {
                decode(MemberAddedFrame.serializer(), frame)?.let { addedToGroupPush(it, recipient) }
            }

            "${ChamaFrames.MODULE}.${ChamaFrames.CONTRIBUTION_ADDED}" -> {
                // to the other members; the contributor already knows
                decode(ContributionAddedFrame.serializer(), frame)?.takeIf { it.userId != recipient }?.let {
                    PushMessage(it.goalName, "${it.displayName} added to ${it.goalName}")
                }
            }

            "${ChamaFrames.MODULE}.${ChamaFrames.REQUEST_UPDATED}" -> {
                decode(RequestUpdatedFrame.serializer(), frame)?.let { requestPush(it, recipient) }
            }

            "${CallFrames.MODULE}.${CallFrames.RINGING}" -> {
                decode(CallRingingFrame.serializer(), frame)?.let(::ringingPush)
            }

            "${CallFrames.MODULE}.${CallFrames.ENDED}" -> {
                decode(CallSnapshot.serializer(), frame)?.let { endedCallPush(it, recipient) }
            }

            "${WalletFrames.MODULE}.${WalletFrames.TRANSFER_UPDATED}" -> {
                decode(TransferUpdatedFrame.serializer(), frame)?.let(::transferPush)
            }

            "${WalletFrames.MODULE}.${WalletFrames.PAYMENT_RECEIVED}" -> {
                val received = decode(PaymentReceivedFrame.serializer(), frame)
                val name = received?.fromName
                // A group wallet's money is pushed to its signer (decision 2026-11-03). The group's name is sealed, so
                // the server never has it: the text says "a group wallet".
                val text =
                    when {
                        received?.walletId != null && name != null -> "$name paid into a group wallet"
                        received?.walletId != null -> "A group wallet received money"
                        name != null -> "$name sent you money"
                        else -> "You received money"
                    }
                // The coins sound (decision 2026-10-31): iOS plays it from the app; Android gets the push as data and
                // plays it itself, or the usual sound when the person turned it off. No amount in a push, ever.
                PushMessage(
                    "Circaso",
                    text,
                    PushPayload(kind = PAYMENT_RECEIVED_KIND),
                    dataOnly = true,
                    sound = MONEY_SOUND,
                )
            }

            "${WalletFrames.MODULE}.${WalletFrames.SETUP_REQUESTED}" -> {
                val name = decode(SetupRequestedFrame.serializer(), frame)?.fromName ?: "Someone"
                PushMessage("Circaso", "$name wants to send you money. Set up your wallet")
            }

            else -> {
                null
            }
        }

    /**
     * Only when the sender marked it `pushable`; a frame without the field wakes nobody (Messaging → Frame data), so
     * sender keys and control messages never do. Data-only (decision 2026-10-25): the app fetches the message from
     * the inbox, decrypts it and words the notification itself; the text here shows only if it can't.
     */
    private fun newMessagePush(frame: MessageNewFrame): PushMessage? {
        if (!frame.pushable) return null
        val ids = PushPayload(chatId = frame.chatId.toString(), messageId = frame.messageId.toString())
        return PushMessage("Circaso", NEW_MESSAGE, ids, dataOnly = true)
    }

    /**
     * Only the people added are woken (`userIds` in the frame, one frame per action, decision 2026-10-26); the other
     * members just see it next time.
     */
    private fun addedToGroupPush(frame: MemberAddedFrame, recipient: UserId): PushMessage? {
        if (recipient !in frame.userIds) return null
        // Never the group name, which is sealed: the push is data-only and the phone words it from the sealed name.
        val ids = PushPayload(chatId = frame.chatId.toString())
        return PushMessage("Circaso", "You were added to a group", ids, dataOnly = true)
    }

    /**
     * The sender hears only about the end of a payment (Wallet → Realtime frames); a push never shows an amount on
     * the lock screen.
     */
    // The admins still to decide hear of a pending request; the requester hears the outcome. Never an amount.
    // A group without a target has no goal name (decision 2026-11-03): the push says "the group wallet". The wallet's
    // signer hears when a payout is approved, since they are the one who pays it.
    private fun requestPush(frame: RequestUpdatedFrame, recipient: UserId): PushMessage? {
        val title = frame.goalName.ifBlank { "Circaso" }
        val from = frame.goalName.ifBlank { "the group wallet" }
        return when (frame.state) {
            "PENDING" -> {
                if (recipient in frame.waitingOn) {
                    PushMessage(title, "${frame.requesterName} needs your approval to pay from $from")
                } else {
                    null
                }
            }

            "APPROVED" -> {
                when (recipient) {
                    frame.requestedBy -> PushMessage(title, "Your request was approved")
                    frame.signerId -> PushMessage(title, "A payout from $from was approved. It's ready for you to pay")
                    else -> null
                }
            }

            "DECLINED" -> {
                if (recipient == frame.requestedBy) PushMessage(title, "Your request was declined") else null
            }

            "PAID" -> {
                if (recipient == frame.requestedBy) PushMessage(title, "Your request was paid") else null
            }

            else -> {
                null
            }
        }
    }

    /**
     * Data-only and high priority with a short life, so the app wakes and shows its incoming-call screen (Android's
     * full-screen intent, CallKit on iOS); the text shows only if it can't.
     */
    private fun ringingPush(frame: CallRingingFrame): PushMessage {
        val ids =
            PushPayload(
                chatId = frame.chatId.toString(),
                callId = frame.callId.toString(),
                kind = "call.ringing",
                callerName = frame.callerName,
                callKind = frame.kind,
            )
        val what = if (frame.kind == "VIDEO") "video call" else "call"
        return PushMessage(
            "Incoming $what",
            "${frame.callerName} is calling",
            ids,
            dataOnly = true,
            ttlSeconds = RING_TTL_SECONDS,
        )
    }

    /**
     * A call that ended while still ringing someone: nobody answered (`MISSED`, an alert "Missed call from …"), or
     * the caller hung up first (`CANCELLED`, data-only, so the phone stops ringing and words the missed call itself).
     * Nobody else is pushed when a call ends; whoever was in it had a socket.
     */
    private fun endedCallPush(frame: CallSnapshot, recipient: UserId): PushMessage? {
        if (recipient == frame.startedBy) return null
        val me = frame.participants.firstOrNull { it.userId == recipient } ?: return null
        if (me.state != "MISSED") return null
        val caller = frame.participants.firstOrNull { it.userId == frame.startedBy }?.displayName ?: "Someone"
        val text = "Missed call from $caller"
        val ids = PushPayload(chatId = frame.chatId.toString(), callId = frame.callId.toString())
        return when (frame.endReason) {
            "MISSED" -> PushMessage("Missed call", text, ids.copy(kind = "call.missed"))
            "CANCELLED" -> PushMessage("Missed call", text, ids.copy(kind = "call.ended"), dataOnly = true)
            else -> null
        }
    }

    private fun transferPush(frame: TransferUpdatedFrame): PushMessage? =
        when (frame.state) {
            "CONFIRMED" -> PushMessage("Circaso", "Payment sent")
            "FAILED" -> PushMessage("Circaso", "Payment failed")
            else -> null
        }

    /** A frame's `data` as its class, from the module's `api`; null (no push) when it doesn't decode. */
    private fun <T> decode(serializer: KSerializer<T>, frame: RealtimeEnvelope): T? =
        runCatching { frame.decodeData(serializer) }.getOrNull()
}

/** What wakes the app for money received, and the sound it ships for it. */
private const val PAYMENT_RECEIVED_KIND = "payment.received"
private const val MONEY_SOUND = "money_received.wav"
