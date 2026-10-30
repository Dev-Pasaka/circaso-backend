package com.circaso.calls.domain.support

import com.circaso.calls.domain.errors.CallsError
import com.circaso.shared.Resource

/** Any SFU failure, whatever the vendor said, is `calls.media_unavailable`: the phone can only try again. */
fun <T> Resource<T>.orMediaUnavailable(): Resource<T> =
    when (this) {
        is Resource.Success -> this
        is Resource.Error -> Resource.error(CallsError.MediaUnavailable, cause)
    }
