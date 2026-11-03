plugins {
    id("circaso.impl")
}

// A group's money (decision 2026-11-03: chamas and groups are one thing): its optional target, the contributions into
// the group's own wallet, who approves payouts, and the payout requests the admins approve before the wallet's signer
// pays them. Membership and roles come from messaging's contract, the group wallet and transfers from wallet's, names
// from users'.
dependencies {
    implementation(project(":modules:users:api"))
    implementation(project(":modules:messaging:api"))
    implementation(project(":modules:wallet:api"))
    implementation(libs.ktor.server.core)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.logback.classic)
    // PostgreSQL for data tests, as infrastructure:api types (the one test-only exception in DependencyRules)
    testImplementation(testFixtures(project(":infrastructure:impl")))
}
