plugins {
    id("circaso.impl")
}

// Chamas: a group chat's shared goal, its contributions (ordinary USDC payments to the group's treasurer) and the
// payment requests the admins approve before the treasurer pays them (decision 2026-10-29). Membership and roles
// come from messaging's contract, transfers from wallet's, names from users'.
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
}
