package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.KycEnvironment
import com.circaso.integrations.api.KycSessionRequest
import com.circaso.integrations.api.KycStatus
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class KycTest {
    private fun request(subject: String, environment: KycEnvironment = KycEnvironment.SANDBOX) =
        KycSessionRequest(environment, subject, "wf")

    @Test
    fun `the log provider needs nothing from the person and approves at once, a different document for each`() =
        runBlocking<Unit> {
            val log = LogKyc()

            val amina = (log.startSession(request("user:amina")) as Resource.Success).data
            val again = (log.startSession(request("user:amina")) as Resource.Success).data
            val brian = (log.startSession(request("user:brian")) as Resource.Success).data
            assertNull(amina.token)
            assertNull(amina.url)
            assertEquals(amina.sessionId, again.sessionId, "the same person gets the same session")
            assertNotEquals(amina.sessionId, brian.sessionId)

            val decided = (log.decision(KycEnvironment.SANDBOX, amina.sessionId) as Resource.Success).data
            val other = (log.decision(KycEnvironment.SANDBOX, brian.sessionId) as Resource.Success).data
            assertEquals(KycStatus.APPROVED, decided.status)
            assertNotEquals(decided.document!!.number, other.document!!.number)
        }

    @Test
    fun `the fake provider keeps one unfinished session per subject and reports what the test decided`() =
        runBlocking<Unit> {
            val fake = FakeKyc()

            val first = (fake.startSession(request("user:1")) as Resource.Success).data
            val same = (fake.startSession(request("user:1")) as Resource.Success).data
            assertEquals(first.sessionId, same.sessionId)
            val pending = (fake.decision(KycEnvironment.SANDBOX, first.sessionId) as Resource.Success).data
            assertEquals(KycStatus.PENDING, pending.status)

            fake.decline(first.sessionId)
            val declined = (fake.decision(KycEnvironment.SANDBOX, first.sessionId) as Resource.Success).data
            assertEquals(KycStatus.DECLINED, declined.status)
            val next = (fake.startSession(request("user:1")) as Resource.Success).data
            assertNotEquals(first.sessionId, next.sessionId, "a finished session is not handed out again")

            fake.available -= KycEnvironment.LIVE
            val live = fake.startSession(request("user:1", KycEnvironment.LIVE))
            assertIs<IntegrationError.NotConfigured>((live as Resource.Error).error)
        }
}
