package com.circaso.integrations.api

import com.circaso.shared.Resource

interface PushGateway {
    /** Used by the gateway only, when a user has no open socket. */
    suspend fun send(target: PushTarget, message: PushMessage): Resource<PushReceipt>
}

enum class PushPlatform { ANDROID, IOS }

/** [token] is the FCM or APNs token the phone registered. */
data class PushTarget(val platform: PushPlatform, val token: String)

data class PushMessage(val title: String, val body: String, val data: Map<String, String> = emptyMap())

data class PushReceipt(val messageId: String, val provider: String)
