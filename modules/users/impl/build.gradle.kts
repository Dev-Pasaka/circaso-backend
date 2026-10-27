plugins {
    id("circaso.impl")
}

// Profiles, handles, contacts, blocks and the reach rule. The domain imports nothing from the libraries below; data
// reaches PostgreSQL through Exposed and `Database` (both from infrastructure:api) and Redis through RedisScope.
dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    // PostgreSQL for data tests, as infrastructure:api types (the one test-only exception in DependencyRules)
    testImplementation(testFixtures(project(":infrastructure:impl")))
}
