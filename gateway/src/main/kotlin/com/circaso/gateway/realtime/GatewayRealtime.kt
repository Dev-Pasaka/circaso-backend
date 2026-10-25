package com.circaso.gateway.realtime

import com.circaso.identity.api.PushRegistrations
import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.api.PushMessage
import com.circaso.integrations.api.PushPlatform
import com.circaso.integrations.api.PushTarget
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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
        val offline = hub.deliverMany(userIds, envelope)
        val pushes = offline.mapNotNull { userId -> pushFor(envelope, userId)?.let { userId to it } }
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
     * Messaging → Socket frames). Messaging's pushes carry ids, never text from the chat.
     */
    private fun pushFor(frame: RealtimeEnvelope, recipient: UserId): PushMessage? {
        val data = frame.data as? JsonObject
        val name = data.text("displayName")
        return when ("${frame.module}.${frame.type}") {
            "system.session.revoked" -> PushMessage("Signed out", "Circaso was opened on another phone.")
            "users.contact.joined" -> PushMessage("Circaso", "${name ?: "Someone you know"} is on Circaso")
            "users.connection.added" -> PushMessage("Circaso", "${name ?: "Someone"} connected with you")
            "messaging.message.new" -> newMessagePush(data)
            "messaging.member.added" -> addedToGroupPush(data, recipient)
            else -> null
        }
    }

    /** Only when the sender marked it `pushable`: sender keys and control messages wake nobody. */
    private fun newMessagePush(data: JsonObject?): PushMessage? {
        val pushable = (data?.get("pushable") as? JsonPrimitive)?.booleanOrNull ?: true
        if (!pushable) return null
        return PushMessage("Circaso", NEW_MESSAGE, ids(data, "chatId", "messageId"))
    }

    /** Only the person added is woken (`userId` in the frame); the other members just see it next time. */
    private fun addedToGroupPush(data: JsonObject?, recipient: UserId): PushMessage? {
        if (data.text("userId") != recipient.toString()) return null
        // TODO(decision: wording of the member.added push; the spec says "Yes for the person added" and nothing more)
        return PushMessage("Circaso", "You were added to a group", ids(data, "chatId"))
    }

    private fun ids(data: JsonObject?, vararg names: String): Map<String, String> =
        names.mapNotNull { name -> data.text(name)?.let { name to it } }.toMap()

    private fun JsonObject?.text(name: String): String? = (this?.get(name) as? JsonPrimitive)?.contentOrNull
}
