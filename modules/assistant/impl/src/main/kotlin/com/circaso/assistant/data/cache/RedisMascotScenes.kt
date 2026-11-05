package com.circaso.assistant.data.cache

import com.circaso.assistant.domain.models.MascotScene
import com.circaso.assistant.domain.models.MascotSceneState
import com.circaso.assistant.domain.ports.MascotScenes
import com.circaso.infrastructure.api.RedisScope
import com.circaso.shared.UserId
import java.time.Duration
import java.util.UUID

/**
 * The mascot's scene per conversation, one hash per user, `ast:mascot:{userId}`, a field per conversation holding
 * `scene|pending|turnId|sass` (decision 2026-11-04). Kept 30 days from the last turn; nothing in it is personal (scene
 * names only), and a lost one just starts the mascot from the default scene.
 */
class RedisMascotScenes(private val redis: RedisScope) : MascotScenes {
    override suspend fun find(userId: UserId, conversationId: UUID): MascotSceneState? =
        redis.hashGetAll(key(userId))[conversationId.toString()]?.let(::decode)

    override suspend fun save(
        userId: UserId,
        conversationId: UUID,
        state: MascotSceneState,
    ) {
        redis.hashSet(key(userId), mapOf(conversationId.toString() to encode(state)), TTL)
    }

    override suspend fun forget(userId: UserId) {
        redis.delete(key(userId))
    }

    private fun key(userId: UserId) = "mascot:$userId"

    private companion object {
        val TTL: Duration = Duration.ofDays(30)

        fun encode(state: MascotSceneState): String =
            listOf(
                state.scene?.wire.orEmpty(),
                state.pending?.wire.orEmpty(),
                state.turnId?.toString().orEmpty(),
                if (state.hadSass) "1" else "",
            ).joinToString("|")

        fun decode(raw: String): MascotSceneState {
            val parts = raw.split('|')
            return MascotSceneState(
                scene = MascotScene.of(parts.getOrNull(0)),
                pending = MascotScene.of(parts.getOrNull(1)),
                turnId =
                    parts
                        .getOrNull(
                            2,
                        )?.takeIf { it.isNotEmpty() }
                        ?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                hadSass = parts.getOrNull(3) == "1",
            )
        }
    }
}
