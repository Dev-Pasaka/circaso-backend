plugins {
    id("circaso.kotlin")
    `java-library`
}

// The edge: version check, auth, scopes, rate limits, idempotency, request log, webhooks and the WebSocket hub.
// No business logic. May depend on every `api`, never on an `impl`.
dependencies {
    api(project(":shared"))
    api(project(":shared-web"))
    api(project(":infrastructure:api"))
    api(project(":integrations:api"))
    api(project(":observability:api"))
    api(project(":modules:identity:api"))
    api(project(":modules:users:api"))
    api(project(":modules:messaging:api"))
    api(project(":modules:wallet:api"))
    api(project(":modules:chama:api"))
    api(project(":modules:calls:api"))
    api(project(":modules:status:api"))
    api(project(":modules:spaces:api"))
    api(project(":modules:assistant:api"))
    api(project(":modules:support:api"))
    api(libs.ktor.server.core)
    api(libs.ktor.server.websockets)
    implementation(libs.ktor.server.compression)

    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.logback.classic) // RequestLogTest reads the log events
}
