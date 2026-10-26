plugins {
    id("circaso.impl")
}

// One package per vendor (`twilio`, `africastalking`, `resend`, `social` for Google and Apple, which share one contract, `workersai` for
// Asha's models on Cloudflare Workers AI), each calling out through the shared
// `HttpClients.named("<vendor>")`. Beside them: `dev` (log and fake) and `placeholder`
// (vendors whose credentials are still to come).
dependencies {
    implementation(libs.koin.core)
    implementation(libs.logback.classic)

    // What the vendor code itself uses, declared here rather than inherited from another module. Versions are in gradle/libs.versions.toml.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.websockets) // Workers AI live voice sockets
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.test.host) // a fake vendor WebSocket server for the live voice tests
    testImplementation(libs.ktor.server.websockets)
}
