package com.circaso.observability.impl

import com.circaso.observability.api.Metrics
import com.circaso.observability.api.Telemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.ObservableDoubleGauge
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [Telemetry] over the OpenTelemetry API. Instrument names are the Prometheus names from the metrics catalog;
 * a name ending in `_seconds` is a duration, so its histogram is declared in seconds.
 */
class OtelTelemetry(openTelemetry: OpenTelemetry) : Telemetry {

    private val meter = openTelemetry.getMeter("circaso")
    private val tracer = openTelemetry.getTracer("circaso")
    private val propagators = openTelemetry.propagators.textMapPropagator

    private val counters = ConcurrentHashMap<String, LongCounter>()
    private val histograms = ConcurrentHashMap<String, DoubleHistogram>()

    // Held so the callbacks stay registered for the life of the process.
    private val gauges = CopyOnWriteArrayList<ObservableDoubleGauge>()

    override fun count(metric: String, labels: Map<String, String>, by: Long) {
        counters.getOrPut(metric) { meter.counterBuilder(metric).build() }.add(by, attributes(labels))
    }

    override fun record(metric: String, value: Double, labels: Map<String, String>) {
        histograms.getOrPut(metric) {
            val builder = meter.histogramBuilder(metric)
            if (metric.endsWith("_seconds")) builder.setUnit("s")
            builder.build()
        }.record(value, attributes(labels))
    }

    override fun gauge(metric: String, labels: Map<String, String>, supplier: () -> Double) {
        val attributes = attributes(labels)
        gauges += meter.gaugeBuilder(metric).buildWithCallback { it.record(supplier(), attributes) }
    }

    override suspend fun <T> span(name: String, attributes: Map<String, String>, block: suspend () -> T): T {
        val span = tracer.spanBuilder(name).setAllAttributes(attributes(attributes)).startSpan()
        return try {
            withContext(Context.current().with(span).asContextElement()) { block() }
        } catch (e: Throwable) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR)
            throw e
        } finally {
            span.end()
        }
    }

    override fun traceparent(): String? {
        if (!Span.current().spanContext.isValid) return null
        val carrier = mutableMapOf<String, String>()
        propagators.inject(Context.current(), carrier) { target, key, value -> target?.put(key, value) }
        return carrier["traceparent"]
    }

    private fun attributes(labels: Map<String, String>): Attributes {
        val builder: AttributesBuilder = Attributes.builder()
        labels.forEach { (key, value) ->
            require(key !in Metrics.forbiddenLabels) {
                "'$key' must not be a metric label or span attribute: it would leak personal data or explode the series count"
            }
            builder.put(key, value)
        }
        return builder.build()
    }
}
