plugins {
    id("circaso.impl")
}

// OpenTelemetry behind `Telemetry`. In staging and production the OpenTelemetry Java agent provides the SDK and
// exports (OTLP to the Collector, Prometheus on :9464); this module talks to its API only, so with no agent
// (local runs, tests) everything is a harmless no-op. The logging setup stays in infrastructure:impl.
dependencies {
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.extension.kotlin)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)

    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
