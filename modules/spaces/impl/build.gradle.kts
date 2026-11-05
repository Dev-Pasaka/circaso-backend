plugins {
    id("circaso.impl")
}

// Spaces (phase 3): communities and small businesses with a space chat that is not end-to-end encrypted, three join
// types (public follow, paid members-only, invite-only), roles and moderation, reports, and a space wallet that is
// the wallet module's (owner type SPACE). Spaces implements the wallet's `SpaceWalletPolicy` and messaging's
// `SpacePrivateChats`, reads users' profiles and contacts and identity's account age, and follows `events:wallet`
// (memberships come from confirmed payments) and `events:identity` (deleted accounts).
dependencies {
    implementation(project(":modules:identity:api"))
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
