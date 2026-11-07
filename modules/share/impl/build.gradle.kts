plugins {
    id("circaso.impl")
}

// Share links (decision 2026-11-06): a short, unguessable link to a public page showing what the sharer chose (a
// payment receipt, a group statement, an invite, a "pay me" card, a wallet address). The page is built here from the
// owning modules' contracts, never from what a phone sent: wallet for receipts and addresses, chama for statements,
// users for names and invites, messaging for who is in a group. Rates come from integrations' ExchangeRates.
dependencies {
    implementation(project(":modules:identity:api"))
    implementation(project(":modules:users:api"))
    implementation(project(":modules:messaging:api"))
    implementation(project(":modules:wallet:api"))
    implementation(project(":modules:chama:api"))
    implementation(libs.ktor.server.core)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.logback.classic)
    // PostgreSQL for data tests, as infrastructure:api types (the one test-only exception in DependencyRules)
    testImplementation(testFixtures(project(":infrastructure:impl")))
}
