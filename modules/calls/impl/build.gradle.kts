plugins {
    id("circaso.impl")
}

// Calls: voice and video calls in a chat, with the media on Cloudflare Realtime's SFU (decision 2026-10-29). The
// server brokers each participant's session descriptions and rings people; media never passes through it.
// Membership comes from messaging's contract, names from users', the SFU through integrations' CallMedia.
dependencies {
    implementation(project(":modules:users:api"))
    implementation(project(":modules:messaging:api"))
    implementation(libs.ktor.server.core)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.logback.classic)
    // PostgreSQL for data tests, as infrastructure:api types (the one test-only exception in DependencyRules)
    testImplementation(testFixtures(project(":infrastructure:impl")))
}
