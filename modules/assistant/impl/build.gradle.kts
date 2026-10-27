plugins {
    id("circaso.impl")
}

// Asha: the conversation, the agent loop, usage limits and the audit trail. Phase A (read) only. The domain imports
// nothing from the libraries below; data reaches PostgreSQL through Exposed and `Database` (both from
// infrastructure:api) and Redis through RedisScope.
dependencies {
    // the answers of users' agent routes, which Asha's tools read over HTTP (decision 2026-10-27)
    implementation(project(":modules:users:api"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.websockets) // the live voice socket (A11); the gateway installs the plugin
    implementation(libs.ktor.client.core)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    // PostgreSQL for data tests, as infrastructure:api types (the one test-only exception in DependencyRules)
    testImplementation(testFixtures(project(":infrastructure:impl")))
}
