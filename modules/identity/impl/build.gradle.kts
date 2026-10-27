plugins {
    id("circaso.impl")
}

// Accounts, providers, phone, devices, sessions, recovery. The domain imports nothing from the libraries below;
// presentation uses Ktor and `shared-web`, data uses Exposed (PostgreSQL) and Redis through `infrastructure:api`.
dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlinx.coroutines.test)
    // PostgreSQL for data tests, as infrastructure:api types (the one test-only exception in DependencyRules)
    testImplementation(testFixtures(project(":infrastructure:impl")))
}
