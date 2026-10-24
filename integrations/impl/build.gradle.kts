plugins {
    id("circaso.impl")
}

// One package per vendor (`twilio`, `africastalking`, `resend`, `social` for Google and Apple, which share one contract), each calling out through the shared
// `HttpClients.named("<vendor>")`. Beside them: `dev` (log and fake) and `placeholder`
// (vendors whose credentials are still to come).
dependencies {
    implementation(libs.koin.core)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(ktorLibs.client.mock)
}
