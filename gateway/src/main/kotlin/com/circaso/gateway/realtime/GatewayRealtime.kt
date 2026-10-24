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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.circaso.gateway.realtime.GatewayRealtime")

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
        val message = pushFor(envelope) ?: return Resource.success(Unit)
        val target = registrations.pushTarget(userId)
        if (target != null) {
            val platform =
                if (target.platform.equals(
                        "ios",
                        ignoreCase = true,
                    )
                ) {
                    PushPlatform.IOS
                } else {
                    PushPlatform.ANDROID
                }
            val result = push.send(PushTarget(platform, target.token), message)
            if (result is Resource.Error) log.warn("push failed: code={}", result.error.code)
        }
        return Resource.success(Unit)
    }

    /** The frames worth a push when the phone is offline (Realtime and push → Events identity and users send). */
    private fun pushFor(frame: RealtimeEnvelope): PushMessage? {
        val data = frame.data as? JsonObject
        val name = (data?.get("displayName") as? JsonPrimitive)?.contentOrNull
        return when ("${frame.module}.${frame.type}") {
            "system.session.revoked" -> PushMessage("Signed out", "Circaso was opened on another phone.")
            "users.contact.joined" -> PushMessage("Circaso", "${name ?: "Someone you know"} is on Circaso")
            "users.connection.added" -> PushMessage("Circaso", "${name ?: "Someone"} connected with you")
            else -> null
        }
    }
}
