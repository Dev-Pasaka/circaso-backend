plugins {
    id("circaso.impl")
}

// Circle user-controlled wallets on several chains, the deletion guard, and later transfers and transaction records.
// The domain imports nothing from the libraries below; data reaches PostgreSQL through Exposed and `Database` (both
// from infrastructure:api) and Redis through RedisScope; Circle and the exchange rates only through their contracts.
dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.logback.classic)
    // PostgreSQL for data tests, as infrastructure:api types (the one test-only exception in DependencyRules)
    testImplementation(testFixtures(project(":infrastructure:impl")))
}
