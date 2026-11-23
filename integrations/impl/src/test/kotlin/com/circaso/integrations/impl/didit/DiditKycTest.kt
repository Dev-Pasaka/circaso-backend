package com.circaso.integrations.impl.didit

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.KycDeclineReason
import com.circaso.integrations.api.KycEnvironment
import com.circaso.integrations.api.KycSessionRequest
import com.circaso.integrations.api.KycStatus
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.text
import com.circaso.shared.Resource
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Didit over a mock engine: the requests it sends and how its answers are read. Nothing here calls Didit. */
class DiditKycTest {
    private var status = 201
    private var reply =
        """{"session_id":"4c5c7f3a-1f82-4f3b-8d8e-1a8d2d2f9b7a","session_kind":"user","session_number":1024,""" +
            """"session_token":"short-token","url":"https://verify.didit.me/session/short-token",""" +
            """"status":"Not Started","workflow_id":"wf-user","vendor_data":"user:42",""" +
            """"expires_at":"2026-11-23T12:00:00.443172Z"}"""
    private var down = false

    /** What `…/features/overrides/` answers: where the person is in the flow. */
    private var flowStatus = 200
    private var flow =
        """{"session_id":"4c5c7f3a-1f82-4f3b-8d8e-1a8d2d2f9b7a","person_in_flow":true,""" +
            """"flow_finished_at":null,"overrides":[]}"""

    private val clients =
        FakeHttpClients { request ->
            when {
                down -> throw IOException("down")
                request.url.encodedPath.endsWith("/features/overrides/") -> json(flow, flowStatus)
                else -> json(reply, status)
            }
        }
    private val sandbox = DiditAccount(clients.named("didit-sandbox") { baseUrl = DiditKyc.BASE_URL }, "sandbox-key")
    private val didit = DiditKyc(mapOf(KycEnvironment.SANDBOX to sandbox))
    private val sessionId = "4c5c7f3a-1f82-4f3b-8d8e-1a8d2d2f9b7a"
    private val request =
        KycSessionRequest(
            environment = KycEnvironment.SANDBOX,
            subject = "user:42",
            workflowId = "wf-user",
            callbackUrl = null,
            validFor = Duration.ofHours(24),
        )

    private fun decision(body: String) =
        runBlocking {
            status = 200
            reply = body
            didit.decision(KycEnvironment.SANDBOX, sessionId)
        }

    private fun statusOf(didits: String) =
        (decision("""{"session_id":"$sessionId","status":"$didits","vendor_data":"user:42"}""") as Resource.Success)
            .data.status

    @Test
    fun `a session is created with the workflow, our reference and its lifetime, under the environment's key`() =
        runBlocking<Unit> {
            val session = assertIs<Resource.Success<*>>(didit.startSession(request)).data
            session as com.circaso.integrations.api.KycSession

            assertEquals(sessionId, session.sessionId)
            assertEquals("short-token", session.token, "what the native SDK starts with")
            assertEquals("https://verify.didit.me/session/short-token", session.url)
            assertEquals(Instant.parse("2026-11-23T12:00:00.443172Z"), session.expiresAt)
            val sent = clients.last
            assertEquals(HttpMethod.Post, sent.method)
            assertEquals("https://verification.didit.me/v3/session/", sent.url.toString())
            assertEquals("sandbox-key", sent.headers["x-api-key"])
            assertNull(sent.headers["Idempotency-Key"], "no key, so the shared client never repeats it")
            assertEquals(
                """{"workflow_id":"wf-user","vendor_data":"user:42","session_expiration_time":86400}""",
                sent.text(),
            )
            assertFalse("short-token" in session.toString(), "the token never prints")
        }

    @Test
    fun `the hosted flow's callback is sent when there is one, and the lifetime is kept inside Didit's bounds`() =
        runBlocking<Unit> {
            didit.startSession(
                request.copy(callbackUrl = "https://merchants.circaso.com/kyc/done", validFor = Duration.ofMinutes(5)),
            )
            assertEquals(
                """{"workflow_id":"wf-user","vendor_data":"user:42",""" +
                    """"callback":"https://merchants.circaso.com/kyc/done","session_expiration_time":3600}""",
                clients.last.text(),
            )

            didit.startSession(request.copy(validFor = null))
            assertEquals("""{"workflow_id":"wf-user","vendor_data":"user:42"}""", clients.last.text())
        }

    @Test
    fun `an environment with no key answers not configured, and an empty workflow is refused, without a call`() =
        runBlocking<Unit> {
            assertTrue(didit.isAvailable(KycEnvironment.SANDBOX))
            assertFalse(didit.isAvailable(KycEnvironment.LIVE))

            val live = didit.startSession(request.copy(environment = KycEnvironment.LIVE))
            assertIs<IntegrationError.NotConfigured>((live as Resource.Error).error)
            val liveDecision = didit.decision(KycEnvironment.LIVE, sessionId)
            assertIs<IntegrationError.NotConfigured>((liveDecision as Resource.Error).error)
            val noWorkflow = didit.startSession(request.copy(workflowId = " "))
            assertIs<IntegrationError.Rejected>((noWorkflow as Resource.Error).error)
            val notAnId = didit.decision(KycEnvironment.SANDBOX, "../admin")
            assertIs<IntegrationError.Rejected>((notAnId as Resource.Error).error)

            assertTrue(clients.requests.isEmpty())
        }

    @Test
    fun `didit's refusals and outages are told apart`() =
        runBlocking<Unit> {
            status = 400
            reply = """{"workflow_id":["Invalid workflow_id."]}"""
            assertIs<IntegrationError.Rejected>((didit.startSession(request) as Resource.Error).error)

            for (code in listOf(401, 403)) {
                status = code
                reply = """{"detail":"You do not have permission to perform this action."}"""
                val refused = (didit.startSession(request) as Resource.Error).error
                assertFalse(assertIs<IntegrationError.Unavailable>(refused).isTransient, "our key was refused")
            }
            for (code in listOf(429, 500, 503)) {
                status = code
                assertIs<IntegrationError.Unavailable>((didit.startSession(request) as Resource.Error).error)
            }
            status = 201
            reply = """{"session_id":"","url":""}"""
            assertIs<IntegrationError.Unavailable>((didit.startSession(request) as Resource.Error).error)
            reply = "<html>not json</html>"
            assertIs<IntegrationError.Unavailable>((didit.startSession(request) as Resource.Error).error)
            down = true
            assertIs<IntegrationError.Unavailable>((didit.startSession(request) as Resource.Error).error)
        }

    @Test
    fun `the names of the fields a refusal complains about are logged, never their values`() {
        assertEquals("workflow_id", DiditKyc.refusedFields("""{"workflow_id":["Invalid workflow_id."]}"""))
        assertEquals(
            "vendor_data,detail",
            DiditKyc.refusedFields("""{"vendor_data":["This user has been blocked."],"detail":"x"}"""),
        )
        assertEquals("not given", DiditKyc.refusedFields("<html>"))
    }

    @Test
    fun `the decision is read from the V3 endpoint with the environment's key`() =
        runBlocking<Unit> {
            status = 200
            reply = """{"session_id":"$sessionId","status":"In Progress","vendor_data":"user:42"}"""

            val read = (didit.decision(KycEnvironment.SANDBOX, sessionId) as Resource.Success).data

            assertEquals(KycStatus.PENDING, read.status)
            assertEquals("user:42", read.subject)
            val sent = clients.requests.first()
            assertEquals(HttpMethod.Get, sent.method)
            assertEquals("https://verification.didit.me/v3/session/$sessionId/decision/", sent.url.toString())
            assertEquals("sandbox-key", sent.headers["x-api-key"])
        }

    private fun submitted(didits: String, flowBody: String = flow): Boolean? {
        flow = flowBody
        clients.requests.clear()
        val read = decision("""{"session_id":"$sessionId","status":"$didits","vendor_data":"user:42"}""")
        return (read as Resource.Success).data.isSubmitted
    }

    private fun finished(at: String? = "2026-11-23T09:15:00Z", inFlow: Boolean? = false) =
        """{"session_id":"$sessionId","person_in_flow":$inFlow,"flow_finished_at":${at?.let { "\"$it\"" }},""" +
            """"overrides":[{"id":"9b2f4c1e-8d3a-4e5f-a6b7-c8d9e0f1a2b3","state":"applied"}]}"""

    @Test
    fun `an undecided session is submitted once didit says the person finished the flow`() {
        assertEquals(false, submitted("In Progress"), "still in the flow")
        val asked = clients.last
        assertEquals(HttpMethod.Get, asked.method)
        assertEquals(
            "https://verification.didit.me/v3/session/$sessionId/features/overrides/",
            asked.url.toString(),
        )
        assertEquals("sandbox-key", asked.headers["x-api-key"])

        assertEquals(true, submitted("In Progress", finished()), "finished: didit is deciding")
        assertEquals(true, submitted("Resubmitted", finished()), "the steps asked for again are done")
        assertEquals(false, submitted("Resubmitted", finished(at = null, inFlow = true)), "redoing them")

        // Both halves must agree: a finish time alone, or leaving the flow with none, is not "submitted".
        assertEquals(false, submitted("In Progress", finished(inFlow = true)))
        assertEquals(false, submitted("In Progress", finished(at = null)))
    }

    @Test
    fun `the flow is asked about only where the answer can be yes`() {
        assertEquals(false, submitted("Not Started", finished()), "nobody opened it")
        assertEquals(1, clients.requests.size, "the decision alone")

        for (decided in listOf("Approved", "Declined", "In Review", "Expired", "Abandoned", "Kyc Expired")) {
            assertNull(submitted(decided, finished()), decided)
            assertEquals(1, clients.requests.size, decided)
        }
        assertNull(submitted("Awaiting User", finished()), "a business session's wait: never ours")
    }

    @Test
    fun `when the flow can't be read the decision stands and says nothing about it`() {
        flowStatus = 403
        assertNull(submitted("In Progress", """{"detail":"You do not have permission to perform this action."}"""))

        flowStatus = 500
        assertNull(submitted("In Progress", "oops"))

        flowStatus = 200
        assertNull(submitted("In Progress", """{"session_id":"$sessionId","overrides":[]}"""), "didit didn't say")
        assertNull(submitted("In Progress", "<html>"), "unreadable")
        assertEquals(KycStatus.PENDING, statusOf("In Progress"), "the decision itself is still read")
    }

    @Test
    fun `didit's statuses are exact strings with spaces, each read into ours`() {
        assertEquals(KycStatus.PENDING, statusOf("Not Started"))
        assertEquals(KycStatus.PENDING, statusOf("In Progress"))
        assertEquals(KycStatus.PENDING, statusOf("Awaiting User"))
        assertEquals(KycStatus.RESUBMIT, statusOf("Resubmitted"))
        assertEquals(KycStatus.IN_REVIEW, statusOf("In Review"))
        assertEquals(KycStatus.APPROVED, statusOf("Approved"))
        assertEquals(KycStatus.DECLINED, statusOf("Declined"))
        assertEquals(KycStatus.EXPIRED, statusOf("Expired"))
        assertEquals(KycStatus.EXPIRED, statusOf("Abandoned"))
        assertEquals(KycStatus.DOCUMENT_EXPIRED, statusOf("Kyc Expired"))

        // Anything else is not a status we know: never guessed at (`IN_REVIEW`, `approved`, a new one).
        for (unknown in listOf("IN_REVIEW", "approved", "in review", "Kyc expired", "Paused", "")) {
            val read = decision("""{"session_id":"$sessionId","status":"$unknown"}""")
            assertIs<IntegrationError.Unavailable>((read as Resource.Error).error, unknown)
        }
    }

    @Test
    fun `an approved decision carries the document's country, type and number, from the plural V3 array`() {
        val approved =
            decision(
                """{"session_id":"$sessionId","status":"Approved","vendor_data":"user:42","features":["ID_VERIFICATION"],
                   "id_verifications":[
                     {"node_id":"id_1","status":"Declined","document_type":"Passport","document_number":"OLD1","issuing_state":"KEN"},
                     {"node_id":"id_2","status":"Approved","document_type":"Identity Card","document_number":"12345678",
                      "issuing_state":"KEN","first_name":"Amina","last_name":"Otieno","date_of_birth":"1990-01-01","warnings":[]}],
                   "liveness_checks":[{"status":"Approved"}],"face_matches":[{"status":"Approved"}]}""",
            )

        val read = (approved as Resource.Success).data
        assertEquals(KycStatus.APPROVED, read.status)
        val document = read.document!!
        assertEquals(
            Triple("KEN", "Identity Card", "12345678"),
            Triple(document.issuingCountry, document.type, document.number),
        )
        assertFalse("12345678" in document.toString() || "12345678" in read.toString(), "a document never prints")
        assertNull(read.declineReason)
    }

    @Test
    fun `an approved decision whose document can't be read has none, and the V2 singular field is never used`() {
        val noNumber =
            decision(
                """{"session_id":"$sessionId","status":"Approved",
                   "id_verifications":[{"status":"Approved","document_type":"Identity Card","issuing_state":"KEN"}]}""",
            )
        assertNull((noNumber as Resource.Success).data.document)

        val legacy =
            decision(
                """{"session_id":"$sessionId","status":"Approved",
                   "id_verification":{"status":"Approved","document_type":"Passport","document_number":"X1","issuing_state":"KEN"}}""",
            )
        assertNull((legacy as Resource.Success).data.document)

        val pending =
            decision(
                """{"session_id":"$sessionId","status":"In Review",
                   "id_verifications":[{"status":"Approved","document_type":"Passport","document_number":"X1","issuing_state":"KEN"}]}""",
            )
        assertNull((pending as Resource.Success).data.document, "a document is only handed over with an approval")
    }

    @Test
    fun `a declined decision says which check declined it, in our words`() {
        fun reason(features: String) =
            (decision("""{"session_id":"$sessionId","status":"Declined",$features}""") as Resource.Success)
                .data.declineReason

        assertEquals(
            KycDeclineReason.DOCUMENT_EXPIRED,
            reason(
                """"id_verifications":[{"status":"Declined","warnings":[{"risk":"DOCUMENT_EXPIRED","log_type":"error"}]}]""",
            ),
        )
        assertEquals(
            KycDeclineReason.DOCUMENT,
            reason(""""id_verifications":[{"status":"Declined","warnings":[{"risk":"MRZ_VALIDATION_FAILED"}]}]"""),
        )
        assertEquals(
            KycDeclineReason.LIVENESS,
            reason(""""id_verifications":[{"status":"Approved"}],"liveness_checks":[{"status":"Declined"}]"""),
        )
        assertEquals(
            KycDeclineReason.FACE_MATCH,
            reason(""""liveness_checks":[{"status":"Approved"}],"face_matches":[{"status":"Declined"}]"""),
        )
        assertEquals(KycDeclineReason.SCREENING, reason(""""aml_screenings":[{"status":"Declined"}]"""))
        assertEquals(KycDeclineReason.OTHER, reason(""""ip_analyses":[{"status":"Declined"}]"""))
    }

    @Test
    fun `a session didit doesn't know is refused, and an outage is unavailable`() =
        runBlocking<Unit> {
            status = 404
            reply = """{"detail":"Not found."}"""
            assertIs<IntegrationError.Rejected>(
                (didit.decision(KycEnvironment.SANDBOX, sessionId) as Resource.Error).error,
            )

            status = 502
            assertIs<IntegrationError.Unavailable>(
                (didit.decision(KycEnvironment.SANDBOX, sessionId) as Resource.Error).error,
            )
        }
}
