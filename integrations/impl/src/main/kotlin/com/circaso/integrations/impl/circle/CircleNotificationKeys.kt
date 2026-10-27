package com.circaso.integrations.impl.circle

import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The public keys Circle signs notifications with, by key id. A key id always names the same key, so each is fetched
 * once (`GET /v2/notifications/publicKey/{keyId}`, base64 DER SubjectPublicKeyInfo) and kept for good.
 */
internal class CircleNotificationKeys(private val client: CircleClient) {
    private val log = LoggerFactory.getLogger(CircleNotificationKeys::class.java)
    private val keys = ConcurrentHashMap<String, PublicKey>()

    /** The key for [keyId], or null if Circle doesn't know it or can't be asked right now. */
    suspend fun get(keyId: String): PublicKey? {
        keys[keyId]?.let { return it }
        if (!KEY_ID.matches(keyId)) return null

        val reply = client.get<NotificationKeyData>("/v2/notifications/publicKey/$keyId", "get_notification_key")
        if (reply !is CircleReply.Ok) {
            log.warn("circle notification key not fetched: status={}", (reply as? CircleReply.Failed)?.status)
            return null
        }
        val key = parse(reply.data.publicKey) ?: return null
        keys[keyId] = key
        return key
    }

    private fun parse(base64: String): PublicKey? =
        runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))
        }.onFailure { log.warn("circle notification key unreadable: {}", it::class.simpleName) }.getOrNull()

    private companion object {
        /** Key ids are UUIDs; anything else never reaches Circle's URL. */
        val KEY_ID = Regex("^[0-9a-fA-F-]{36}$")
    }
}
