package com.circaso.integrations.impl

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Environment
import com.circaso.infrastructure.api.Secrets
import com.circaso.integrations.api.PushMessage
import com.circaso.integrations.api.PushPlatform
import com.circaso.integrations.api.PushTarget
import com.circaso.integrations.api.SmsGateway
import com.circaso.integrations.api.SmsPurpose
import com.circaso.integrations.api.SocialProvider
import com.circaso.integrations.api.SocialSignIn
import com.circaso.integrations.api.CarrierPhoneVerification
import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.impl.dev.FakeSms
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
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
        startKoin { modules(integrationsModule(MapConfig(values.toMap()), object : Secrets { override fun get(name: String): String? = null })) }.koin

    @Test
    fun `every contract defaults to its log provider`() = runTest {
        val koin = koin()
        assertIs<Resource.Success<*>>(koin.get<SmsGateway>().send(phone, "Your code is 123456", SmsPurpose.VERIFICATION))
        assertIs<Resource.Success<*>>(koin.get<PushGateway>().send(PushTarget(PushPlatform.ANDROID, "t"), PushMessage("Hi", "there")))
    }

    @Test
    fun `fake sms records what was sent`() = runTest {
        val sms = koin("integrations.sms.provider" to "fake").get<SmsGateway>() as FakeSms
        sms.send(phone, "code 1", SmsPurpose.VERIFICATION)
        assertEquals(listOf("code 1"), sms.sent.map { it.text })
    }

    @Test
    fun `a vendor placeholder fails loudly instead of pretending to deliver`() = runTest {
        val push = koin("integrations.push.provider" to "fcm-apns").get<PushGateway>()
        val result = push.send(PushTarget(PushPlatform.ANDROID, "t"), PushMessage("Hi", "there"))
        assertEquals("integrations.not_implemented", (result as Resource.Error).error.code)
    }

    @Test
    fun `africastalking refuses to start without its username and key`() {
        val e = assertFailsWith<Exception> { koin("integrations.sms.provider" to "africastalking").get<SmsGateway>() }
        assertTrue("africastalking" in generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() })
    }

    @Test
    fun `an unknown provider stops startup`() {
        assertFailsWith<Exception> { koin("integrations.sms.provider" to "carrier-pigeon").get<SmsGateway>() }
    }

    @Test
    fun `dev social sign-in accepts dev tokens only`() = runTest {
        val social = koin().get<SocialSignIn>()
        val ok = social.verify(SocialProvider.GOOGLE, "dev:sub-1:amina@example.com:Amina", "n") as Resource.Success
        assertEquals("sub-1", ok.data.subject)
        assertTrue(ok.data.emailVerified)
        assertIs<Resource.Error>(social.verify(SocialProvider.APPLE, "garbage", "n"))
    }

    @Test
    fun `dev carrier verification reads the number from the token`() = runTest {
        val carrier = koin().get<CarrierPhoneVerification>()
        assertEquals(phone, (carrier.verify("+254712345678") as Resource.Success).data)
        assertIs<Resource.Error>(carrier.verify("nope"))
    }

    @Test
    fun `resend refuses to start without its key, sender and a template for every email`() {
        val e = assertFailsWith<Exception> { koin("integrations.email.provider" to "resend").get<com.circaso.integrations.api.EmailGateway>() }
        assertTrue("resend.api-key" in generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() })
    }

    @Test
    fun `google-apple needs at least one client id, and then verifies for real`() {
        val e = assertFailsWith<Exception> { koin("integrations.social.provider" to "google-apple").get<SocialSignIn>() }
        assertTrue("client-ids" in generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() })
        stopKoin()
        val social = koin("integrations.social.provider" to "google-apple", "integrations.social.google.client-ids" to "web, android").get<SocialSignIn>()
        assertTrue(social !is com.circaso.integrations.impl.dev.FakeSocialSignIn)
    }

    @Test
    fun `phone verification defaults to none, and twilio needs its account, token and service`() {
        assertEquals(false, koin().get<com.circaso.integrations.api.PhoneVerificationGateway>().managesCodes)
        stopKoin()
        val e = assertFailsWith<Exception> { koin("integrations.verify.provider" to "twilio").get<com.circaso.integrations.api.PhoneVerificationGateway>() }
        assertTrue("twilio" in generateSequence<Throwable>(e) { it.cause }.joinToString { it.message.orEmpty() })
    }
}

