package com.circaso.identity.domain.models

import com.circaso.integrations.api.SocialProvider
import java.time.Instant
import java.util.UUID

/** Why someone left, chosen from a short fixed list on the website's deletion page (I17, 2026-11-12). */
enum class LeavingReason(val code: String) {
    NOT_USEFUL("not_useful"),
    PRIVACY("privacy"),
    TOO_MANY_APPS("too_many_apps"),
    SWITCHING("switching"),
    PROBLEMS("problems"),
    OTHER("other"),
    ;

    companion object {
        fun of(code: String): LeavingReason? = entries.firstOrNull { it.code == code.trim().lowercase() }
    }
}

/**
 * What a person said when they asked for their account to go. Kept apart from the account and never linked to it, so
 * it outlives the deletion and staff can read why people leave without it pointing at anyone.
 */
data class DeletionFeedback(
    /** Time-ordered UUID; null until stored. */
    val feedbackId: UUID?,
    val reason: LeavingReason,
    val details: String?,
    /** Where the request came from: `web` for the website's deletion page. */
    val source: String,
    val createdAt: Instant,
)

/** I17: the website's deletion request, proved by a Google or Apple ID token for one of the account's logins. */
data class WebDeletionCommand(
    val provider: SocialProvider,
    val idToken: String,
    val nonce: String,
    val reason: LeavingReason,
    val details: String?,
) {
    companion object {
        const val MAX_DETAILS = 500
    }
}

/** I17's answer. Each case says only what the page needs to show; none names the account. */
sealed interface WebDeletionOutcome {
    /** No account has this Google or Apple login. */
    data object NoAccount : WebDeletionOutcome

    data class Scheduled(val deleteAfter: Instant) : WebDeletionOutcome

    /** It was already asked for; nothing changed. */
    data class AlreadyScheduled(val deleteAfter: Instant?) : WebDeletionOutcome

    /** A guard or the account's state refused it; [code] is that refusal's own error code. */
    data class Refused(val code: String, val message: String) : WebDeletionOutcome
}
