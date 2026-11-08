package com.circaso.integrations.impl

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.Secrets
import com.circaso.integrations.api.AiBilling
import com.circaso.integrations.api.AudioEncoding
import com.circaso.integrations.api.CallMedia
import com.circaso.integrations.api.CarrierPhoneVerification
import com.circaso.integrations.api.CryptoMarkets
import com.circaso.integrations.api.CryptoPrices
import com.circaso.integrations.api.CustodyNotifications
import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.EmailTemplate
import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.LiveSpeech
import com.circaso.integrations.api.LiveTranscription
import com.circaso.integrations.api.NoVendorVerification
import com.circaso.integrations.api.PhoneVerificationGateway
import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.api.SmsGateway
import com.circaso.integrations.api.SocialSignIn
import com.circaso.integrations.api.SpeechToText
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.api.StaticMaps
import com.circaso.integrations.api.TextToSpeech
import com.circaso.integrations.api.VisionModel
import com.circaso.integrations.api.VoipPushGateway
import com.circaso.integrations.api.WebSearch
import com.circaso.integrations.api.WebhookVendor
import com.circaso.integrations.impl.africastalking.AfricasTalkingSms
import com.circaso.integrations.impl.africastalking.africasTalkingBaseUrl
import com.circaso.integrations.impl.apns.ApnsProviderToken
import com.circaso.integrations.impl.apns.ApnsVoipPush
import com.circaso.integrations.impl.apns.JdkApnsTransport
import com.circaso.integrations.impl.anthropic.AnthropicAdminBilling
import com.circaso.integrations.impl.brave.BraveWebSearch
import com.circaso.integrations.impl.circle.CircleClient
import com.circaso.integrations.impl.circle.CircleCustody
import com.circaso.integrations.impl.circle.CircleSettings
import com.circaso.integrations.impl.circle.CircleWebhook
import com.circaso.integrations.impl.cloudflare.CloudflareRealtime
import com.circaso.integrations.impl.cloudflare.TurnKey
import com.circaso.integrations.impl.coingecko.CoinGeckoMarkets
import com.circaso.integrations.impl.coingecko.CoinGeckoPrices
import com.circaso.integrations.impl.dev.FakeCallMedia
import com.circaso.integrations.impl.dev.FakeCarrierPhoneVerification
import com.circaso.integrations.impl.dev.FakeCryptoMarkets
import com.circaso.integrations.impl.dev.FakeCryptoPrices
import com.circaso.integrations.impl.dev.FakeCustody
import com.circaso.integrations.impl.dev.FakeEmail
import com.circaso.integrations.impl.dev.FakeExchangeRates
import com.circaso.integrations.impl.dev.FakeLiveSpeech
import com.circaso.integrations.impl.dev.FakeLiveTranscription
import com.circaso.integrations.impl.dev.FakePush
import com.circaso.integrations.impl.dev.FakeSms
import com.circaso.integrations.impl.dev.FakeSocialSignIn
import com.circaso.integrations.impl.dev.FakeSpeechToText
import com.circaso.integrations.impl.dev.FakeStaticMaps
import com.circaso.integrations.impl.dev.FakeTextToSpeech
import com.circaso.integrations.impl.dev.FakeVisionModel
import com.circaso.integrations.impl.dev.FakeWebSearch
import com.circaso.integrations.impl.dev.LogCallMedia
import com.circaso.integrations.impl.dev.LogCryptoPrices
import com.circaso.integrations.impl.dev.LogCustody
import com.circaso.integrations.impl.dev.LogEmail
import com.circaso.integrations.impl.dev.LogExchangeRates
import com.circaso.integrations.impl.dev.LogPush
import com.circaso.integrations.impl.dev.LogSms
import com.circaso.integrations.impl.dev.LogStaticMaps
import com.circaso.integrations.impl.dev.LogWebSearch
import com.circaso.integrations.impl.dev.NoAiBilling
import com.circaso.integrations.impl.dev.NoCryptoMarkets
import com.circaso.integrations.impl.dev.NoCryptoPrices
import com.circaso.integrations.impl.dev.NoVoipPush
import com.circaso.integrations.impl.exchangerate.OpenErApiRates
import com.circaso.integrations.impl.fcm.FcmPush
import com.circaso.integrations.impl.fcm.ServiceAccount
import com.circaso.integrations.impl.googlemaps.GoogleStaticMaps
import com.circaso.integrations.impl.placeholder.FirebaseCarrierPhoneVerification
import com.circaso.integrations.impl.resend.ResendEmail
import com.circaso.integrations.impl.social.AppleRevocation
import com.circaso.integrations.impl.social.GoogleAppleSignIn
import com.circaso.integrations.impl.social.ProviderSpec
import com.circaso.integrations.impl.tavily.TavilyWebSearch
import com.circaso.integrations.impl.twilio.TwilioVerify
import com.circaso.integrations.impl.workersai.WorkersAiGateway
import com.circaso.integrations.impl.workersai.WorkersAiLiveSocket
import com.circaso.integrations.impl.workersai.WorkersAiLiveSpeech
import com.circaso.integrations.impl.workersai.WorkersAiLiveTranscription
import com.circaso.integrations.impl.workersai.WorkersAiSpeechToText
import com.circaso.integrations.impl.workersai.WorkersAiTextToSpeech
import com.circaso.integrations.impl.workersai.WorkersAiVisionModel
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Binds each contract to the provider named in config: `integrations.sms.provider = africastalking | log | fake`
 * and likewise `email` (`resend`), `social` (`google-apple`), `carrier` (`firebase`), `push` (`fcm`, for Android and iOS) and, for the
 * assistant, `llm` (`workers-ai` or `anthropic`, with `llm.voice-provider` for live voice), `vision`, `stt`, `tts`,
 * `live-stt` and `live-tts` (`workers-ai`), and for the wallet `custody`
 * (`circle`, which also serves Circle's webhook at `/v1/hooks/circle`), `rates` (`open-er-api`) and `prices`
 * (`coingecko`; unset, `coingecko` when its key is set, else `off`), for calls
 * `calls` (`cloudflare`, the Realtime SFU), for location previews `maps` (`google`), and Asha's web `search`
 * (`brave` or `tavily`; unset, `brave` when its key is set, else `fake`). Every other one defaults to `log`, which needs nothing; the vendor names are placeholders until their credentials
 * arrive. An unknown name stops startup.
 */
fun integrationsModule(config: Config, secrets: Secrets) =
    module {
        val integrations = config.section("integrations")
        val ai = integrations.section("ai")

        fun <T> pick(
            contract: String,
            default: String = "log",
            providers: Map<String, () -> T>,
        ): T {
            val name = integrations.string("$contract.provider", default)
            val create =
                providers[name]
                    ?: throw IllegalStateException(
                        "integrations.$contract.provider must be one of ${providers.keys.joinToString()} (was " +
                            "'$name')",
                    )
            return create()
        }

        fun custodyChains() = CustodyChainsConfig.parse(integrations.section("custody")).supported

        single<SmsGateway> {
            pick(
                "sms",
                providers =
                    mapOf(
                        "log" to ::LogSms,
                        "fake" to ::FakeSms,
                        "africastalking" to { africasTalkingSms(config, secrets, get()) },
                    ),
            )
        }
        // Who makes and checks the one-time code: `none` (identity does, and SmsGateway delivers) or `twilio` (Twilio Verify does both).
        single<PhoneVerificationGateway> {
            pick(
                "verify",
                default = "none",
                providers =
                    mapOf(
                        "none" to { NoVendorVerification },
                        "twilio" to { twilioVerify(config, secrets, get()) },
                    ),
            )
        }
        single<EmailGateway> {
            pick(
                "email",
                providers =
                    mapOf(
                        "log" to ::LogEmail,
                        "fake" to ::FakeEmail,
                        "resend" to { resendEmail(integrations.section("email"), secrets, get()) },
                    ),
            )
        }
        // `log` has nothing to print for a token check, so it shares the fake that accepts `dev:` tokens.
        single<SocialSignIn> {
            pick(
                "social",
                providers =
                    mapOf(
                        "log" to ::FakeSocialSignIn,
                        "fake" to ::FakeSocialSignIn,
                        "google-apple" to { googleAppleSignIn(integrations.section("social"), secrets, get()) },
                    ),
            )
        }
        single<CarrierPhoneVerification> {
            pick(
                "carrier",
                providers =
                    mapOf(
                        "log" to ::FakeCarrierPhoneVerification,
                        "fake" to ::FakeCarrierPhoneVerification,
                        "firebase" to ::FirebaseCarrierPhoneVerification,
                    ),
            )
        }
        single<PushGateway> {
            pick(
                "push",
                providers =
                    mapOf(
                        "log" to ::LogPush,
                        "fake" to ::FakePush,
                        "fcm" to { fcmPush(integrations.section("push"), secrets, get()) },
                    ),
            )
        }
        // Incoming-call rings to iPhones straight through APNs (decision 2026-11-08); off, and every ring through
        // `PushGateway`, until the key is configured. Created at start so its one line says which.
        single<VoipPushGateway>(createdAtStart = true) { apnsVoipPush(integrations.section("apns"), secrets) }
        single<LanguageModel> { ashaLanguageModel(config, secrets, get()) }
        single<VisionModel> {
            pick(
                "vision",
                providers =
                    mapOf(
                        "log" to ::FakeVisionModel,
                        "fake" to ::FakeVisionModel,
                        "workers-ai" to {
                            WorkersAiVisionModel(
                                workersAi(config, secrets, get()),
                                ai.string("model.vision", WORKERS_AI_VISION_MODEL),
                            )
                        },
                    ),
            )
        }
        single<SpeechToText> {
            pick(
                "stt",
                providers =
                    mapOf(
                        "log" to ::FakeSpeechToText,
                        "fake" to ::FakeSpeechToText,
                        "workers-ai" to {
                            WorkersAiSpeechToText(
                                workersAi(config, secrets, get()),
                                ai.string("model.stt", WORKERS_AI_STT_MODEL),
                            )
                        },
                    ),
            )
        }
        single<TextToSpeech> {
            pick(
                "tts",
                providers =
                    mapOf(
                        "log" to ::FakeTextToSpeech,
                        "fake" to ::FakeTextToSpeech,
                        "workers-ai" to {
                            WorkersAiTextToSpeech(
                                workersAi(config, secrets, get()),
                                ai.string("model.tts", WORKERS_AI_TTS_MODEL),
                            )
                        },
                    ),
            )
        }
        single<LiveTranscription> {
            pick(
                "live-stt",
                providers =
                    mapOf(
                        "log" to ::FakeLiveTranscription,
                        "fake" to ::FakeLiveTranscription,
                        "workers-ai" to {
                            WorkersAiLiveTranscription(
                                workersAiLive(config, secrets, get()),
                                ai.string("model.live-stt", WORKERS_AI_LIVE_STT_MODEL),
                                endpointingMillis =
                                    ai.int(
                                        "live.endpointing-ms",
                                        WorkersAiLiveTranscription.DEFAULT_ENDPOINTING_MILLIS,
                                    ),
                                utteranceEndMillis =
                                    ai.int(
                                        "live.utterance-end-ms",
                                        WorkersAiLiveTranscription.DEFAULT_UTTERANCE_END_MILLIS,
                                    ),
                            )
                        },
                    ),
            )
        }
        single<StablecoinCustody> {
            pick(
                "custody",
                providers =
                    mapOf(
                        "log" to { LogCustody(FakeCustody(supported = custodyChains(), autoCompleteSetup = true)) },
                        "fake" to { FakeCustody(supported = custodyChains()) },
                        "circle" to { CircleCustody(get<CircleClient>(), get<CircleSettings>(), Clock.systemUTC()) },
                    ),
            )
        }
        if (integrations.string("custody.provider", "log") == "circle") {
            circle(integrations.section("custody"), secrets)
        }
        // The wallet's fiat balance (decision 2026-10-28). The open endpoint needs no key; its table is kept in Redis
        // under `fx:` so every node shares one fetch a day.
        single<ExchangeRates> {
            pick(
                "rates",
                providers =
                    mapOf(
                        "log" to { LogExchangeRates() },
                        "fake" to { FakeExchangeRates() },
                        "open-er-api" to {
                            OpenErApiRates(
                                http = get<HttpClients>().named("exchangerate") { baseUrl = OpenErApiRates.BASE_URL },
                                redis = get<RedisScopes>().scope("fx"),
                            )
                        },
                    ),
            )
        }
        // Coin prices (decision 2026-11-06): `coingecko` needs the secret `COINGECKO_API_KEY` (a Demo key) and is the
        // default when it is set; without it the default is `off` (only dollar stablecoins priced), so a missing key
        // never stops startup and never shows a made-up price. `log` has fixed rough prices for local runs. The table
        // is kept in Redis under `px:` for `INTEGRATIONS_PRICES_FRESH_SECONDS` (default 60).
        single<CryptoPrices> {
            pick(
                "prices",
                default = if (secrets.get(COINGECKO_KEY) != null) "coingecko" else "off",
                providers =
                    mapOf(
                        "off" to { NoCryptoPrices() },
                        "log" to { LogCryptoPrices() },
                        "fake" to { FakeCryptoPrices() },
                        "coingecko" to {
                            CoinGeckoPrices(
                                http = coinGeckoClient(get()),
                                redis = get<RedisScopes>().scope("px"),
                                key = secrets.require(COINGECKO_KEY),
                                freshFor = pricesFreshFor(integrations),
                            )
                        },
                    ),
            )
        }
        // Asha's crypto answers (decision 2026-11-06): the same feed, key and client as the prices; `off` without a key
        // (she then says she couldn't get the data), `log` is `off` too, since made-up market data is never shown.
        single<CryptoMarkets> {
            pick(
                "prices",
                default = if (secrets.get(COINGECKO_KEY) != null) "coingecko" else "off",
                providers =
                    mapOf(
                        "off" to { NoCryptoMarkets() },
                        "log" to { NoCryptoMarkets() },
                        "fake" to { FakeCryptoMarkets() },
                        "coingecko" to {
                            CoinGeckoMarkets(
                                http = coinGeckoClient(get()),
                                redis = get<RedisScopes>().scope("px"),
                                key = secrets.require(COINGECKO_KEY),
                                freshFor = pricesFreshFor(integrations),
                            )
                        },
                    ),
            )
        }
        // Location previews (decision 2026-10-30): `google` needs the secret `GOOGLE_MAPS_API_KEY` (a key allowed only
        // the Maps Static API) and, recommended, `GOOGLE_MAPS_SIGNING_SECRET` (Cloud console > Maps > URL signing
        // secret). `log` answers unavailable and the app keeps its drawn tile.
        single<StaticMaps> {
            pick(
                "maps",
                providers =
                    mapOf(
                        "log" to { LogStaticMaps() },
                        "fake" to { FakeStaticMaps() },
                        "google" to {
                            GoogleStaticMaps(
                                http = get<HttpClients>().named("google-maps"),
                                key = secrets.require("google-maps.api-key"),
                                signingSecret = secrets.get("google-maps.signing-secret"),
                            )
                        },
                    ),
            )
        }
        // Asha's web search (decisions 2026-10-31 and 2026-11-05): `brave` needs the secret `BRAVE_SEARCH_API_KEY`,
        // `tavily` the secret `TAVILY_API_KEY`. Unset, the provider is `brave` when its key is there and otherwise
        // `fake` (two made-up results), so nothing breaks without a key; `log` answers none.
        single<WebSearch> {
            pick(
                "search",
                default = if (secrets.get(BRAVE_KEY) != null) "brave" else "fake",
                providers =
                    mapOf(
                        "log" to { LogWebSearch() },
                        "fake" to { FakeWebSearch() },
                        "brave" to {
                            BraveWebSearch(
                                http = get<HttpClients>().named("brave"),
                                key = secrets.require(BRAVE_KEY),
                            )
                        },
                        "tavily" to {
                            TavilyWebSearch(
                                http = get<HttpClients>().named("tavily"),
                                key = secrets.require("tavily.api-key"),
                            )
                        },
                    ),
            )
        }
        // Anthropic's own billing, set beside Asha's cost estimate in the staff report (decision 2026-11-08). Off unless
        // the secret `OPS_ANTHROPIC_ADMIN_KEY` (an Admin API key, `sk-ant-admin…`) is set; `OPS_ANTHROPIC_WORKSPACE_ID`
        // counts one workspace only (`default` for the default one). Answers are kept in Redis under `aib:`.
        single<AiBilling> {
            val adminKey = secrets.get(ANTHROPIC_ADMIN_KEY)
            if (adminKey == null) {
                NoAiBilling
            } else {
                val baseUrl = config.string("ops.anthropic.admin-base-url", AnthropicAdminBilling.BASE_URL)
                AnthropicAdminBilling(
                    http =
                        get<HttpClients>().named("anthropic-admin") {
                            this.baseUrl = baseUrl
                            connectTimeout = AnthropicAdminBilling.TIMEOUT
                            requestTimeout = AnthropicAdminBilling.TIMEOUT
                        },
                    key = adminKey,
                    cache = get<RedisScopes>().scope("aib"),
                    workspaceId = config.string("ops.anthropic.workspace-id")?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }
        // Calls' SFU (decision 2026-10-29): `log` and `fake` make up sessions and SDP, so calls work without media.
        single<CallMedia> {
            pick(
                "calls",
                providers =
                    mapOf(
                        "log" to ::LogCallMedia,
                        "fake" to ::FakeCallMedia,
                        "cloudflare" to { cloudflareRealtime(config, secrets, get()) },
                    ),
            )
        }
        single<LiveSpeech> {
            pick(
                "live-tts",
                providers =
                    mapOf(
                        "log" to { FakeLiveSpeech() },
                        "fake" to { FakeLiveSpeech() },
                        "workers-ai" to {
                            WorkersAiLiveSpeech(
                                workersAiLive(config, secrets, get()),
                                ai.string("model.live-tts", WORKERS_AI_TTS_MODEL),
                                encoding = liveSpeechEncoding(ai.string("live.tts-encoding", "mulaw")),
                                sampleRate =
                                    ai.int(
                                        "live.tts-sample-rate",
                                        WorkersAiLiveSpeech.DEFAULT_SAMPLE_RATE,
                                    ),
                            )
                        },
                    ),
            )
        }
    }

/**
 * Circle needs the secret `CIRCLE_API_KEY` (testnet and mainnet keys are separate) and `INTEGRATIONS_CUSTODY_APP_ID`
 * (handed to the phone's SDK); the chains and their assets are [CustodyChainsConfig]'s. The custody calls and the
 * webhook share one client. The webhook's listener, [CustodyNotifications], is bound by the wallet module and looked
 * up on each delivery, so integrations never depend on the wallet.
 */
private fun Module.circle(custody: Config, secrets: Secrets) {
    single {
        val chains = CustodyChainsConfig.parse(custody)
        CircleSettings(
            appId = custody.requireString("app-id"),
            supported = chains.supported,
            accountTypes = chains.accountTypes,
        )
    }
    single {
        CircleClient(
            http = get<HttpClients>().named("circle") { baseUrl = "https://api.circle.com" },
            apiKey = secrets.require("circle.api-key"),
        )
    }
    single { CircleWebhook(get(), get(), notifications = { getOrNull<CustodyNotifications>() }) } bind
        WebhookVendor::class
}

/**
 * Resend needs `RESEND_API_KEY`, `integrations.email.from` (`INTEGRATIONS_EMAIL_FROM`, a sender on a verified domain) and
 * one template id per [EmailTemplate] (`INTEGRATIONS_EMAIL_TEMPLATES_<NAME>`). Anything missing stops startup, naming it.
 */
private fun resendEmail(
    email: Config,
    secrets: Secrets,
    clients: HttpClients,
): ResendEmail =
    ResendEmail(
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
private fun googleAppleSignIn(
    social: Config,
    secrets: Secrets,
    clients: HttpClients,
): GoogleAppleSignIn {
    fun ids(key: String) =
        social
            .string(key, "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    val google = ids("google.client-ids")
    val apple = ids("apple.client-ids")
    check(google.isNotEmpty() || apple.isNotEmpty()) {
        "integrations.social.provider is google-apple, so set integrations.social.google.client-ids and/or " +
            "integrations.social.apple.client-ids"
    }
    val privateKey = secrets.get("apple.private-key")
    val revocation =
        if (privateKey != null) {
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
private fun africasTalkingSms(
    config: Config,
    secrets: Secrets,
    clients: HttpClients,
): AfricasTalkingSms {
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
private fun twilioVerify(
    config: Config,
    secrets: Secrets,
    clients: HttpClients,
) = TwilioVerify(
    accountSid = secrets.require("twilio.account-sid"),
    authToken = secrets.require("twilio.auth-token"),
    serviceSid = config.requireString("twilio.verify-service-sid"),
    http = clients.named("twilio") { baseUrl = "https://verify.twilio.com" },
    locale = config.string("twilio.verify-locale"),
)

/**
 * FCM needs the secret `FCM_SERVICE_ACCOUNT`: the service-account JSON from Firebase console > Project settings > Service accounts >
 * Generate new private key, as one line of JSON or as base64 of the file. iOS pushes also need the APNs key uploaded under Project
 * settings > Cloud Messaging, once. `INTEGRATIONS_PUSH_BASE_URL` overrides the FCM address (tests, a local fake).
 */
private fun fcmPush(
    push: Config,
    secrets: Secrets,
    clients: HttpClients,
) = FcmPush(
    account = ServiceAccount.parse(secrets.require("fcm.service-account")),
    http = clients.named("fcm") { baseUrl = push.string("base-url", "https://fcm.googleapis.com") },
    oauth = clients.named("google-oauth"),
)

/**
 * APNs VoIP pushes for incoming calls on iPhones (decision 2026-11-08), on when the secret `INTEGRATIONS_APNS_KEY` is
 * set: base64 of the `.p8` file (Apple Developer > Keys, with Apple Push Notifications service), or its PEM text. It
 * then needs `INTEGRATIONS_APNS_KEY_ID` (the key's 10-character id) and `INTEGRATIONS_APNS_TEAM_ID`; optional are
 * `INTEGRATIONS_APNS_TOPIC`, the app's bundle id (default `com.circaso.app`; pushes go to `<topic>.voip`), and
 * `INTEGRATIONS_APNS_SANDBOX` (`true` for development-signed builds, default `false`). The key never prints.
 */
private fun apnsVoipPush(apns: Config, secrets: Secrets): VoipPushGateway {
    val log = LoggerFactory.getLogger("com.circaso.integrations.impl.apns")
    val key = secrets.get("integrations.apns.key")?.takeIf { it.isNotBlank() }
    if (key == null) {
        log.info("APNs VoIP pushes: off (INTEGRATIONS_APNS_KEY not set); iPhone rings go through FCM")
        return NoVoipPush()
    }
    val sandbox = apns.boolean("sandbox", false)
    val topic = apns.string("topic", "com.circaso.app")
    val keyId = apns.requireString("key-id")
    val tokens = ApnsProviderToken(keyId, apns.requireString("team-id"), ApnsProviderToken.parseKey(key))
    val host = if (sandbox) "https://api.sandbox.push.apple.com" else "https://api.push.apple.com"
    log.info("APNs VoIP pushes: on (host={}, topic={}.voip, keyId={})", host, topic, keyId)
    return ApnsVoipPush(tokens, JdkApnsTransport(host), topic)
}

/**
 * Cloudflare Realtime needs `CLOUDFLARE_REALTIME_APP_ID` and the secret `CLOUDFLARE_REALTIME_APP_TOKEN` (dashboard >
 * Realtime > SFU > Create application: its App ID and App Secret). TURN is optional: `CLOUDFLARE_TURN_KEY_ID` and the
 * secret `CLOUDFLARE_TURN_KEY_TOKEN` (Realtime > TURN Server > Create: its Turn Token ID and API token); without them
 * phones get Cloudflare's STUN only.
 */
private fun cloudflareRealtime(
    config: Config,
    secrets: Secrets,
    clients: HttpClients,
): CloudflareRealtime {
    val turnKeyId = config.string("cloudflare.turn-key-id")?.takeIf { it.isNotBlank() }
    val turn = turnKeyId?.let { TurnKey(it, secrets.require("cloudflare.turn-key-token")) }
    return CloudflareRealtime(
        appId = config.requireString("cloudflare.realtime-app-id"),
        appToken = secrets.require("cloudflare.realtime-app-token"),
        http = clients.named("cloudflare-realtime") { baseUrl = "https://rtc.live.cloudflare.com" },
        turn = turn,
    )
}

// Asha's models on Workers AI, each overridable by its INTEGRATIONS_AI_MODEL_* setting. Ids from developers.cloudflare.com/workers-ai/models/.

/** Brave Search's key, `BRAVE_SEARCH_API_KEY`; its presence also picks `brave` when no provider is named. */
private const val BRAVE_KEY = "brave-search.api-key"

/** Anthropic's Admin API key for the staff cost report, `OPS_ANTHROPIC_ADMIN_KEY`; never logged. */
private const val ANTHROPIC_ADMIN_KEY = "ops.anthropic-admin-key"
private const val COINGECKO_KEY = "coingecko.api-key"

/** CoinGecko's one client, for prices and Asha's coin data: short timeouts and no retry, since a reader waits. */
private fun coinGeckoClient(clients: HttpClients) =
    clients.named("coingecko") {
        baseUrl = CoinGeckoPrices.BASE_URL
        connectTimeout = CoinGeckoPrices.TIMEOUT
        requestTimeout = CoinGeckoPrices.TIMEOUT
        maxAttempts = 1
    }

private fun pricesFreshFor(integrations: Config): Duration =
    Duration.ofSeconds(
        integrations
            .int("prices.fresh-seconds", CoinGeckoPrices.DEFAULT_FRESH_FOR.seconds.toInt())
            .coerceAtLeast(MIN_FRESH_SECONDS)
            .toLong(),
    )

/** The shortest a price table is kept: the Demo plan allows 30 calls a minute and 10,000 a month. */
private const val MIN_FRESH_SECONDS = 30

internal const val WORKERS_AI_TEXT_MODEL = "@cf/moonshotai/kimi-k2.6"

private const val WORKERS_AI_VISION_MODEL = "@cf/meta/llama-4-scout-17b-16e-instruct"
private const val WORKERS_AI_STT_MODEL = "@cf/openai/whisper-large-v3-turbo"
private const val WORKERS_AI_TTS_MODEL = "@cf/deepgram/aura-2-en"
private const val WORKERS_AI_LIVE_STT_MODEL = "@cf/deepgram/nova-3"

// A reasoning model spends part of its budget thinking before it answers or calls a tool; 4096 leaves room for both.
internal const val WORKERS_AI_MAX_OUTPUT_TOKENS = 8192

/**
 * Every Workers AI model needs `CLOUDFLARE_ACCOUNT_ID`, `INTEGRATIONS_AI_GATEWAY` (the AI Gateway's name) and the secret
 * `CLOUDFLARE_AI_TOKEN` (an API token with Workers AI Read). One shared `workers-ai` client: a reasoning model can take
 * longer than the usual 15 s, so it waits up to a minute.
 */
internal fun workersAi(
    config: Config,
    secrets: Secrets,
    clients: HttpClients,
): WorkersAiGateway {
    val accountId = config.requireString("cloudflare.account-id")
    return WorkersAiGateway(
        http =
            clients.named("workers-ai") {
                baseUrl = WorkersAiGateway.baseUrl(accountId)
                requestTimeout = Duration.ofSeconds(60)
            },
        token = secrets.require("cloudflare.ai-token"),
        gateway = config.requireString("integrations.ai.gateway"),
    )
}

/**
 * Live voice opens Workers AI sockets, on its own `workers-ai-live` client: `webSockets = true` installs the WebSockets
 * plugin, keeps retries and the circuit breaker away from the socket and times only its opening. It can't share
 * `workers-ai`, whose settings were fixed by whichever contract asked for it first. Same account, gateway and token.
 */
private fun workersAiLive(
    config: Config,
    secrets: Secrets,
    clients: HttpClients,
): WorkersAiLiveSocket {
    val accountId = config.requireString("cloudflare.account-id")
    return WorkersAiLiveSocket(
        http = clients.named("workers-ai-live") { webSockets = true },
        token = secrets.require("cloudflare.ai-token"),
        gateway = config.requireString("integrations.ai.gateway"),
        socketBase = WorkersAiLiveSocket.socketBase(accountId),
    )
}

/** `INTEGRATIONS_AI_LIVE_TTS_ENCODING`: `mulaw` (the default) or `linear16`. Anything else stops startup. */
private fun liveSpeechEncoding(name: String): AudioEncoding =
    when (name.lowercase()) {
        "mulaw" -> AudioEncoding.MULAW
        "linear16" -> AudioEncoding.LINEAR16
        else -> throw IllegalStateException("integrations.ai.live.tts-encoding must be mulaw or linear16 (was '$name')")
    }
