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
 * The messages Ktor 3.6.0's gzip and deflate readers (`io.ktor.util.EncodersJvm`) give the `IllegalStateException`s
 * they throw from `check()`. Recheck them when Ktor is upgraded.
 */
private val DECODER_MESSAGES =
    listOf(
        "GZIP magic invalid",
        "Deflater method unsupported",
        "Gzip file name not supported",
        "Gzip file comment not supported",
        "Gzip checksum invalid",
        "Gzip size invalid",
        "Inflater made no progress",
    )

/**
 * A `Content-Encoding: gzip` or `deflate` request body that isn't what it claims is the caller's mistake: 400
 * `request.malformed`, not 500. `Compression` decodes the body while the route reads it and closes the channel with
 * the decoder's error, wrapped in Ktor's `ClosedByteChannelException`. Only calls whose body `Compression` decoded
 * are considered, and only when the decoder's error is the immediate cause of that channel exception; every other
 * unexpected exception still reaches StatusPages as a 500.
 *
 * The edge left: during a request whose body was decoded, an outbound client call (an integration) that fails the
 * same way, with a channel exception caused by its own response's decoding error, would also answer 400.
 */
fun Application.installRequestDecodingErrors() {
    intercept(ApplicationCallPipeline.Plugins) {
        try {
            proceed()
        } catch (cause: Throwable) {
            if (call.request.appliedDecoders.isEmpty() || !cause.isDecodingFailure()) throw cause
            call.respondError(SharedError.Malformed("body", "could not be decoded"))
            finish()
        }
    }
}

/** A `ClosedByteChannelException` somewhere in the chain whose immediate cause is the decoder's own error. */
private fun Throwable.isDecodingFailure(): Boolean =
    generateSequence(this) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .filterIsInstance<ClosedByteChannelException>()
        .any { it.cause?.isDecoderError() == true }

private fun Throwable.isDecoderError(): Boolean =
    when (this) {
        is ZipException, is DataFormatException, is EOFException -> true
        is IllegalStateException -> DECODER_MESSAGES.any { message.orEmpty().startsWith(it) }
        else -> false
    }
