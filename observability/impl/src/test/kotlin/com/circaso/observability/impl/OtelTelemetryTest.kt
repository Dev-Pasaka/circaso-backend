package com.circaso.observability.impl

import com.circaso.observability.api.Metrics
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtelTelemetryTest {

    private val metricReader = InMemoryMetricReader.create()
    private val spans = InMemorySpanExporter.create()
    private val sdk = OpenTelemetrySdk.builder()
        .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()
    private val telemetry = OtelTelemetry(sdk)

    private fun metric(name: String) = metricReader.collectAllMetrics().single { it.name == name }

    @Test
    fun `a counter adds up by label`() {
        telemetry.count(Metrics.EVENTS_PUBLISHED, mapOf("stream" to "events:identity", "type" to "identity.AccountActivated"))
        telemetry.count(Metrics.EVENTS_PUBLISHED, mapOf("stream" to "events:identity", "type" to "identity.AccountActivated"), by = 2)

        val points = metric(Metrics.EVENTS_PUBLISHED).longSumData.points
        assertEquals(3L, points.single().value)
        assertEquals("events:identity", points.single().attributes.get(AttributeKey.stringKey("stream")))
    }

    @Test
    fun `a duration histogram is declared in seconds`() {
        telemetry.record(Metrics.INTEGRATION_DURATION, 0.25, mapOf("vendor" to "africastalking", "operation" to "send", "outcome" to "ok"))

        val data = metric(Metrics.INTEGRATION_DURATION)
        assertEquals("s", data.unit)
        assertEquals(1L, data.histogramData.points.single().count)
    }

    @Test
    fun `a gauge reports what its supplier returns when collected`() {
        var lag = 4.0
        telemetry.gauge(Metrics.EVENTS_LAG, mapOf("group" to "users")) { lag }

        assertEquals(4.0, metric(Metrics.EVENTS_LAG).doubleGaugeData.points.single().value)
        lag = 9.0
        assertEquals(9.0, metric(Metrics.EVENTS_LAG).doubleGaugeData.points.single().value)
    }

    @Test
    fun `labels that would leak personal data are refused`() {
        for (key in listOf("userId", "phone", "email", "handle", "ip", "token")) {
            assertFailsWith<IllegalArgumentException>("label '$key'") { telemetry.count("x_total", mapOf(key to "v")) }
        }
    }

    @Test
    fun `a span wraps the block, carries attributes and ends`() = runTest {
        val result = telemetry.span("identity.SignIn", mapOf("provider" to "google")) { 42 }

        assertEquals(42, result)
        val span = spans.finishedSpanItems.single()
        assertEquals("identity.SignIn", span.name)
        assertEquals("google", span.attributes.get(AttributeKey.stringKey("provider")))
    }

    @Test
    fun `a failing block marks the span as an error and rethrows`() = runTest {
        assertFailsWith<IllegalStateException> { telemetry.span("boom") { error("nope") } }

        val span = spans.finishedSpanItems.single()
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, span.status.statusCode)
        assertTrue(span.events.any { it.name == "exception" })
    }

    @Test
    fun `the traceparent is the current span's, and null outside one`() = runTest {
        assertNull(telemetry.traceparent())

        val inside = telemetry.span("outer") { telemetry.traceparent() }

        assertNotNull(inside)
        assertTrue(Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}").matches(inside), inside)
        assertEquals(inside.split('-')[1], spans.finishedSpanItems.single().traceId)
    }

    @Test
    fun `a nested span is a child of the outer one`() = runTest {
        telemetry.span("outer") { telemetry.span("inner") { } }

        val (inner, outer) = spans.finishedSpanItems
        assertEquals(outer.spanId, inner.parentSpanId)
    }
}
