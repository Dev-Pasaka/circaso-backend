package com.circaso.assistant.data.cache

import com.circaso.assistant.domain.ports.VoiceSamples
import com.circaso.infrastructure.api.RedisScope
import com.circaso.integrations.api.SpokenAudio
import com.circaso.shared.CircasoJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/**
 * Voice previews, `ast:voice-sample:{voiceId}:{text hash}`, kept a day. The text's hash is in the key so a changed
 * sample line is made afresh at once. A clip is a few seconds of MP3 (tens of KB), so six of them are cheap to hold.
 * Redis trouble is never the user's: a read that fails is a miss and a write that fails is skipped.
 */
class RedisVoiceSamples(private val redis: RedisScope) : VoiceSamples {
    private val log = LoggerFactory.getLogger(RedisVoiceSamples::class.java)

    override suspend fun find(voiceId: String, text: String): SpokenAudio? {
        val raw = quietly { redis.get(key(voiceId, text)) } ?: return null
        val sample = runCatching { CircasoJson.decodeFromString(SampleJson.serializer(), raw) }.getOrNull()
        val bytes = sample?.let { runCatching { Base64.getDecoder().decode(it.audio) }.getOrNull() }
        return bytes?.takeIf { it.isNotEmpty() }?.let { SpokenAudio(it, sample.contentType) }
    }

    override suspend fun save(
        voiceId: String,
        text: String,
        audio: SpokenAudio,
    ) {
        val json = SampleJson(audio.contentType, Base64.getEncoder().encodeToString(audio.bytes))
        quietly { redis.set(key(voiceId, text), CircasoJson.encodeToString(SampleJson.serializer(), json), TTL) }
    }

    private suspend fun <T> quietly(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("voice sample cache unavailable: error={}", e::class.simpleName)
            null
        }

    private fun key(voiceId: String, text: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return "voice-sample:$voiceId:" + hash.take(HASH_BYTES).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val TTL: Duration = Duration.ofDays(1)
        const val HASH_BYTES = 8
    }
}

@Serializable
private class SampleJson(val contentType: String, val audio: String)
