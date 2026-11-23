package com.circaso.integrations.impl.didit

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.KycDecision
import com.circaso.integrations.api.KycDeclineReason
import com.circaso.integrations.api.KycDocument
import com.circaso.integrations.api.KycEnvironment
import com.circaso.integrations.api.KycProvider
import com.circaso.integrations.api.KycSession
import com.circaso.integrations.api.KycSessionRequest
import com.circaso.integrations.api.KycStatus
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** One Didit application (sandbox or live): the client that reaches it and the key that speaks for it. */
internal class DiditAccount(val http: HttpClient, val apiKey: String) {
    override fun toString(): String = "DiditAccount(***)"
}

/**
 * Didit (docs.didit.me) for the ramp's identity checks, over the shared `HttpClients` clients, base
 * `https://verification.didit.me`, the key in `x-api-key`. Didit has two applications, a sandbox whose checks are
 * simulated and a live one, each with its own key; both can be configured at once (as the two Circle accounts are),
 * and an environment with no key answers `NotConfigured` instead of failing the server's start.
 *
 * - **Start:** `POST /v3/session/` with the workflow, our reference as `vendor_data`, and where the hosted page
 *   returns to. Didit keeps one unfinished session per workflow and reference, so asking again returns the open one.
 *   The answer's `session_token` starts Didit's native SDK; `url` is the hosted page.
 * - **Decision:** `GET /v3/session/{id}/decision/`, the V3 shape. Statuses are Didit's exact strings, with spaces.
 * - **Submitted:** the decision has no word for "the person finished and Didit is still deciding": that session is
 *   `In Progress` like one the person left halfway. `GET /v3/session/{id}/features/overrides/` says which
 *   (`person_in_flow`, `flow_finished_at`), so it is asked for an `In Progress` or `Resubmitted` session only. When
 *   it can't be read the decision stands and says nothing about it.
 *
 * Bodies are never logged in either direction: a decision carries a person's name, face and document. Log lines name
 * the operation, the environment and a status code, and for a refusal the names of the fields Didit complained about.
 */
internal class DiditKyc(private val accounts: Map<KycEnvironment, DiditAccount>) : KycProvider {
    private val log = LoggerFactory.getLogger(DiditKyc::class.java)

    override fun isAvailable(environment: KycEnvironment): Boolean = environment in accounts

    override suspend fun startSession(request: KycSessionRequest): Resource<KycSession> {
        val account = accounts[request.environment] ?: return notConfigured(request.environment)
        if (request.workflowId.isBlank()) return rejected("No workflow was named for the check.")
        val body =
            CreateSessionBody(
                workflowId = request.workflowId,
                vendorData = request.subject,
                callback = request.callbackUrl,
                sessionExpirationTime = request.validFor?.seconds?.coerceIn(MIN_VALIDITY_SECONDS, MAX_VALIDITY_SECONDS),
            )
        return call("create_session", request.environment) {
            account.http.post("/v3/session/") {
                operation("create_session")
                header(API_KEY_HEADER, account.apiKey)
                contentType(ContentType.Application.Json)
                setBody(diditJson.encodeToString(CreateSessionBody.serializer(), body))
            }
        }.flatMapBody("create_session", request.environment) { text ->
            val reply = diditJson.decodeFromString(CreateSessionReply.serializer(), text)
            if (reply.sessionId.isEmpty() || reply.sessionToken.isEmpty() || reply.url.isEmpty()) {
                null
            } else {
                KycSession(reply.sessionId, reply.sessionToken, reply.url, instantOrNull(reply.expiresAt))
            }
        }
    }

    override suspend fun decision(environment: KycEnvironment, sessionId: String): Resource<KycDecision> {
        val account = accounts[environment] ?: return notConfigured(environment)
        // Didit's router answers anything but a UUID with an HTML page, not its JSON: refuse it here.
        if (runCatching { UUID.fromString(sessionId) }.isFailure) return rejected("That isn't a session id.")
        val read =
            call("session_decision", environment) {
                account.http.get("/v3/session/$sessionId/decision/") {
                    operation("session_decision")
                    header(API_KEY_HEADER, account.apiKey)
                }
            }.flatMapBody("session_decision", environment) { text ->
                diditJson.decodeFromString(DecisionReply.serializer(), text).takeIf { statusOf(it.status) != null }
            }
        val reply =
            when (read) {
                is Resource.Error -> return read
                is Resource.Success -> read.data
            }
        val status = statusOf(reply.status) ?: return Resource.error(IntegrationError.Unavailable(VENDOR))
        return Resource.success(
            KycDecision(
                status = status,
                subject = reply.vendorData?.takeIf { it.isNotEmpty() },
                document = if (status == KycStatus.APPROVED) documentOf(reply) else null,
                declineReason = if (status == KycStatus.DECLINED) declineReasonOf(reply) else null,
                isSubmitted = isSubmitted(account, environment, sessionId, reply.status),
            ),
        )
    }

    /**
     * Whether the person has finished the flow of a session Didit hasn't decided: asked only where the answer can be
     * yes. A session nobody opened is not submitted; a decided one has nothing to say (null). Null too when Didit
     * can't be asked or doesn't say: never a guess.
     */
    private suspend fun isSubmitted(
        account: DiditAccount,
        environment: KycEnvironment,
        sessionId: String,
        status: String,
    ): Boolean? {
        if (status == NOT_STARTED) return false
        if (status != IN_PROGRESS && status != RESUBMITTED) return null
        val read =
            call("session_flow", environment) {
                account.http.get("/v3/session/$sessionId/features/overrides/") {
                    operation("session_flow")
                    header(API_KEY_HEADER, account.apiKey)
                }
            }.flatMapBody("session_flow", environment) { text ->
                diditJson.decodeFromString(FlowReply.serializer(), text)
            }
        val flow = (read as? Resource.Success)?.data ?: return null
        val isInFlow = flow.personInFlow ?: return null
        return !isInFlow && !flow.flowFinishedAt.isNullOrBlank()
    }

    /** The status and body of an answer, or the failure when Didit couldn't be reached. */
    private suspend fun call(
        operation: String,
        environment: KycEnvironment,
        send: suspend () -> HttpResponse,
    ): Resource<Pair<Int, String>> =
        try {
            val response = send()
            Resource.success(response.status.value to response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: CircuitOpenException) {
            Resource.error(IntegrationError.Unavailable(VENDOR, isTransient = false), e)
        } catch (e: Exception) {
            log.warn(
                "didit unreachable: operation={} environment={} error={}",
                operation,
                environment,
                e::class.simpleName,
            )
            Resource.error(IntegrationError.Unavailable(VENDOR), e)
        }

    /** Reads a 2xx body with [read] (null or unreadable: `Unavailable`); maps any other status to our errors. */
    private fun <T> Resource<Pair<Int, String>>.flatMapBody(
        operation: String,
        environment: KycEnvironment,
        read: (String) -> T?,
    ): Resource<T> {
        val (status, text) =
            when (this) {
                is Resource.Error -> return this
                is Resource.Success -> data
            }
        return when {
            status in SUCCESS -> {
                runCatching { read(text) }.getOrNull()?.let { Resource.success(it) }
                    ?: unavailable(operation, environment, status, isTransient = true)
            }

            // Our own key was refused: nothing a person can fix, and asking again changes nothing.
            status == UNAUTHORIZED || status == FORBIDDEN -> {
                unavailable(operation, environment, status, isTransient = false)
            }

            status == TOO_MANY || status >= SERVER_ERROR -> {
                unavailable(operation, environment, status, isTransient = true)
            }

            else -> {
                log.warn(
                    "didit refused: operation={} environment={} status={} fields={}",
                    operation,
                    environment,
                    status,
                    refusedFields(text),
                )
                rejected("The identity provider refused the request.")
            }
        }
    }

    private fun <T> unavailable(
        operation: String,
        environment: KycEnvironment,
        status: Int,
        isTransient: Boolean,
    ): Resource<T> {
        log.warn("didit unavailable: operation={} environment={} status={}", operation, environment, status)
        return Resource.error(IntegrationError.Unavailable(VENDOR, isTransient))
    }

    private fun <T> notConfigured(environment: KycEnvironment): Resource<T> =
        Resource.error(IntegrationError.NotConfigured("Didit's ${environment.name.lowercase()} environment"))

    private fun <T> rejected(message: String): Resource<T> = Resource.error(IntegrationError.Rejected(message))

    internal companion object {
        const val BASE_URL = "https://verification.didit.me"
        const val VENDOR = "Didit"
        const val API_KEY_HEADER = "x-api-key"

        /** The bounds Didit accepts for `session_expiration_time`: an hour to four weeks. */
        private const val MIN_VALIDITY_SECONDS = 3_600L
        private const val MAX_VALIDITY_SECONDS = 2_419_200L
        private val SUCCESS = 200..299
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val TOO_MANY = 429
        private const val SERVER_ERROR = 500
        private const val MAX_FIELDS = 8
        private val FIELD_NAME = Regex(""""([A-Za-z_]{1,40})"\s*:""")

        /**
         * Didit's session statuses are exact strings with spaces (docs.didit.me → Verification statuses). `Abandoned`
         * (started, never finished) ends like `Expired`: a new session is needed. `Awaiting User` is a business
         * session's wait, which the ramp never starts, and reads as pending.
         */
        internal fun statusOf(status: String): KycStatus? =
            when (status) {
                NOT_STARTED, IN_PROGRESS, "Awaiting User" -> KycStatus.PENDING
                RESUBMITTED -> KycStatus.RESUBMIT
                "In Review" -> KycStatus.IN_REVIEW
                "Approved" -> KycStatus.APPROVED
                "Declined" -> KycStatus.DECLINED
                "Expired", "Abandoned" -> KycStatus.EXPIRED
                "Kyc Expired" -> KycStatus.DOCUMENT_EXPIRED
                else -> null
            }

        /** The approved document: the first approved ID check that has all three of country, type and number. */
        private fun documentOf(reply: DecisionReply): KycDocument? =
            reply.idVerifications
                .orEmpty()
                .sortedByDescending { it.status == APPROVED }
                .firstNotNullOfOrNull { check ->
                    val country = check.issuingState?.takeIf { it.isNotBlank() }
                    val type = check.documentType?.takeIf { it.isNotBlank() }
                    val number = check.documentNumber?.takeIf { it.isNotBlank() }
                    if (country == null || type == null || number == null) null else KycDocument(country, type, number)
                }

        /** Which check declined the session, as far as a person may be told; never Didit's own wording. */
        private fun declineReasonOf(reply: DecisionReply): KycDeclineReason {
            val documents = reply.idVerifications.orEmpty()
            val isExpired = documents.any { check -> check.warnings.orEmpty().any { it.risk == DOCUMENT_EXPIRED } }
            return when {
                isExpired -> KycDeclineReason.DOCUMENT_EXPIRED
                documents.any { it.status == DECLINED } -> KycDeclineReason.DOCUMENT
                reply.livenessChecks.orEmpty().any { it.status == DECLINED } -> KycDeclineReason.LIVENESS
                reply.faceMatches.orEmpty().any { it.status == DECLINED } -> KycDeclineReason.FACE_MATCH
                reply.amlScreenings.orEmpty().any { it.status == DECLINED } -> KycDeclineReason.SCREENING
                else -> KycDeclineReason.OTHER
            }
        }

        /**
         * The names of the fields a `400` complains about (`workflow_id`, `vendor_data`, `callback`, `detail`), for
         * the log: enough to tell an operator what to fix. Names only, never a value.
         */
        internal fun refusedFields(body: String): String =
            FIELD_NAME
                .findAll(body)
                .map { it.groupValues[1] }
                .distinct()
                .take(MAX_FIELDS)
                .joinToString(",")
                .ifEmpty { "not given" }

        /** Didit's times are ISO 8601 with an offset (`…Z` or `+00:00`). */
        private fun instantOrNull(text: String?): Instant? =
            text?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

        private const val NOT_STARTED = "Not Started"
        private const val IN_PROGRESS = "In Progress"
        private const val RESUBMITTED = "Resubmitted"
        private const val APPROVED = "Approved"
        private const val DECLINED = "Declined"
        private const val DOCUMENT_EXPIRED = "DOCUMENT_EXPIRED"
    }
}
