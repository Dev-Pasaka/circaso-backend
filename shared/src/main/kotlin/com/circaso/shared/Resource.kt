package com.circaso.shared

/**
 * What every use case and module contract returns. Expected failures are a [Error] carrying the module's
 * [DomainError]; exceptions are for bugs and outages.
 */
sealed class Resource<out T> {
    data class Success<T>(val data: T, val message: String = "Success") : Resource<T>()

    /** The [cause] is for logs only; it is never sent to the app. */
    data class Error(val error: DomainError, val cause: Throwable? = null) : Resource<Nothing>()

    companion object {
        fun <T> success(data: T, message: String = "Success"): Resource<T> = Success(data, message)
        fun error(error: DomainError, cause: Throwable? = null): Resource<Nothing> = Error(error, cause)
    }
}

inline fun <T, R> Resource<T>.map(transform: (T) -> R): Resource<R> = when (this) {
    is Resource.Success -> Resource.Success(transform(data), message)
    is Resource.Error -> this
}

inline fun <T, R> Resource<T>.flatMap(transform: (T) -> Resource<R>): Resource<R> = when (this) {
    is Resource.Success -> transform(data)
    is Resource.Error -> this
}

inline fun <T, R> Resource<T>.fold(onSuccess: (T) -> R, onError: (DomainError) -> R): R = when (this) {
    is Resource.Success -> onSuccess(data)
    is Resource.Error -> onError(error)
}

inline fun <T> Resource<T>.onSuccess(action: (T) -> Unit): Resource<T> = also { if (it is Resource.Success) action(it.data) }

inline fun <T> Resource<T>.onError(action: (DomainError) -> Unit): Resource<T> = also { if (it is Resource.Error) action(it.error) }

fun <T> Resource<T>.getOrNull(): T? = (this as? Resource.Success)?.data

/** Unwraps a success or throws a [DomainException] carrying the error, for the rare spot where returning is awkward. */
fun <T> Resource<T>.getOrThrow(): T = when (this) {
    is Resource.Success -> data
    is Resource.Error -> throw DomainException(error, cause)
}
