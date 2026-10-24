plugins {
    id("circaso.impl")
}

// One package per vendor (`twilio`, `africastalking`, `resend`, `social` for Google and Apple, which share one contract), each calling out through the shared
// `HttpClients.named("<vendor>")`. Beside them: `dev` (log and fake) and `placeholder`
// (vendors whose credentials are still to come).
dependencies {
    implementation(libs.koin.core)
    implementation(libs.logback.classic)

    // What the vendor code itself uses, declared here rather than inherited from another module. Ktor comes from the `ktorLibs` version
    // catalog (settings.gradle.kts), which keeps every Ktor artifact on one version; the rest are in gradle/libs.versions.toml.
    implementation(ktorLibs.client.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(ktorLibs.client.mock)
}
