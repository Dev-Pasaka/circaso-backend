package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.models.AshaVoice
import com.circaso.assistant.domain.models.VoiceGender
import com.circaso.assistant.domain.ports.VoiceFallbacks

/**
 * The voices a user can choose for Asha, and the one used when they haven't (decision 2026-10-31). Set in the
 * assistant's config (`ASSISTANT_VOICES`, `ASSISTANT_DEFAULT_VOICE`); [DEFAULT] holds otherwise.
 *
 * A voice asked for that isn't on the list, or none at all, is [default]: never an error, so an app from before voices
 * existed, or one holding a voice since dropped from the list, keeps working.
 */
class AshaVoices(val all: List<AshaVoice>, defaultId: String) {
    init {
        require(all.isNotEmpty()) { "Asha needs at least one voice" }
        require(all.map { it.id }.toSet().size == all.size) { "Asha's voice ids must be unique" }
    }

    val default: AshaVoice =
        requireNotNull(all.firstOrNull { it.id == defaultId }) { "Asha's default voice '$defaultId' isn't on the list" }

    /** The voice with this id (case and spaces aside), or null when there is none. */
    fun find(id: String?): AshaVoice? {
        val wanted = id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return all.firstOrNull { it.id.lowercase() == wanted }
    }

    /** The voice to speak in for [requested]: that voice, or [default] (told to [fallbacks]) when it isn't one. */
    fun pick(requested: String?, fallbacks: VoiceFallbacks): AshaVoice =
        find(requested) ?: default.also { fallbacks.fellBack(requested, it) }

    companion object {
        /**
         * Six Deepgram Aura 2 voices (the speaker names on Workers AI's `@cf/deepgram/aura-2-en` page), chosen for a
         * warm, trustworthy finance assistant whose plain, clear English carries well for East African listeners: three
         * female, three male. Luna was Asha's only voice before, so it stays the default.
         */
        val DEFAULT =
            AshaVoices(
                listOf(
                    AshaVoice("luna", "Luna", "Friendly", VoiceGender.FEMALE),
                    AshaVoice("harmonia", "Harmonia", "Calm", VoiceGender.FEMALE),
                    AshaVoice("thalia", "Thalia", "Bright", VoiceGender.FEMALE),
                    AshaVoice("arcas", "Arcas", "Warm", VoiceGender.MALE),
                    AshaVoice("orion", "Orion", "Gentle", VoiceGender.MALE),
                    AshaVoice("zeus", "Zeus", "Steady", VoiceGender.MALE),
                ),
                defaultId = "luna",
            )

        /** What every voice says when the app previews it. */
        const val SAMPLE_TEXT = "Hi, I'm Asha. I can help you understand your money and payments."
    }
}
