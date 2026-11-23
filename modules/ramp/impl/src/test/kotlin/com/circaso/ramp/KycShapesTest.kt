package com.circaso.ramp

import com.circaso.integrations.api.KycDeclineReason
import com.circaso.integrations.api.KycEnvironment
import com.circaso.ramp.domain.models.BlockReason
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.presentation.dto.KycResponse
import com.circaso.ramp.presentation.dto.RampStatusResponse
import com.circaso.ramp.presentation.dto.VerificationDto
import com.circaso.ramp.presentation.mappers.toDto
import com.circaso.ramp.presentation.mappers.toResponse
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The JSON the app is built against (docs/mobile-integration.md → the ramp): R0's answer, R1's `verification`, and
 * what each state looks like on the wire. Encoded the way the gateway encodes every response.
 */
class KycShapesTest {
    private val ramp = Harness()
    private val amina get() = ramp.amina

    private fun <T> Resource<T>.value() = (this as Resource.Success).data

    private suspend fun r0(): String =
        CircasoJson.encodeToString(KycResponse.serializer(), ramp.startKyc(amina, TESTNET).value().toResponse())

    private suspend fun read(): String =
        CircasoJson.encodeToString(VerificationDto.serializer(), ramp.getKyc(amina, TESTNET).value().toDto())

    private suspend fun r1() =
        Json
            .parseToJsonElement(
                CircasoJson.encodeToString(
                    RampStatusResponse.serializer(),
                    ramp.status(amina, TESTNET).value().toResponse(),
                ),
            ).jsonObject

    private fun session() = ramp.sessionOf(Subject.user(amina))

    @Test
    fun `R0 answers the state and, while a check is open, the SDK's token, the hosted page and their expiry`() =
        runTest {
            ramp.requireKyc()

            assertEquals(
                """{"status":"PENDING","required":true,"canStart":true,"reason":null,"retryAt":null,""" +
                    """"session":{"sessionToken":"token-1","url":"https://verify.test/session/token-1",""" +
                    """"expiresAt":"2026-11-23T12:00:00Z"}}""",
                r0(),
            )
            assertEquals(
                """{"status":"PENDING","required":true,"canStart":true,"reason":null,"retryAt":null}""",
                read(),
                "the status read never carries the link",
            )

            ramp.didit.approve(session())
            ramp.webhook(session())
            assertEquals(
                """{"status":"VERIFIED","required":true,"canStart":false,"reason":null,"retryAt":null,"session":null}""",
                r0(),
            )
        }

    @Test
    fun `R1 carries the verification in full, the short kyc older apps read, and blocked`() =
        runTest {
            ramp.requireKyc()
            ramp.verified(ramp.crynce)

            val none = r1()
            assertEquals(
                """{"status":"NONE","required":true,"canStart":true,"reason":null,"retryAt":null}""",
                none.getValue("verification").toString(),
            )
            assertEquals("\"NONE\"", none.getValue("kyc").toString())
            assertEquals("false", none.getValue("blocked").toString())
            assertEquals("false", none.getValue("canTrade").toString())

            ramp.startKyc(amina, TESTNET)
            ramp.didit.decline(session(), KycDeclineReason.DOCUMENT_EXPIRED)
            ramp.webhook(session())
            val declined = r1()
            assertEquals(
                """{"status":"DECLINED","required":true,"canStart":true,"reason":"document_expired","retryAt":null}""",
                declined.getValue("verification").toString(),
            )
            assertEquals("\"NONE\"", declined.getValue("kyc").toString(), "anything but verified reads as NONE there")

            ramp.startKyc(amina, TESTNET)
            ramp.didit.approve(session())
            ramp.webhook(session())
            val verified = r1()
            assertEquals(
                """{"status":"VERIFIED","required":true,"canStart":false,"reason":null,"retryAt":null}""",
                verified.getValue("verification").toString(),
            )
            assertEquals("\"VERIFIED\"", verified.getValue("kyc").toString())
            assertEquals("true", verified.getValue("canTrade").toString())

            ramp.blockUser(amina, BlockReason.OPS, "why", "staff-1")
            val barred = r1()
            assertEquals("true", barred.getValue("blocked").toString())
            assertEquals("false", barred.getValue("canTrade").toString(), "verified, and still barred")
        }

    @Test
    fun `the day's cap shows as canStart false with the time it lifts`() =
        runTest {
            ramp.requireKyc { it.copy(kycAttemptsPerDay = 1) }
            ramp.startKyc(amina, TESTNET)
            ramp.didit.decline(session(), KycDeclineReason.LIVENESS)
            ramp.webhook(session())

            assertEquals(
                """{"status":"DECLINED","required":true,"canStart":false,"reason":"liveness",""" +
                    """"retryAt":"2026-11-22T21:00:00Z"}""",
                read(),
            )
        }

    @Test
    fun `where KYC isn't required R1 says so, and never a link, a token or an identity anywhere but R0's session`() =
        runTest {
            val open = r1()
            assertEquals(
                """{"status":"NONE","required":false,"canStart":true,"reason":null,"retryAt":null}""",
                open.getValue("verification").toString(),
            )
            assertEquals("\"NOT_REQUIRED\"", open.getValue("kyc").toString())

            ramp.requireKyc { it.copy(kycEnvironment = KycEnvironment.LIVE) }
            ramp.startKyc(amina, TESTNET)
            assertFalse("token-1" in r1().toString() || "verify.test" in r1().toString())
            ramp.didit.approve(session())
            ramp.webhook(session(), KycEnvironment.LIVE)
            val key =
                ramp.verificationRows.rows.values
                    .single { it.subject == Subject.user(amina) }
                    .identityKey!!
            assertFalse(key in r1().toString() || key in r0() || "12345678" in r1().toString())
            assertTrue(RampSettings.SANDBOX_WORKFLOW_USER !in r1().toString())
        }
}
