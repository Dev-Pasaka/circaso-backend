package com.circaso.integrations.impl

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Environment
import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.RedisScopes
import com.circaso.infrastructure.api.Secrets
import com.circaso.integrations.api.AudioSpan
import com.circaso.integrations.api.CallMedia
import com.circaso.integrations.api.CarrierPhoneVerification
import com.circaso.integrations.api.CryptoMarkets
import com.circaso.integrations.api.CryptoPrices
import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.ImageInput
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.LiveSpeech
import com.circaso.integrations.api.LiveTranscription
import com.circaso.integrations.api.LiveTranscriptionOptions
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelPurpose
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.PhoneVerificationGateway
import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.api.PushMessage
import com.circaso.integrations.api.PushPlatform
import com.circaso.integrations.api.PushTarget
import com.circaso.integrations.api.SmsGateway
import com.circaso.integrations.api.SmsPurpose
import com.circaso.integrations.api.SocialProvider
import com.circaso.integrations.api.SocialSignIn
import com.circaso.integrations.api.SpeechEvent
import com.circaso.integrations.api.SpeechToText
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.api.TextToSpeech
import com.circaso.integrations.api.TranscriptionEvent
import com.circaso.integrations.api.VisionModel
import com.circaso.integrations.api.WebSearch
import com.circaso.integrations.api.WebhookVendor
import com.circaso.integrations.impl.brave.BraveWebSearch
import com.circaso.integrations.impl.circle.CircleCustody
import com.circaso.integrations.impl.coingecko.CoinGeckoMarkets
import com.circaso.integrations.impl.coingecko.CoinGeckoPrices
import com.circaso.integrations.impl.dev.FakeSms
import com.circaso.integrations.impl.dev.FakeSocialSignIn
import com.circaso.integrations.impl.dev.FakeWebSearch
import com.circaso.integrations.impl.dev.LogCallMedia
import com.circaso.integrations.impl.dev.LogCustody
import com.circaso.integrations.impl.dev.LogExchangeRates
import com.circaso.integrations.impl.dev.LogLanguageModel
import com.circaso.integrations.impl.dev.NoCryptoMarkets
import com.circaso.integrations.impl.dev.NoCryptoPrices
import com.circaso.integrations.impl.llm.RetryingLanguageModel
import com.circaso.integrations.impl.llm.RoutedLanguageModel
import com.circaso.integrations.impl.llm.UnavailableLanguageModel
import com.circaso.integrations.impl.workersai.WorkersAiLanguageModel
import com.circaso.integrations.impl.workersai.WorkersAiLiveSpeech
import com.circaso.integrations.impl.workersai.WorkersAiLiveTranscription
import com.circaso.integrations.impl.workersai.WorkersAiSpeechToText
import com.circaso.integrations.impl.workersai.WorkersAiTextToSpeech
import com.circaso.integrations.impl.workersai.WorkersAiVisionModel
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegrationsModuleTest {
    private class MapConfig(
        private val values: Map<String, String>,
        private val prefix: String = "",
        override val environment: Environment = Environment.LOCAL,
    ) : Config {
        override fun string(key: String): String? = values[prefix + key]

        override fun section(prefix: String): Config = MapConfig(values, this.prefix + prefix + ".", environment)
    }

    private val phone = PhoneNumber.parse("+254712345678")!!

    @AfterTest
    fun tearDown() = stopKoin()

    private fun koin(vararg values: Pair<String, String>) = koin(emptyMap(), *values)

    private fun koin(secrets: Map<String, String>, vararg values: Pair<String, String>) =
        startKoin {
            modules(
                integrationsModule(
                    MapConfig(values.toMap()),
                    object : Secrets {
                        override fun get(name: String): String? = secrets[name]
                    },
                ),
                module {
                    single<HttpClients> {
                        FakeHttpClients { _ ->
                            respondOk()
                        }
                    }
                    single<RedisScopes> {
                        object : RedisScopes {
                            override fun scope(prefix: String) = MemoryRedis(prefix)
                        }
                    }
                },
            )
        }.koin

    @Test
    fun `every contract defaults to its log provider`() =
        runTest {
            val koin = koin()
            assertIs<Resource.Success<*>>(
                koin.get<SmsGateway>().send(phone, "Your code is 123456", SmsPurpose.VERIFICATION),
            )
            assertIs<Resource.Success<*>>(
                koin.get<PushGateway>().send(PushTarget(PushPlatform.ANDROID, "t"), PushMessage("Hi", "there")),
            )
        }

    @Test
    fun `fake sms records what was sent`() =
        runTest {
            val sms = koin("integrations.sms.provider" to "fake").get<SmsGateway>() as FakeSms
            sms.send(phone, "code 1", SmsPurpose.VERIFICATION)
            assertEquals(listOf("code 1"), sms.sent.map { it.text })
        }

    @Test
    fun `workers-ai refuses to start without the account, the gateway and the token`() {
        val e = assertFailsWith<Exception> { koin("integrations.stt.provider" to "workers-ai").get<SpeechToText>() }
        assertTrue(
            "cloudflare.account-id" in
                generateSequence<Throwable>(
                    e,
                ) { it.cause }.joinToString { it.message.orEmpty() },
        )
    }

    @Test
    fun `workers-ai serves every Asha model through one gateway once configured`() =
        runTest {
            val clients = FakeHttpClients { _ -> respondOk() }
            val koin =
                startKoin {
                    modules(
                        integrationsModule(
                            MapConfig(
                                mapOf(
                                    "cloudflare.account-id" to "acc123",
                                    "integrations.ai.gateway" to "asha",
                                    "integrations.ai.model.text" to "@cf/zai-org/glm-5.2",
                                    "integrations.llm.provider" to "workers-ai",
                                    "integrations.vision.provider" to "workers-ai",
                                    "integrations.stt.provider" to "workers-ai",
                                    "integrations.tts.provider" to "workers-ai",
                                ),
                            ),
                            object : Secrets {
                                override fun get(name: String): String? =
                                    "cf-token".takeIf { name == "cloudflare.ai-token" }
                            },
                        ),
                        module { single<HttpClients> { clients } },
                    )
                }.koin

            assertIs<WorkersAiLanguageModel>(koin.get<LanguageModel>())
            assertIs<WorkersAiVisionModel>(koin.get<VisionModel>())
            assertIs<WorkersAiSpeechToText>(koin.get<SpeechToText>())
            assertIs<WorkersAiTextToSpeech>(koin.get<TextToSpeech>())
            koin.get<LanguageModel>().complete(ModelRequest("s", listOf(ModelMessage.User("u"))))
            assertTrue(clients.names.all { it == "workers-ai" })
            assertTrue("glm-5.2" in clients.last.text(), "a model id setting replaces the default")
            koin
                .get<LanguageModel>()
                .complete(ModelRequest("s", listOf(ModelMessage.User("u")), purpose = ModelPurpose.VOICE))
            assertTrue(
                "glm-5.2" in clients.last.text() && "phonellm" !in clients.last.text(),
                "voice turns fall back to the text model unless a voice model is set (no PhoneLLM, 2026-11-07)",
            )
        }

    private fun ashaKoin(
        clients: FakeHttpClients,
        secrets: Map<String, String>,
        vararg values: Pair<String, String>,
    ) = startKoin {
        modules(
            integrationsModule(
                MapConfig(mapOf("cloudflare.account-id" to "acc123", "integrations.ai.gateway" to "asha") + values),
                object : Secrets {
                    override fun get(name: String): String? = secrets[name]
                },
            ),
            module { single<HttpClients> { clients } },
        )
    }.koin

    private val cloudflare = mapOf("cloudflare.ai-token" to "cf-token")
    private val keyed = cloudflare + ("anthropic.api-key" to "test-anthropic-key")

    private fun hosts(clients: FakeHttpClients) = clients.requests.map { it.url.host }

    @Test
    fun `anthropic answers chat and voice on its own client, retries once and never falls back to Workers AI`() =
        runTest {
            val clients = FakeHttpClients { _ -> respondOk() }
            val koin =
                ashaKoin(
                    clients,
                    keyed,
                    "integrations.llm.provider" to "anthropic",
                    "integrations.llm.voice-provider" to "anthropic",
                )

            val model = koin.get<LanguageModel>()

            assertIs<RetryingLanguageModel>(model)
            val config = clients.configs.getValue("anthropic")
            assertEquals(1, config.maxAttempts)
            assertEquals("https://api.anthropic.com/", config.baseUrl)
            // MockEngine can't stream Server-Sent Events, so Claude can't answer here: each turn fails, and nothing
            // else is ever asked (decision 2026-11-07: no fallback model; the retry itself is RetryingLanguageModelTest's)
            for (purpose in ModelPurpose.entries) {
                clients.requests.clear()
                val answer = model.complete(ModelRequest("s", listOf(ModelMessage.User("u")), purpose = purpose))
                assertIs<IntegrationError.Unavailable>((answer as Resource.Error).error, "$purpose")
                assertTrue("api.cloudflare.com" !in hosts(clients), "$purpose")
            }
            assertIs<Resource.Error>(model.probe())
            assertTrue("workers-ai" !in clients.names)
        }

    @Test
    fun `anthropic without its key is the log model locally and never Workers AI`() =
        runTest {
            val clients = FakeHttpClients { _ -> respondOk() }
            val koin =
                ashaKoin(
                    clients,
                    cloudflare,
                    "integrations.llm.provider" to "anthropic",
                    "integrations.llm.voice-provider" to "anthropic",
                )

            assertIs<LogLanguageModel>(koin.get<LanguageModel>())
            assertTrue("anthropic" !in clients.names)
            assertTrue("workers-ai" !in clients.names)
        }

    @Test
    fun `anthropic without its key is always unavailable outside a local server`() =
        runTest {
            val model =
                ashaLanguageModel(
                    MapConfig(mapOf("integrations.llm.provider" to "anthropic"), environment = Environment.PRODUCTION),
                    object : Secrets {
                        override fun get(name: String): String? = null
                    },
                    FakeHttpClients { _ -> respondOk() },
                )

            assertIs<UnavailableLanguageModel>(model)
            val error = (model.complete(ModelRequest("s", emptyList())) as Resource.Error).error
            assertEquals(false, (error as IntegrationError.Unavailable).isTransient)
            assertIs<Resource.Error>(model.probe())
        }

    @Test
    fun `voice can be on anthropic alone, and typed turns stay where they were`() =
        runTest {
            val clients = FakeHttpClients { _ -> respondOk() }
            val koin =
                ashaKoin(
                    clients,
                    keyed,
                    "integrations.llm.provider" to "log",
                    "integrations.llm.voice-provider" to "anthropic",
                )
            val model = koin.get<LanguageModel>()

            assertIs<RoutedLanguageModel>(model)
            assertIs<Resource.Success<*>>(
                model.complete(ModelRequest("s", listOf(ModelMessage.User("u")), purpose = ModelPurpose.CHAT)),
            )
            assertTrue(clients.requests.isEmpty())
        }

    @Test
    fun `Asha's model defaults to the log model, and a wrong provider or effort stops startup`() {
        assertIs<LogLanguageModel>(koin().get<LanguageModel>())
        stopKoin()
        assertFailsWith<Exception> { koin("integrations.llm.voice-provider" to "openai").get<LanguageModel>() }
        stopKoin()
        assertFailsWith<Exception> {
            ashaKoin(
                FakeHttpClients { _ -> respondOk() },
                keyed,
                "integrations.llm.provider" to "anthropic",
                "integrations.anthropic.effort.text" to "max",
            ).get<LanguageModel>()
        }
    }

    @Test
    fun `live voice runs on its own workers-ai-live client, with the WebSockets flag`() {
        val clients = FakeHttpClients { _ -> respondOk() }
        val koin =
            startKoin {
                modules(
                    integrationsModule(
                        MapConfig(
                            mapOf(
                                "cloudflare.account-id" to "acc123",
                                "integrations.ai.gateway" to "asha",
                                "integrations.live-stt.provider" to "workers-ai",
                                "integrations.live-tts.provider" to "workers-ai",
                            ),
                        ),
                        object : Secrets {
                            override fun get(name: String): String? =
                                "cf-token".takeIf { name == "cloudflare.ai-token" }
                        },
                    ),
                    module { single<HttpClients> { clients } },
                )
            }.koin

        assertIs<WorkersAiLiveTranscription>(koin.get<LiveTranscription>())
        assertIs<WorkersAiLiveSpeech>(koin.get<LiveSpeech>())
        assertEquals(setOf("workers-ai-live"), clients.names.toSet())
        assertTrue(clients.configs.getValue("workers-ai-live").webSockets)
    }

    @Test
    fun `the development live voice reads text frames as speech and speaks English only`() =
        runTest {
            val koin = koin()
            val ears = koin.get<LiveTranscription>()
            val voice = koin.get<LiveSpeech>()

            val session = (ears.open(LiveTranscriptionOptions("en")) as Resource.Success).data
            session.send("hello Asha".toByteArray())
            session.send(byteArrayOf(-1, -2, 0, 7))
            assertEquals(TranscriptionEvent.SpeechStarted, session.next())
            assertEquals(TranscriptionEvent.Interim("hello Asha", AudioSpan(0.0, 0.8)), session.next())
            assertEquals(
                TranscriptionEvent.Final("hello Asha", endOfTurn = true, audio = AudioSpan(0.0, 0.8)),
                session.next(),
            )
            session.close()
            assertEquals(null, session.next(), "binary audio is silence to the fake")

            val speaking = (voice.open(null) as Resource.Success).data
            speaking.speak("Hi.")
            speaking.flush()
            assertIs<SpeechEvent.Audio>(speaking.next())
            assertEquals(SpeechEvent.Flushed, speaking.next())

            assertIs<IntegrationError.UnsupportedLanguage>(
                (ears.open(LiveTranscriptionOptions("sw")) as Resource.Error).error,
            )
            assertIs<IntegrationError.UnsupportedLanguage>((voice.open("sw") as Resource.Error).error)
        }

    @Test
    fun `vision defaults to its development model`() =
        runTest {
            val answer = koin().get<VisionModel>().describe(ImageInput(byteArrayOf(1, 2), "image/png"), "Describe it")
            assertIs<Resource.Success<*>>(answer)
        }

    @Test
    fun `the development voice speaks English only, like Aura, so the Swahili path works on dev`() =
        runTest {
            for (provider in listOf("log", "fake")) {
                val voice = koin("integrations.tts.provider" to provider).get<TextToSpeech>()

                assertIs<Resource.Success<*>>(voice.speak("How are you", null), provider)
                assertIs<Resource.Success<*>>(voice.speak("How are you", "en-KE"), provider)
                val swahili = voice.speak("Habari yako", "sw") as Resource.Error
                assertIs<IntegrationError.UnsupportedLanguage>(swahili.error, provider)
                stopKoin()
            }
        }

    @Test
    fun `fcm refuses to start without its service account`() {
        val e = assertFailsWith<Exception> { koin("integrations.push.provider" to "fcm").get<PushGateway>() }
        assertTrue(
            "fcm.service-account" in generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() },
        )
    }

    @Test
    fun `web search defaults to the fake and tavily refuses to start without its key`() {
        assertIs<FakeWebSearch>(koin().get<WebSearch>())
        stopKoin()
        val e = assertFailsWith<Exception> { koin("integrations.search.provider" to "tavily").get<WebSearch>() }
        assertTrue(
            "tavily.api-key" in generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() },
        )
    }

    @Test
    fun `coin prices and Asha's coin data are off without a CoinGecko key, and coingecko with one`() =
        runTest {
            val off = koin()
            assertIs<NoCryptoPrices>(off.get<CryptoPrices>())
            assertIs<NoCryptoMarkets>(off.get<CryptoMarkets>())
            assertEquals(mapOf("USDC" to BigDecimal.ONE), off.get<CryptoPrices>().usdPrices(setOf("USDC", "ETH")))
            stopKoin()

            val on = koin(mapOf("coingecko.api-key" to "k"))
            assertIs<CoinGeckoPrices>(on.get<CryptoPrices>())
            assertIs<CoinGeckoMarkets>(on.get<CryptoMarkets>())
            stopKoin()

            val local = koin("integrations.prices.provider" to "log")
            assertEquals(BigDecimal("3500"), local.get<CryptoPrices>().usdPrice("ETH"))
            assertIs<NoCryptoMarkets>(local.get<CryptoMarkets>(), "made-up market data is never shown")
            stopKoin()

            val e =
                assertFailsWith<Exception> { koin("integrations.prices.provider" to "coingecko").get<CryptoPrices>() }
            assertTrue(
                "coingecko.api-key" in
                    generateSequence<Throwable>(
                        e,
                    ) { it.cause }.joinToString { it.message.orEmpty() },
            )
        }

    @Test
    fun `web search is brave when its key is set and no provider is named, and brave needs the key`() {
        assertIs<BraveWebSearch>(koin(mapOf("brave-search.api-key" to "k")).get<WebSearch>())
        stopKoin()
        assertIs<FakeWebSearch>(
            koin(mapOf("brave-search.api-key" to "k"), "integrations.search.provider" to "fake").get<WebSearch>(),
        )
        stopKoin()
        val e = assertFailsWith<Exception> { koin("integrations.search.provider" to "brave").get<WebSearch>() }
        assertTrue(
            "brave-search.api-key" in
                generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() },
        )
    }

    @Test
    fun `calls default to the log media and cloudflare refuses to start without its app id`() {
        assertIs<LogCallMedia>(koin().get<CallMedia>())
        stopKoin()
        val e = assertFailsWith<Exception> { koin("integrations.calls.provider" to "cloudflare").get<CallMedia>() }
        assertTrue(
            "cloudflare.realtime-app-id" in
                generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() },
        )
    }

    @Test
    fun `africastalking refuses to start without its username and key`() {
        val e = assertFailsWith<Exception> { koin("integrations.sms.provider" to "africastalking").get<SmsGateway>() }
        assertTrue(
            "africastalking" in generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() },
        )
    }

    @Test
    fun `an unknown provider stops startup`() {
        assertFailsWith<Exception> { koin("integrations.sms.provider" to "carrier-pigeon").get<SmsGateway>() }
    }

    @Test
    fun `dev social sign-in accepts dev tokens only`() =
        runTest {
            val social = koin().get<SocialSignIn>()
            val ok = social.verify(SocialProvider.GOOGLE, "dev:sub-1:amina@example.com:Amina", "n") as Resource.Success
            assertEquals("sub-1", ok.data.subject)
            assertTrue(ok.data.emailVerified)
            assertIs<Resource.Error>(social.verify(SocialProvider.APPLE, "garbage", "n"))
        }

    @Test
    fun `dev carrier verification reads the number from the token`() =
        runTest {
            val carrier = koin().get<CarrierPhoneVerification>()
            assertEquals(phone, (carrier.verify("+254712345678") as Resource.Success).data)
            assertIs<Resource.Error>(carrier.verify("nope"))
        }

    @Test
    fun `resend refuses to start without its key, sender and a template for every email`() {
        val e =
            assertFailsWith<Exception> {
                koin("integrations.email.provider" to "resend").get<EmailGateway>()
            }
        assertTrue(
            "resend.api-key" in generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() },
        )
    }

    @Test
    fun `google-apple needs at least one client id, and then verifies for real`() {
        val e =
            assertFailsWith<Exception> { koin("integrations.social.provider" to "google-apple").get<SocialSignIn>() }
        assertTrue("client-ids" in generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() })
        stopKoin()
        val social =
            koin(
                "integrations.social.provider" to "google-apple",
                "integrations.social.google.client-ids" to "web, android",
            ).get<SocialSignIn>()
        assertTrue(social !is FakeSocialSignIn)
    }

    @Test
    fun `phone verification defaults to none, and twilio needs its account, token and service`() {
        assertEquals(false, koin().get<PhoneVerificationGateway>().managesCodes)
        stopKoin()
        val e =
            assertFailsWith<Exception> {
                koin(
                    "integrations.verify.provider" to "twilio",
                ).get<PhoneVerificationGateway>()
            }
        assertTrue("twilio" in generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() })
    }

    @Test
    fun `custody defaults to log, whose wallets appear at once, and binds no webhook`() =
        runTest {
            val koin = koin()
            val custody = koin.get<StablecoinCustody>()
            assertIs<LogCustody>(custody)
            custody.createUser("u-1")
            assertTrue((custody.createWallet("u-1", UUID.randomUUID()) as Resource.Success).data.needsSignature)
            assertEquals(1, (custody.wallets("u-1") as Resource.Success).data.size)
            assertTrue(koin.getAll<WebhookVendor>().isEmpty())
        }

    @Test
    fun `exchange rates default to log, which knows a few currencies and no others`() =
        runTest {
            val rates = koin().get<ExchangeRates>()
            assertIs<LogExchangeRates>(rates)
            assertEquals(BigDecimal("129.25"), rates.usdRate("KES")?.rate)
            assertNull(rates.usdRate("XYZ"))
        }

    @Test
    fun `circle needs its key and app id, then serves custody and the webhook on one client`() {
        val e =
            assertFailsWith<Exception> {
                koin("integrations.custody.provider" to "circle").get<StablecoinCustody>()
            }
        val messages = generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() }
        assertTrue("circle.api-key" in messages || "app-id" in messages, messages)
        stopKoin()

        val clients = FakeHttpClients { _ -> respondOk() }
        val koin =
            startKoin {
                modules(
                    integrationsModule(
                        MapConfig(
                            mapOf(
                                "integrations.custody.provider" to "circle",
                                "integrations.custody.app-id" to "app-1",
                                "integrations.custody.chains" to "BASE-SEPOLIA,SOL-DEVNET",
                            ),
                        ),
                        object : Secrets {
                            override fun get(name: String): String? =
                                "TEST_API_KEY:a:b".takeIf {
                                    name ==
                                        "circle.api-key"
                                }
                        },
                    ),
                    module { single<HttpClients> { clients } },
                )
            }.koin

        val custody = koin.get<StablecoinCustody>()
        assertIs<CircleCustody>(custody)
        assertEquals(listOf("BASE-SEPOLIA", "SOL-DEVNET"), custody.supported.chains.map { it.blockchain })
        assertEquals(listOf("circle"), koin.getAll<WebhookVendor>().map { it.name })
        assertEquals(listOf("circle"), clients.names)
        assertEquals("https://api.circle.com", clients.configs.getValue("circle").baseUrl)
    }
}
