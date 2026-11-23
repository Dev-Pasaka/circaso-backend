package com.circaso.ramp.domain.models

import com.circaso.integrations.api.KycEnvironment
import com.circaso.ramp.api.MerchantId
import com.circaso.shared.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Who a verification is about: a user of the app, or a partner merchant (an account of its own). */
enum class SubjectKind { USER, MERCHANT }

/**
 * One subject of identity verification. [reference] is what the provider is told and echoes back (`user:<id>`,
 * `merchant:<id>`): our own id, never anything personal.
 */
data class Subject(val kind: SubjectKind, val id: UUID) {
    val reference: String get() = "${kind.name.lowercase()}:$id"

    /** The user, when this is one. */
    val userId: UserId? get() = if (kind == SubjectKind.USER) UserId(id) else null

    companion object {
        fun user(userId: UserId) = Subject(SubjectKind.USER, userId.value)

        fun merchant(merchantId: MerchantId) = Subject(SubjectKind.MERCHANT, merchantId.value)
    }
}

/**
 * Where a verification stands. There is no `NONE` here: a subject who never started has no row. `PENDING` is a check
 * the provider hasn't decided: one the person still has to finish (or redo), or, once [Verification.isSubmitted], one
 * they finished that the provider is still working on. `IN_REVIEW` is one the provider's reviewers must decide.
 */
enum class VerificationStatus {
    PENDING,
    IN_REVIEW,
    VERIFIED,
    DECLINED,
    EXPIRED,
    ;

    /** Still waiting on the person or the provider: what the sweep re-reads. */
    val isOpen: Boolean get() = this == PENDING || this == IN_REVIEW

    /** Ended without a verification: the person may start again (within the daily cap). */
    val isRetryable: Boolean get() = this == DECLINED || this == EXPIRED
}

/**
 * Why a verification is where it is, as a code the app words itself. Never the provider's text and never anything
 * personal. [canRetry] is false where trying again can't help: the person has to talk to support.
 */
enum class VerificationReason(val code: String, val canRetry: Boolean = true) {
    /** The ID document couldn't be verified. */
    DOCUMENT("document"),

    /** The ID document has passed its expiry date. */
    DOCUMENT_EXPIRED("document_expired"),

    /** The provider approved, but the document's country, type or number couldn't be read: another document. */
    DOCUMENT_UNREADABLE("document_unreadable"),

    /** The selfie's liveness check failed. */
    LIVENESS("liveness"),

    /** The selfie didn't match the document's photo. */
    FACE_MATCH("face_match"),

    /** Declined for a reason a person isn't told (a screening hit): support, not another try. */
    NOT_APPROVED("not_approved", canRetry = false),

    /** Declined, and the provider named no check: another try may pass. */
    DECLINED("declined"),

    /** This identity already verified another account. */
    IDENTITY_IN_USE("identity_in_use", canRetry = false),

    /** This identity is barred from the ramp. */
    BLOCKED("blocked", canRetry = false),

    /** The check wasn't finished in time. */
    SESSION_EXPIRED("session_expired"),

    /** A reviewer asked for some steps to be done again, in the same check. */
    RESUBMISSION_REQUESTED("resubmission_requested"),
    ;

    companion object {
        fun of(code: String?): VerificationReason? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One subject's verification in one provider environment. [sessionToken] and [sessionUrl] are the open check's link,
 * kept only until it is verified; [identityKey] is the HMAC of the approved document, set with `VERIFIED` in the
 * live environment only (the sandbox reads the same sample document for everyone). [isSubmitted] is the provider
 * saying the person finished everything a `PENDING` check asked of them; it is false in every other status. The
 * string form hides the link and the key.
 */
data class Verification(
    val subject: Subject,
    val environment: KycEnvironment,
    val status: VerificationStatus,
    val reason: VerificationReason?,
    val isSubmitted: Boolean = false,
    /** The KYC provider that holds [sessionId]: its decision is asked there, whatever the settings say later. */
    val provider: String,
    val sessionId: String?,
    val sessionToken: String?,
    val sessionUrl: String?,
    val sessionExpiresAt: Instant?,
    val identityKey: String?,
    /** Sessions ever started. */
    val attempts: Int,
    /** The Nairobi day [attemptsOnDay] counts, null before the first. */
    val attemptDay: LocalDate?,
    val attemptsOnDay: Int,
    val startedAt: Instant?,
    /** When the provider was last asked about the session. */
    val checkedAt: Instant,
    val verifiedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** How many sessions were started on [day]. */
    fun attemptsOn(day: LocalDate): Int = if (attemptDay == day) attemptsOnDay else 0

    /**
     * The person has done their part and the provider hasn't decided (it is still working, or its reviewers are
     * looking): there is nothing for them to do but wait, so no check is started, continued or handed out.
     */
    val awaitsDecision: Boolean
        get() = status == VerificationStatus.IN_REVIEW || (status == VerificationStatus.PENDING && isSubmitted)

    /**
     * Whether the open check's link is for the person to use at [now]: there is one, it hasn't run out, and they
     * haven't already finished what it asks.
     */
    fun hasLiveLink(now: Instant): Boolean =
        status == VerificationStatus.PENDING &&
            !isSubmitted &&
            sessionId != null &&
            (sessionToken != null || sessionUrl != null) &&
            sessionExpiresAt?.isAfter(now) != false

    override fun toString(): String = "Verification($subject, $environment, $status)"
}

/**
 * Where a move takes a verification: its new status, the reason behind it, and for a `PENDING` one whether the person
 * has finished their part ([isSubmitted]).
 */
data class VerificationChange(
    val status: VerificationStatus,
    val reason: VerificationReason? = null,
    val isSubmitted: Boolean = false,
)

/** How marking a verification `VERIFIED` ended. */
sealed interface VerifiedOutcome {
    data class Moved(val verification: Verification) : VerifiedOutcome

    /** Already verified, or the row names another session now: nothing to do and nobody to tell. */
    data object AlreadyThere : VerifiedOutcome

    /** The identity already verified another subject of the same kind: one identity, one account. */
    data object IdentityInUse : VerifiedOutcome
}

/** The state the app shows: a verification's status, or `NONE` when the person never started. */
enum class KycState { NONE, PENDING, IN_REVIEW, VERIFIED, DECLINED, EXPIRED }

/** The open check's link, for the provider's SDK ([token]) or its hosted page ([url]). The string form hides both. */
class KycLink(val token: String?, val url: String?, val expiresAt: Instant?) {
    override fun toString(): String = "KycLink(***)"
}

/**
 * A subject's verification as the app and the portal are told it, so neither ever guesses: the [state], whether the
 * network [required] it, whether a check may be started or continued now ([canStart]), the [reason] behind a decline
 * or an expiry, and when the daily cap lifts ([retryAt]). [link] is set only by the calls that hand one out.
 *
 * [submitted] is what tells "finished, waiting for the answer" from "started, not finished", which are both
 * `PENDING`: the person has done their part and the provider hasn't decided. It is true for a submitted `PENDING` and
 * for `IN_REVIEW`, and then [canStart] is false and there is no [link].
 */
data class KycView(
    val state: KycState,
    val required: Boolean,
    val canStart: Boolean,
    val reason: VerificationReason?,
    val retryAt: Instant?,
    val link: KycLink? = null,
    val submitted: Boolean = false,
) {
    val isVerified: Boolean get() = state == KycState.VERIFIED

    /** Whether identity stands in the way of trading: required and not verified. */
    val blocksTrading: Boolean get() = required && !isVerified
}

/** Why someone is barred from the ramp. */
enum class BlockReason {
    /** Staff barred them. */
    OPS,

    /** They reversed a payment after the USDC was released. */
    REVERSAL,

    /** They verified as an identity that is barred. */
    IDENTITY,
}

/** One bar on the ramp: of a user ([userId]) or of a KYC identity ([identityKey]), exactly one of the two. */
data class Block(
    val blockId: UUID,
    val userId: UserId?,
    val identityKey: String?,
    val reason: BlockReason,
    /** What staff wrote; never personal data. */
    val note: String,
    /** On an identity's row: the user whose block it came from. */
    val sourceUserId: UserId?,
    val createdBy: String,
    val createdAt: Instant,
) {
    override fun toString(): String = "Block($blockId, $reason)"
}

/** A user's verification in every environment they have one, and their block if they are barred: staff's lookup. */
data class UserVerifications(val userId: UserId, val verifications: List<Verification>, val block: Block?)
