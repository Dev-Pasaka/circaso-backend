package com.circaso.observability.impl

import com.circaso.observability.api.Telemetry
import io.opentelemetry.api.GlobalOpenTelemetry
import org.koin.dsl.module

/**
 * Binds [Telemetry] to the process-wide OpenTelemetry the Java agent installs. Without the agent
 * `GlobalOpenTelemetry` is a no-op, which is what local runs and tests want.
 */
val observabilityModule = module {
    single<Telemetry> { OtelTelemetry(GlobalOpenTelemetry.get()) }
}
