package com.circaso.assistant.presentation.services

import com.circaso.assistant.domain.models.Usage
import com.circaso.assistant.domain.models.VoiceClip
import com.circaso.assistant.domain.support.AshaVoices
import com.circaso.assistant.domain.usecases.GetUsage
import com.circaso.assistant.domain.usecases.GetVoiceSample
import com.circaso.assistant.domain.usecases.ListVoices
import com.circaso.assistant.domain.usecases.SynthesizeSpeech
import com.circaso.assistant.domain.usecases.TranscribeVoice
import com.circaso.integrations.api.SpokenAudio
import com.circaso.integrations.api.Transcript
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** Voice in and out, Asha's voices, and the day's allowance. */
class VoiceService(
    private val transcribeVoice: TranscribeVoice,
    private val synthesizeSpeech: SynthesizeSpeech,
    private val getUsage: GetUsage,
    private val listVoices: ListVoices,
    private val getVoiceSample: GetVoiceSample,
) {
    suspend fun transcribe(userId: UserId, clip: VoiceClip): Resource<Transcript> = transcribeVoice(userId, clip)

    suspend fun speak(
        userId: UserId,
        text: String,
        language: String?,
        voice: String?,
    ): Resource<SpokenAudio> = synthesizeSpeech(userId, text, language, voice)

    suspend fun voices(): Resource<AshaVoices> = listVoices()

    suspend fun voiceSample(voiceId: String): Resource<SpokenAudio> = getVoiceSample(voiceId)

    suspend fun getUsage(userId: UserId): Resource<Usage> = getUsage.invoke(userId)
}
