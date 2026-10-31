package com.circaso.assistant.domain.models

/** A hint for the voice picker, so the app can show a balanced list; not a claim about anyone. */
enum class VoiceGender { FEMALE, MALE }

/**
 * One of the voices Asha can speak in (decision 2026-10-31). [id] is the voice provider's own name for it, opaque here:
 * the assistant only checks it against its list and passes it on. [name] is what the picker shows and [character] one
 * word for how it sounds ("Warm", "Calm").
 */
data class AshaVoice(
    val id: String,
    val name: String,
    val character: String,
    val gender: VoiceGender,
)
