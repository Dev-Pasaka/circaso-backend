package com.circaso.gateway

import com.circaso.shared.SharedError
import com.circaso.web.respondError
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.compression.appliedDecoders
import io.ktor.utils.io.ClosedByteChannelException
import java.io.EOFException
import java.util.zip.DataFormatException
import java.util.zip.ZipException

private const val MAX_CAUSE_DEPTH = 8

/**
 * A `Content-Encoding: gzip` or `deflate` request body that isn't what it claims is the caller's mistake: 400
 * `request.malformed`, not 500. `Compression` decodes the body while the route reads it and closes the channel with
 * the decoder's error, wrapped in Ktor's `ClosedByteChannelException`. Only calls whose body `Compression` decoded
 * are considered, so every other unexpected exception still reaches StatusPages as a 500.
 */
fun Application.installRequestDecodingErrors() {
    intercept(ApplicationCallPipeline.Plugins) {
        try {
            proceed()
        } catch (cause: Throwable) {
            if (call.request.appliedDecoders.isEmpty() || !cause.isDecodingFailure()) throw cause
            call.respondError(SharedError.Malformed(mapOf("body" to "could not be decoded")))
            finish()
        }
    }
}

/**
 * The JDK inflater's `DataFormatException`/`ZipException`, or the `IllegalStateException`/`EOFException` Ktor's gzip
 * reader throws on a bad header, checksum or truncated input, inside the channel exception.
 */
private fun Throwable.isDecodingFailure(): Boolean {
    val chain = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    if (chain.none { it is ClosedByteChannelException }) return false
    return chain.any {
        it is ZipException || it is DataFormatException || it is EOFException || it is IllegalStateException
    }
}
