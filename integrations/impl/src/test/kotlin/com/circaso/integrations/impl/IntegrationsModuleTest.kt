package com.circaso.integrations.impl

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Environment
import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.Secrets
import com.circaso.integrations.api.CarrierPhoneVerification
import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.ImageInput
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelMessage
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
import com.circaso.integrations.api.SpeechToText
import com.circaso.integrations.api.TextToSpeech
import com.circaso.integrations.api.VisionModel
import com.circaso.integrations.impl.dev.FakeSms
import com.circaso.integrations.impl.dev.FakeSocialSignIn
import com.circaso.integrations.impl.workersai.WorkersAiLanguageModel
import com.circaso.integrations.impl.workersai.WorkersAiSpeechToText
import com.circaso.integrations.impl.workersai.WorkersAiTextToSpeech
import com.circaso.integrations.impl.workersai.WorkersAiVisionModel
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IntegrationsModuleTest {
    private class MapConfig(private val values: Map<String, String>, private val prefix: String = "") : Config {
        override val environment = Environment.LOCAL

        override fun string(key: String): String? = values[prefix + key]

        override fun section(prefix: String): Config = MapConfig(values, this.prefix + prefix + ".")
    }

    private val phone = PhoneNumber.parse("+254712345678")!!

    @AfterTest
    fun tearDown() = stopKoin()

    private fun koin(vararg values: Pair<String, String>) =
        startKoin {
            modules(
                integrationsModule(
                    MapConfig(values.toMap()),
                    object : Secrets {
                        override fun get(name: String): String? = null
                    },
                ),
                org.koin.dsl.module {
                    single<HttpClients> {
                        FakeHttpClients { _ ->
                            respondOk()
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
                        org.koin.dsl.module { single<HttpClients> { clients } },
                    )
                }.koin

            assertIs<WorkersAiLanguageModel>(koin.get<LanguageModel>())
            assertIs<WorkersAiVisionModel>(koin.get<VisionModel>())
            assertIs<WorkersAiSpeechToText>(koin.get<SpeechToText>())
            assertIs<WorkersAiTextToSpeech>(koin.get<TextToSpeech>())
            koin.get<LanguageModel>().complete(ModelRequest("s", listOf(ModelMessage.User("u"))))
            assertTrue(clients.names.all { it == "workers-ai" })
            assertTrue("glm-5.2" in clients.last.text(), "a model id setting replaces the default")
        }

    @Test
    fun `vision defaults to its development model`() =
        runTest {
            val answer = koin().get<VisionModel>().describe(ImageInput.Url("https://x.example/p.png"), "Describe it")
            assertIs<Resource.Success<*>>(answer)
        }

    @Test
    fun `fcm refuses to start without its service account`() {
        val e = assertFailsWith<Exception> { koin("integrations.push.provider" to "fcm").get<PushGateway>() }
        assertTrue(
            "fcm.service-account" in generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() },
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
}
