package com.circaso.gateway.realtime

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

            "${WalletFrames.MODULE}.${WalletFrames.TRANSFER_UPDATED}" -> {
                decode(TransferUpdatedFrame.serializer(), frame)?.let(::transferPush)
            }

            "${WalletFrames.MODULE}.${WalletFrames.PAYMENT_RECEIVED}" -> {
                val name = decode(PaymentReceivedFrame.serializer(), frame)?.fromName
                PushMessage("Circaso", if (name != null) "$name sent you money" else "You received money")
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
