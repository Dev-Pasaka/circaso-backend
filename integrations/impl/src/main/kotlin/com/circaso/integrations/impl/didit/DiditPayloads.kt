package com.circaso.integrations.impl.didit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Didit's request and response bodies, as Didit names them (docs.didit.me, read 2026-11-22). They never leave this
// package. Answers are read leniently: Didit adds fields often, and every field we don't name is ignored. Nothing
// here is ever logged: a decision carries a person's name, face and document.

internal val diditJson =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

/** `POST /v3/session/`. The workflow decides which checks run; `vendor_data` is our own reference for the person. */
@Serializable
internal data class CreateSessionBody(
    @SerialName("workflow_id") val workflowId: String,
    @SerialName("vendor_data") val vendorData: String,
    val callback: String? = null,
    /** Seconds the session's link stays valid: 3 600 (an hour) to 2 419 200 (four weeks). */
    @SerialName("session_expiration_time") val sessionExpirationTime: Long? = null,
) {
    override fun toString(): String = "CreateSessionBody(***)"
}

/** The `201` of `POST /v3/session/`. The token is what the native SDK starts with; the hosted page is `url`. */
@Serializable
internal data class CreateSessionReply(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("session_token") val sessionToken: String = "",
    val url: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
) {
    override fun toString(): String = "CreateSessionReply(***)"
}

/**
 * `GET /v3/session/{id}/decision/`, the V3 shape: every feature's result is a plural array (never the V2 singular
 * fields). Only what a verification rests on is read: the session's status, our reference, and for the document its
 * issuing state, type and number. Names, dates of birth and images are never decoded.
 */
@Serializable
internal data class DecisionReply(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("session_kind") val sessionKind: String? = null,
    val status: String = "",
    @SerialName("vendor_data") val vendorData: String? = null,
    @SerialName("id_verifications") val idVerifications: List<IdVerification>? = null,
    @SerialName("liveness_checks") val livenessChecks: List<FeatureResult>? = null,
    @SerialName("face_matches") val faceMatches: List<FeatureResult>? = null,
    @SerialName("aml_screenings") val amlScreenings: List<FeatureResult>? = null,
) {
    override fun toString(): String = "DecisionReply(***)"
}

@Serializable
internal data class IdVerification(
    val status: String = "",
    @SerialName("document_type") val documentType: String? = null,
    @SerialName("document_number") val documentNumber: String? = null,
    /** ISO 3166-1 alpha-3 (`KEN`). */
    @SerialName("issuing_state") val issuingState: String? = null,
    val warnings: List<Warning>? = null,
) {
    override fun toString(): String = "IdVerification(***)"
}

/** One feature's result, of which only its own status is read. */
@Serializable
internal data class FeatureResult(val status: String = "")

/**
 * `GET /v3/session/{id}/features/overrides/`, of which only where the person is in the flow is read (docs.didit.me →
 * List Feature Overrides, read 2026-11-23): `person_in_flow` is true while the session is `Not Started`,
 * `In Progress` or `Resubmitted` and the person has not finished; `flow_finished_at` is when they last finished, null
 * while they are still in it (a resubmission included). The overrides themselves are never decoded.
 */
@Serializable
internal data class FlowReply(
    @SerialName("person_in_flow") val personInFlow: Boolean? = null,
    @SerialName("flow_finished_at") val flowFinishedAt: String? = null,
)

/** A risk signal on a feature: `risk` is Didit's stable code (`DOCUMENT_EXPIRED`). */
@Serializable
internal data class Warning(val risk: String = "")

/**
 * A webhook delivery's envelope: who sent what about which session. The `decision` it may carry is never read: the
 * ramp asks Didit for the decision itself before it acts.
 */
@Serializable
internal data class WebhookEnvelope(
    @SerialName("event_id") val eventId: String = "",
    @SerialName("webhook_type") val webhookType: String = "",
    /** Unix seconds when Didit dispatched this delivery; inside the signed body. */
    val timestamp: Long? = null,
    /** `live` or `sandbox`: which of our two Didit applications the delivery is from. */
    val environment: String = "",
    @SerialName("session_id") val sessionId: String = "",
    /** `business` on a business-verification session, which the ramp never starts. */
    @SerialName("session_kind") val sessionKind: String? = null,
) {
    override fun toString(): String = "WebhookEnvelope($webhookType)"
}
