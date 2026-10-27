package com.circaso.web

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** The one shape the app receives, success or failure (Architecture → Results, errors and responses). */
@Serializable
data class DefaultResponse<T>(
    /** Always equals the real HTTP status: there is no `200` with `status: false`. */
    val httpStatusCode: Int = HttpStatusCode.OK.value,
    val status: Boolean = false,
    val message: String = "",
    /** Error code (`identity.phone_taken`); null on success. */
    val code: String? = null,
    /** `retryAfterSeconds` and field errors; null on success. */
    val details: ErrorDetailsDto? = null,
    val requestId: String? = null,
    val data: T? = null,
)

@Serializable
data class ErrorDetailsDto(val retryAfterSeconds: Long? = null, val fields: List<FieldErrorDto> = emptyList())

/** One wrong field of the request (decision 2026-10-27: a list of these, not a map of field to problem). */
@Serializable
data class FieldErrorDto(val field: String, val problem: String)

fun ErrorDetails.toDto(): ErrorDetailsDto =
    ErrorDetailsDto(
        retryAfterSeconds = retryAfterSeconds,
        fields = fields.map { FieldErrorDto(it.field, it.problem) },
    )

/** The status comes from the error's kind, never chosen per route. */
fun ErrorKind.httpStatus(): HttpStatusCode =
    when (this) {
        ErrorKind.INVALID -> HttpStatusCode.BadRequest
        ErrorKind.UNAUTHORIZED -> HttpStatusCode.Unauthorized
        ErrorKind.FORBIDDEN -> HttpStatusCode.Forbidden
        ErrorKind.NOT_FOUND -> HttpStatusCode.NotFound
        ErrorKind.CONFLICT -> HttpStatusCode.Conflict
        ErrorKind.RATE_LIMITED -> HttpStatusCode.TooManyRequests
        ErrorKind.UPSTREAM -> HttpStatusCode.BadGateway
        ErrorKind.UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
        ErrorKind.INTERNAL -> HttpStatusCode.InternalServerError
    }

fun <T, R> Resource<T>.toDefaultResponse(
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    transform: (T) -> R,
): DefaultResponse<R> =
    when (this) {
        is Resource.Success -> DefaultResponse(successStatus.value, true, message, data = transform(data))
        is Resource.Error -> error.toDefaultResponse()
    }

/** The failure envelope for an error, whatever the success type would have been. */
fun <R> DomainError.toDefaultResponse(): DefaultResponse<R> =
    DefaultResponse(
        httpStatusCode = kind.httpStatus().value,
        status = false,
        message = message,
        code = code,
        details = details?.toDto(),
    )
