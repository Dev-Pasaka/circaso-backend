plugins {
    id("circaso.impl")
}

// Support tickets (decision 2026-11-08): users open them from the app (SU1-SU4, app gateway) or through Asha's
// `openSupportTicket` tool; staff work them through `SupportDesk` from the ops module (ops gateway only). A ticket's
// context is checked against the owning modules' contracts: wallet for a transfer, messaging for a chat.
dependencies {
    implementation(project(":modules:wallet:api"))
    implementation(project(":modules:messaging:api"))
    implementation(libs.ktor.server.core)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.logback.classic)
    // PostgreSQL for data tests, as infrastructure:api types (the one test-only exception in DependencyRules)
    testImplementation(testFixtures(project(":infrastructure:impl")))
}
