package com.circaso.integrations.api

import com.circaso.shared.Resource
import java.time.Duration
import java.time.Instant

/**
 * Identity verification (KYC) for the ramp (decision 2026-11-22): Didit first. A provider runs the check with the
 * person (a document, a liveness selfie, a face match, and screening where the workflow has it) and keeps the record;
 * we keep only what it decided. Nothing here is a vendor's shape, so another provider is a new implementation.
 *
 * A provider has two environments ([KycEnvironment]): a sandbox whose checks are simulated and a live one that
 * verifies real people. Which one a ramp network uses is a setting; an environment whose keys aren't on this server
 * answers [IntegrationError.NotConfigured], never an exception.
 */
interface KycProvider {
    /** Whether this server can run checks in [environment] (its key is present). */
    fun isAvailable(environment: KycEnvironment): Boolean

    /**
     * Starts a check for [KycSessionRequest.subject], or returns the one the provider still has open for the same
     * subject and workflow. Never retried by the client.
     */
    suspend fun startSession(request: KycSessionRequest): Resource<KycSession>

    /**
     * The provider's decision on a session as it stands now: the only thing a verification may rest on. A webhook is
     * a doorbell; this is the record.
     */
    suspend fun decision(environment: KycEnvironment, sessionId: String): Resource<KycDecision>
}

/** A provider's two sides: simulated checks that bill nothing, and real ones. */
enum class KycEnvironment {
    SANDBOX,
    LIVE,
    ;

    companion object {
        fun parse(raw: String?): KycEnvironment? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

/**
 * Every KYC provider this server offers, by name. Which one the ramp uses is not a start-up line: it is a setting
 * staff switch in the admin, per network (as the collections provider is), so integrations binds them all at once and
 * the ramp names the one it wants on each call.
 *
 * Real providers (`didit`) are always offered. `log`, which approves everybody at once, is offered only where the
 * server runs in the local environment: anywhere else it would wave anyone through. `fake` exists only where a test
 * builds it.
 */
interface KycProviders {
    /** The names offered here, real providers first. */
    val names: List<String>

    /** The provider called [name], or null when this server doesn't offer it. */
    fun named(name: String): KycProvider?

    companion object {
        const val DIDIT = "didit"
        const val LOG = "log"
    }
}

data class KycSessionRequest(
    val environment: KycEnvironment,
    /** Our own reference for who is being checked (`user:<id>`, `merchant:<id>`): echoed back in the decision. */
    val subject: String,
    /** The provider's id of the workflow to run (which checks, in which order). */
    val workflowId: String,
    /** Where the provider's hosted page sends the person when they finish; null when there is nowhere (the app). */
    val callbackUrl: String? = null,
    /** How long the session's link stays valid; null leaves the workflow's own setting. */
    val validFor: Duration? = null,
)

/**
 * An open check. [token] is what the provider's native SDK starts with and [url] its hosted page; both are null for a
 * provider that needs nothing from the person (the local stand-in), whose decision can be read at once. The string
 * form hides the token and the link.
 */
class KycSession(
    val sessionId: String,
    val token: String?,
    val url: String?,
    val expiresAt: Instant?,
) {
    override fun toString(): String = "KycSession($sessionId, ***)"
}

/** Where a session stands, in our words. */
enum class KycStatus {
    /** Not started, or the person is still going through it. */
    PENDING,

    /** A reviewer asked the person to redo some steps in the same session. */
    RESUBMIT,

    /** The provider's reviewers must decide. */
    IN_REVIEW,
    APPROVED,
    DECLINED,

    /** The session ran out before the person finished: a new one is needed. */
    EXPIRED,

    /** A session that was approved, whose document has since passed its own expiry date. */
    DOCUMENT_EXPIRED,
}

/** Why a check was declined, as far as a person may be told. Never the provider's wording. */
enum class KycDeclineReason {
    DOCUMENT,
    DOCUMENT_EXPIRED,
    LIVENESS,
    FACE_MATCH,

    /** A screening hit (sanctions, politically exposed people). Never said to the person as such. */
    SCREENING,
    OTHER,
}

/**
 * The document a person was approved with: what the ramp computes its identity key from, once, and never stores or
 * logs. The string form hides all of it.
 */
class KycDocument(val issuingCountry: String, val type: String, val number: String) {
    override fun toString(): String = "KycDocument(***)"
}

/**
 * A session as the provider reports it. [subject] is the reference the session was started with; [document] is set
 * for an approved session whose document could be read; [declineReason] for a declined one.
 */
class KycDecision(
    val status: KycStatus,
    val subject: String?,
    val document: KycDocument? = null,
    val declineReason: KycDeclineReason? = null,
) {
    override fun toString(): String = "KycDecision($status, ***)"
}

/** Implemented by the ramp, wired in `app`; integrations never depend on the ramp. */
interface KycNotifications {
    /**
     * The provider says the session [sessionId] in [environment] changed. Only the id is handed over: the listener
     * reads the decision from the provider itself before it acts, and ignores a session it never started or one that
     * belongs to another environment.
     */
    suspend fun onStatusChanged(environment: KycEnvironment, sessionId: String): Resource<Unit>
}
