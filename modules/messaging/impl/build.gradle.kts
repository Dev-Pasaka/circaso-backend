plugins {
    id("circaso.impl")
}

// Chats, membership, the key directory, delivery of end-to-end encrypted messages and media, receipts and history.
// The domain imports nothing from the libraries below; data reaches PostgreSQL through Exposed and `Database` (both
// from infrastructure:api) and Redis through RedisScope.
dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.logback.classic)
    // PostgreSQL for data tests, as infrastructure:api types (the one test-only exception in DependencyRules)
    testImplementation(testFixtures(project(":infrastructure:impl")))
}
