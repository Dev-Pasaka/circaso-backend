package com.circaso.integrations.impl.placeholder

import com.circaso.integrations.api.CarrierPhoneVerification
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.SpeechToText
import com.circaso.integrations.api.SpokenAudio
import com.circaso.integrations.api.TextToSpeech
import com.circaso.integrations.api.Transcript
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource

// The real vendors, until their credentials and SDKs arrive. Each returns `integrations.not_implemented`, so
// selecting one by config fails loudly instead of pretending to deliver. Replace a class with the vendor's own
// package (`carrier/firebase`, `push`) and delete it here.

// TODO(vendor: Firebase project and service account for phone number verification)
class FirebaseCarrierPhoneVerification : CarrierPhoneVerification {
    override suspend fun verify(carrierToken: String): Resource<PhoneNumber> =
        Resource.error(IntegrationError.NotImplemented("CarrierPhoneVerification", "firebase"))
}

// TODO(vendor: Cloudflare account id, AI Gateway and a tool-calling model id for Workers AI, through Koog's OpenAI client)
class WorkersAiLanguageModel : LanguageModel {
    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> =
        Resource.error(IntegrationError.NotImplemented("LanguageModel", "workers-ai"))
}

// TODO(vendor: Whisper on Workers AI; test Swahili quality before choosing)
class WorkersAiSpeechToText : SpeechToText {
    override suspend fun transcribe(
        audio: ByteArray,
        contentType: String,
        language: String?,
    ): Resource<Transcript> = Resource.error(IntegrationError.NotImplemented("SpeechToText", "workers-ai"))
}

// TODO(vendor: Workers AI text-to-speech voice; test Swahili quality before choosing)
class WorkersAiTextToSpeech : TextToSpeech {
    override suspend fun speak(text: String, language: String?): Resource<SpokenAudio> =
        Resource.error(IntegrationError.NotImplemented("TextToSpeech", "workers-ai"))
}
