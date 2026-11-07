plugins {
    id("circaso.impl")
    // Asha's eval (decision 2026-11-07): the cases, fake tools, grader and runner, shared with `app`, which owns the
    // `ashaEval` task because only it may build the real model providers
    `java-test-fixtures`
}

// Asha: the conversation, the agent loop, usage limits and the audit trail. Phase A (read) only. The domain imports
// nothing from the libraries below; data reaches PostgreSQL through Exposed and `Database` (both from
// infrastructure:api) and Redis through RedisScope.
dependencies {
    // the answers of the other modules' agent routes, which Asha's tools read over HTTP (decisions 2026-10-27 and
    // 2026-10-31): users, identity, messaging, wallet and chama; status since 2026-11-05 (own moments' counts)
    implementation(project(":modules:users:api"))
    implementation(project(":modules:identity:api"))
    implementation(project(":modules:messaging:api"))
    implementation(project(":modules:wallet:api"))
    implementation(project(":modules:chama:api"))
    implementation(project(":modules:status:api"))
    // the live voice latency histogram (decision 2026-10-30), through the Telemetry handle
    implementation(project(":observability:api"))

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

    // the eval (src/testFixtures): the real tools over fake ports and a fake agent-route backend, never a vendor
    testFixturesImplementation(project(":shared"))
    testFixturesImplementation(project(":integrations:api"))
    testFixturesImplementation(project(":modules:users:api"))
    testFixturesImplementation(project(":modules:messaging:api"))
    testFixturesImplementation(project(":modules:wallet:api"))
    testFixturesImplementation(libs.ktor.client.core)
    testFixturesImplementation(libs.ktor.client.mock)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotlinx.serialization.json)
}
