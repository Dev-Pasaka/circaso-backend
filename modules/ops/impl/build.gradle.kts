plugins {
    id("circaso.impl")
}

// The staff side of Circaso (decision 2026-11-08): the staff list, the audit log, and every staff route the ops gateway
// serves (dashboard, users, transactions, groups, finance, AI usage, tickets, calls, staff, audit, support accounts). It owns only staff
// and audit data; everything else it reads or changes through the owning modules' contracts, never their tables.
dependencies {
    implementation(project(":modules:identity:api"))
    implementation(project(":modules:users:api"))
    implementation(project(":modules:messaging:api"))
    implementation(project(":modules:wallet:api"))
    implementation(project(":modules:support:api"))
    implementation(project(":modules:assistant:api"))
    implementation(project(":modules:calls:api"))
    implementation(project(":integrations:api"))
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
