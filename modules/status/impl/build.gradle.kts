plugins {
    id("circaso.impl")
}

// Moments (WhatsApp-style statuses that live 24 hours), end to end encrypted on the poster's phone. The server keeps
// ciphertext, the audience, view receipts and expiry, and the encrypted media in object storage. The reach rule comes
// from users' contract, account deletion from identity's events.
dependencies {
    implementation(project(":modules:identity:api"))
    implementation(project(":modules:users:api"))
    implementation(libs.ktor.server.core)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.logback.classic)
    // PostgreSQL for data tests, as infrastructure:api types (the one test-only exception in DependencyRules)
    testImplementation(testFixtures(project(":infrastructure:impl")))
}
