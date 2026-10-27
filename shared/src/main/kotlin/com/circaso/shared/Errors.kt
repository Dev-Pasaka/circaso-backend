package com.circaso.shared

/** What went wrong, not which HTTP status: `shared-web` maps each kind to a status in one place. */
enum class ErrorKind { INVALID, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, RATE_LIMITED, UPSTREAM, INTERNAL }

interface DomainError {
    /** Stable, `module.reason` (`identity.phone_taken`); the app keys its copy on it. */
    val code: String

    /** Safe to show the user. */
    val message: String

    val kind: ErrorKind

    /** Optional extras the app can act on. */
    val details: ErrorDetails? get() = null
}

data class ErrorDetails(
    /** RATE_LIMITED: also sent as the `Retry-After` header. */
    val retryAfterSeconds: Long? = null,
    /** INVALID: each field that is wrong and what is wrong with it. */
    val fields: List<FieldError> = emptyList(),
)

/** One field of a request that is wrong: `{"field": "displayName", "problem": "required"}` on the wire. */
data class FieldError(val field: String, val problem: String)

/** For the rare spot where returning a `Resource.Error` is awkward; it reaches the app in the same envelope. */
class DomainException(val error: DomainError, cause: Throwable? = null) : RuntimeException(error.message, cause)

/**
 * Errors every module can return, with the shared codes from the Conventions table. A module's own
 * errors live in its `domain/errors`.
 */
sealed class SharedError(
    override val code: String,
    override val message: String,
    override val kind: ErrorKind,
    override val details: ErrorDetails? = null,
) : DomainError {
    /** A request that isn't shaped right: bad JSON, a missing field, a malformed value. Mappers return this. */
    class Malformed(fields: List<FieldError> = emptyList()) :
        SharedError(
            code = "request.malformed",
            message = "The request is not valid.",
            kind = ErrorKind.INVALID,
            details = fields.takeIf { it.isNotEmpty() }?.let { ErrorDetails(fields = it) },
        ) {
        /** The usual case: one field and what is wrong with it. */
        constructor(field: String, problem: String) : this(listOf(FieldError(field, problem)))
    }

    data object NotFound : SharedError("request.not_found", "That does not exist.", ErrorKind.NOT_FOUND)

    data object Internal : SharedError("internal.error", "Something went wrong", ErrorKind.INTERNAL)

    data object TokenMissing : SharedError("auth.token_missing", "Sign in to continue.", ErrorKind.UNAUTHORIZED)

    data object TokenInvalid : SharedError(
        "auth.token_invalid",
        "Your session is not valid. Sign in again.",
        ErrorKind.UNAUTHORIZED,
    )

    data object ScopeInsufficient : SharedError(
        "auth.scope_insufficient",
        "You can't do that with this session.",
        ErrorKind.FORBIDDEN,
    )

    /** `request.client_outdated` is sent with 426 by the gateway, which is not the status its kind maps to. */
    data object ClientOutdated : SharedError(
        "request.client_outdated",
        "Update Circaso to continue.",
        ErrorKind.INVALID,
    )

    class RateLimited(retryAfterSeconds: Long) :
        SharedError(
            code = "request.rate_limited",
            message = "Too many requests. Try again shortly.",
            kind = ErrorKind.RATE_LIMITED,
            details = ErrorDetails(retryAfterSeconds = retryAfterSeconds),
        )

    data object InProgress : SharedError(
        "request.in_progress",
        "That request is still being processed.",
        ErrorKind.CONFLICT,
    )

    data object IdempotencyKeyMissing : SharedError(
        "request.malformed",
        "This request needs an Idempotency-Key header.",
        ErrorKind.INVALID,
        ErrorDetails(fields = listOf(FieldError("Idempotency-Key", "required"))),
    )
}
