package com.circaso.assistant

import com.circaso.assistant.domain.models.VoiceClip
import com.circaso.assistant.presentation.mappers.MAX_CLIP_BYTES
import com.circaso.assistant.presentation.mappers.voiceClipOf
import com.circaso.shared.Resource
import kotlin.test.Test
import kotlin.test.assertEquals

/** A3's headers (decision 2026-10-27): a decimal length is fine, and each problem is named on its own. */
class VoiceClipTest {
    private val audio = ByteArray(10)

    private fun clip(duration: String?, bytes: ByteArray = audio) = voiceClipOf(bytes, duration, "audio/mp4", "en")

    private fun Resource<VoiceClip>.fields(): Map<String, String> = (this as Resource.Error).error.details!!.fields

    @Test
    fun `a decimal length is accepted and rounded up`() {
        assertEquals(6, (clip("5.5") as Resource.Success).data.claimedSeconds)
        assertEquals(5, (clip("5") as Resource.Success).data.claimedSeconds)
        assertEquals(1, (clip("0.2") as Resource.Success).data.claimedSeconds)
        assertEquals(61, (clip("60.01") as Resource.Success).data.claimedSeconds, "so 60.01 s is over the 60 s cap")
    }

    @Test
    fun `a missing length and a clip that is too big are each named`() {
        assertEquals(setOf("X-Duration-Seconds"), clip(null).fields().keys)
        assertEquals(setOf("audio"), clip("5.5", ByteArray(MAX_CLIP_BYTES + 1)).fields().keys)
        val both = clip(null, ByteArray(MAX_CLIP_BYTES + 1)).fields()
        assertEquals(setOf("X-Duration-Seconds", "audio"), both.keys)
        assertEquals("must be under 3 MB", both["audio"])
        assertEquals(false, "3 MB" in both.getValue("X-Duration-Seconds"), "the length's message is about the length")
    }

    @Test
    fun `a length that isn't a positive number is malformed`() {
        listOf("0", "-1", "abc", "NaN", "Infinity", "").forEach {
            assertEquals(setOf("X-Duration-Seconds"), clip(it).fields().keys, "'$it'")
        }
    }
}
