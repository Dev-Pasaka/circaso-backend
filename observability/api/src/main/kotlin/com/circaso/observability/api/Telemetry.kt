package com.circaso.observability.api

/**
 * The handle modules and infrastructure borrow for business metrics and manual spans, in Circaso's own types:
 * no OpenTelemetry class crosses it. The domain stays library-free, so a use case that needs a counter declares
 * a small port of its own and `di` binds that port to this handle.
 *
 * Label values never carry a user id, phone number, handle, email or IP: they would leak personal data and
 * explode the number of series. User ids belong on traces and logs only; the implementation rejects those keys.
 */
interface Telemetry {
    /** Adds [by] to a counter. */
    fun count(
        metric: String,
        labels: Map<String, String> = emptyMap(),
        by: Long = 1,
    )

    /** Records one observation (a duration in seconds, a ratio) in a histogram. */
    fun record(
        metric: String,
        value: Double,
        labels: Map<String, String> = emptyMap(),
    )

    /** Reports a value that is read when metrics are collected (consumer lag, circuit state). */
    fun gauge(
        metric: String,
        labels: Map<String, String> = emptyMap(),
        supplier: () -> Double,
    )

    /** Runs [block] inside a span named [name]; an exception marks the span as failed and is rethrown. */
    suspend fun <T> span(
        name: String,
        attributes: Map<String, String> = emptyMap(),
        block: suspend () -> T,
    ): T

    /** The W3C `traceparent` of the current span, or null when there is none; carried in event envelopes. */
    fun traceparent(): String?
}

/** Used where no telemetry is wired (unit tests of code that takes a [Telemetry]). */
object NoopTelemetry : Telemetry {
    override fun count(
        metric: String,
        labels: Map<String, String>,
        by: Long,
    ) = Unit

    override fun record(
        metric: String,
        value: Double,
        labels: Map<String, String>,
    ) = Unit

    override fun gauge(
        metric: String,
        labels: Map<String, String>,
        supplier: () -> Double,
    ) = Unit

    override suspend fun <T> span(
        name: String,
        attributes: Map<String, String>,
        block: suspend () -> T,
    ): T = block()

    override fun traceparent(): String? = null
}
