package com.circaso.integrations.impl.dev

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
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests: an in-memory KYC provider that records every session it was asked to start. A session is `PENDING` until
 * the test says what the provider decided ([approve], [decline], [set]). Like Didit, it keeps one unfinished session
 * per environment, workflow and subject: asking again returns it. While [failure] is set every call fails with it;
 * [available] are the environments that have keys.
 */
class FakeKyc(private val clock: Clock = Clock.systemUTC()) : KycProvider {
    val started: MutableList<KycSessionRequest> = CopyOnWriteArrayList()
    val decisionQueries: MutableList<String> = CopyOnWriteArrayList()
    val available: MutableSet<KycEnvironment> =
        ConcurrentHashMap.newKeySet<KycEnvironment>().also { it += KycEnvironment.entries }

    @Volatile
    var failure: IntegrationError? = null

    private class Kept(
        val request: KycSessionRequest,
        val session: KycSession,
        @Volatile var decision: KycDecision,
    )

    /** In the order they were started. */
    private val sessions = CopyOnWriteArrayList<Kept>()

    /** The session last started for [subject]. */
    fun sessionOf(subject: String): KycSession = sessions.last { it.request.subject == subject }.session

    fun approve(sessionId: String, document: KycDocument? = KycDocument("KEN", "Identity Card", "12345678")) {
        set(sessionId, KycStatus.APPROVED, document = document)
    }

    fun decline(sessionId: String, reason: KycDeclineReason = KycDeclineReason.DOCUMENT) {
        set(sessionId, KycStatus.DECLINED, declineReason = reason)
    }

    /** What the provider reports for [sessionId] from now on. [subject] defaults to the one it was started with. */
    fun set(
        sessionId: String,
        status: KycStatus,
        document: KycDocument? = null,
        declineReason: KycDeclineReason? = null,
        subject: String? = kept(sessionId)?.request?.subject,
    ) {
        checkNotNull(kept(sessionId)) { "no such session" }.decision =
            KycDecision(status, subject, document, declineReason)
    }

    override fun isAvailable(environment: KycEnvironment): Boolean = environment in available

    override suspend fun startSession(request: KycSessionRequest): Resource<KycSession> {
        failure?.let { return Resource.error(it) }
        if (request.environment !in available) return notConfigured(request.environment)
        if (request.workflowId.isBlank()) return Resource.error(IntegrationError.Rejected("No workflow."))
        started += request
        open(request)?.let { return Resource.success(it.session) }
        val id = UUID.randomUUID().toString()
        val token = "token-${sessions.size + 1}"
        val session = KycSession(id, token, "https://verify.test/session/$token", expiryOf(request))
        sessions += Kept(request, session, KycDecision(KycStatus.PENDING, request.subject))
        return Resource.success(session)
    }

    override suspend fun decision(environment: KycEnvironment, sessionId: String): Resource<KycDecision> {
        failure?.let { return Resource.error(it) }
        if (environment !in available) return notConfigured(environment)
        decisionQueries += sessionId
        val kept = kept(sessionId)?.takeIf { it.request.environment == environment }
        return kept?.let { Resource.success(it.decision) }
            ?: Resource.error(IntegrationError.Rejected("No such session."))
    }

    private fun kept(sessionId: String): Kept? = sessions.firstOrNull { it.session.sessionId == sessionId }

    private fun open(request: KycSessionRequest): Kept? =
        sessions.firstOrNull {
            it.request.environment == request.environment &&
                it.request.subject == request.subject &&
                it.request.workflowId == request.workflowId &&
                it.decision.status in UNFINISHED
        }

    private fun expiryOf(request: KycSessionRequest) = clock.instant().plus(request.validFor ?: DEFAULT_VALIDITY)

    private fun <T> notConfigured(environment: KycEnvironment): Resource<T> =
        Resource.error(IntegrationError.NotConfigured("The fake KYC provider's ${environment.name.lowercase()} side"))

    private companion object {
        val UNFINISHED = setOf(KycStatus.PENDING, KycStatus.RESUBMIT)
        val DEFAULT_VALIDITY: Duration = Duration.ofDays(7)
    }
}

/**
 * Local runs: nobody is checked. A session needs nothing from the person (no token, no link) and is approved the
 * moment it is read, so a developer's machine goes from unverified to verified and on to buying, end to end. It keeps
 * nothing: a session's id is derived from who it is for, and its made-up document from that id, so the same person
 * always "presents" the same document and two people never the same one. It prints that it was asked, never who.
 *
 * Offered only where the server runs in the local environment: anywhere else it would wave anyone through.
 */
class LogKyc : KycProvider {
    private val log = LoggerFactory.getLogger(LogKyc::class.java)

    override fun isAvailable(environment: KycEnvironment): Boolean = true

    override suspend fun startSession(request: KycSessionRequest): Resource<KycSession> {
        val id = UUID.nameUUIDFromBytes("${request.environment}|${request.subject}".toByteArray()).toString()
        log.info("kyc (log): a check was started in {}, reported as approved", request.environment)
        return Resource.success(KycSession(id, token = null, url = null, expiresAt = null))
    }

    override suspend fun decision(environment: KycEnvironment, sessionId: String): Resource<KycDecision> =
        Resource.success(
            KycDecision(
                status = KycStatus.APPROVED,
                subject = null,
                document = KycDocument(COUNTRY, DOCUMENT_TYPE, sessionId.filter { it.isLetterOrDigit() }.uppercase()),
            ),
        )

    private companion object {
        const val COUNTRY = "KEN"
        const val DOCUMENT_TYPE = "Local stand-in"
    }
}
