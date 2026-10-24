package com.circaso.integrations.impl

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.Secrets
import com.circaso.integrations.api.CarrierPhoneVerification
import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.EmailTemplate
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.SpeechToText
import com.circaso.integrations.api.TextToSpeech
import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.api.SmsGateway
import com.circaso.integrations.api.SocialSignIn
import com.circaso.integrations.impl.dev.FakeCarrierPhoneVerification
import com.circaso.integrations.impl.dev.FakeEmail
import com.circaso.integrations.impl.dev.FakePush
import com.circaso.integrations.impl.dev.FakeSms
import com.circaso.integrations.impl.dev.FakeSocialSignIn
import com.circaso.integrations.impl.dev.FakeLanguageModel
import com.circaso.integrations.impl.dev.FakeSpeechToText
import com.circaso.integrations.impl.dev.FakeTextToSpeech
import com.circaso.integrations.impl.dev.LogLanguageModel
import com.circaso.integrations.impl.dev.LogEmail
import com.circaso.integrations.impl.dev.LogPush
import com.circaso.integrations.impl.dev.LogSms
import com.circaso.integrations.api.NoVendorVerification
import com.circaso.integrations.api.PhoneVerificationGateway
import com.circaso.integrations.impl.africastalking.AfricasTalkingSms
import com.circaso.integrations.impl.africastalking.africasTalkingBaseUrl
import com.circaso.integrations.impl.twilio.TwilioVerify
import com.circaso.integrations.impl.placeholder.FcmApnsPush
import com.circaso.integrations.impl.placeholder.WorkersAiLanguageModel
import com.circaso.integrations.impl.placeholder.WorkersAiSpeechToText
import com.circaso.integrations.impl.placeholder.WorkersAiTextToSpeech
import com.circaso.integrations.impl.placeholder.FirebaseCarrierPhoneVerification
import com.circaso.integrations.impl.social.AppleRevocation
import com.circaso.integrations.impl.social.GoogleAppleSignIn
import com.circaso.integrations.impl.social.ProviderSpec
import com.circaso.integrations.impl.resend.ResendEmail
import org.koin.dsl.module

/**
 * Binds each contract to the provider named in config: `integrations.sms.provider = africastalking | log | fake`
 * and likewise `email` (`resend`), `social` (`google-apple`), `carrier` (`firebase`), `push` (`fcm-apns`) and, for the
 * assistant, `llm`, `stt` and `tts` (`workers-ai`).
 * Every one defaults to `log`, which needs nothing; the vendor names are placeholders until their credentials
 * arrive. An unknown name stops startup.
 */
fun integrationsModule(config: Config, secrets: Secrets) = module {
    val integrations = config.section("integrations")

    fun <T> pick(contract: String, default: String = "log", providers: Map<String, () -> T>): T {
        val name = integrations.string("$contract.provider", default)
        val create = providers[name]
            ?: throw IllegalStateException("integrations.$contract.provider must be one of ${providers.keys.joinToString()} (was '$name')")
        return create()
    }

    single<SmsGateway> {
        pick("sms", providers = mapOf("log" to ::LogSms, "fake" to ::FakeSms, "africastalking" to { africasTalkingSms(config, secrets, get()) }))
    }
    // Who makes and checks the one-time code: `none` (identity does, and SmsGateway delivers) or `twilio` (Twilio Verify does both).
    single<PhoneVerificationGateway> {
        pick("verify", default = "none", providers = mapOf("none" to { NoVendorVerification }, "twilio" to { twilioVerify(config, secrets, get()) }))
    }
    single<EmailGateway> {
        pick("email", providers = mapOf("log" to ::LogEmail, "fake" to ::FakeEmail, "resend" to { resendEmail(integrations.section("email"), secrets, get()) }))
    }
    // `log` has nothing to print for a token check, so it shares the fake that accepts `dev:` tokens.
    single<SocialSignIn> {
        pick("social", providers = mapOf("log" to ::FakeSocialSignIn, "fake" to ::FakeSocialSignIn, "google-apple" to { googleAppleSignIn(integrations.section("social"), secrets, get()) }))
    }
    single<CarrierPhoneVerification> {
        pick("carrier", providers = mapOf("log" to ::FakeCarrierPhoneVerification, "fake" to ::FakeCarrierPhoneVerification, "firebase" to ::FirebaseCarrierPhoneVerification))
    }
    single<PushGateway> {
        pick("push", providers = mapOf("log" to ::LogPush, "fake" to ::FakePush, "fcm-apns" to ::FcmApnsPush))
    }
    single<LanguageModel> {
        pick<LanguageModel>("llm", providers = mapOf("log" to ::LogLanguageModel, "fake" to { FakeLanguageModel() }, "workers-ai" to ::WorkersAiLanguageModel))
    }
    single<SpeechToText> {
        pick("stt", providers = mapOf("log" to ::FakeSpeechToText, "fake" to ::FakeSpeechToText, "workers-ai" to ::WorkersAiSpeechToText))
    }
    single<TextToSpeech> {
        pick("tts", providers = mapOf("log" to ::FakeTextToSpeech, "fake" to ::FakeTextToSpeech, "workers-ai" to ::WorkersAiTextToSpeech))
    }
}

/**
 * Resend needs `RESEND_API_KEY`, `integrations.email.from` (`INTEGRATIONS_EMAIL_FROM`, a sender on a verified domain) and
 * one template id per [EmailTemplate] (`INTEGRATIONS_EMAIL_TEMPLATES_<NAME>`). Anything missing stops startup, naming it.
 */
private fun resendEmail(email: Config, secrets: Secrets, clients: HttpClients): ResendEmail = ResendEmail(
    apiKey = secrets.require("resend.api-key"),
    from = email.requireString("from"),
    templates = EmailTemplate.entries.associateWith { email.requireString("templates.${it.name.lowercase()}") },
    http = clients.named("resend") { baseUrl = email.string("base-url", "https://api.resend.com") },
)

/**
 * Google and Apple ID tokens are verified here, so each needs the client ids its tokens are issued for:
 * `INTEGRATIONS_SOCIAL_GOOGLE_CLIENT_IDS` (the web/server client id, plus the Android and iOS ones if they differ) and
 * `INTEGRATIONS_SOCIAL_APPLE_CLIENT_IDS` (the iOS bundle id, and the Services id if Android signs in through the web), comma separated.
 * At least one provider must be configured. Revoking Apple grants on account deletion also needs `..._APPLE_TEAM_ID`,
 * `..._APPLE_KEY_ID`, optionally `..._APPLE_CLIENT_ID` (default: the first Apple client id) and the secret `APPLE_PRIVATE_KEY` (the `.p8` text).
 */
private fun googleAppleSignIn(social: Config, secrets: Secrets, clients: HttpClients): GoogleAppleSignIn {
    fun ids(key: String) = social.string(key, "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val google = ids("google.client-ids")
    val apple = ids("apple.client-ids")
    check(google.isNotEmpty() || apple.isNotEmpty()) {
        "integrations.social.provider is google-apple, so set integrations.social.google.client-ids and/or integrations.social.apple.client-ids"
    }
    val privateKey = secrets.get("apple.private-key")
    val revocation = if (privateKey != null) {
        AppleRevocation(
            teamId = social.requireString("apple.team-id"),
            keyId = social.requireString("apple.key-id"),
            clientId = social.string("apple.client-id") ?: apple.first(),
            privateKeyPem = privateKey,
        )
    } else {
        null
    }
    return GoogleAppleSignIn(
        google = ProviderSpec(GoogleAppleSignIn.GOOGLE_JWKS, GoogleAppleSignIn.GOOGLE_ISSUERS, google),
        apple = ProviderSpec(GoogleAppleSignIn.APPLE_JWKS, GoogleAppleSignIn.APPLE_ISSUERS, apple),
        clients = clients,
        revocation = revocation,
    )
}

/**
 * Africa's Talking needs `AFRICASTALKING_USERNAME` (`sandbox` for their test environment), the secret `AFRICASTALKING_API_KEY`, and
 * optionally `AFRICASTALKING_SENDER_ID` (an approved sender name; without one it sends from their shared short code).
 */
private fun africasTalkingSms(config: Config, secrets: Secrets, clients: HttpClients): AfricasTalkingSms {
    val username = config.requireString("africastalking.username")
    return AfricasTalkingSms(
        username = username,
        apiKey = secrets.require("africastalking.api-key"),
        http = clients.named("africastalking") { baseUrl = africasTalkingBaseUrl(username) },
        senderId = config.string("africastalking.sender-id"),
    )
}

/**
 * Twilio Verify needs the secrets `TWILIO_ACCOUNT_SID` and `TWILIO_AUTH_TOKEN` and the setting `TWILIO_VERIFY_SERVICE_SID` (a `VA...` id, made in
 * the Twilio console under Verify > Services). `TWILIO_VERIFY_LOCALE` (for example `en` or `sw`) sets the language of the text.
 */
private fun twilioVerify(config: Config, secrets: Secrets, clients: HttpClients) = TwilioVerify(
    accountSid = secrets.require("twilio.account-sid"),
    authToken = secrets.require("twilio.auth-token"),
    serviceSid = config.requireString("twilio.verify-service-sid"),
    http = clients.named("twilio") { baseUrl = "https://verify.twilio.com" },
    locale = config.string("twilio.verify-locale"),
)
