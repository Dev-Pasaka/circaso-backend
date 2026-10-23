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
    api(ktorLibs.server.core)
    api(ktorLibs.server.websockets)
    implementation(ktorLibs.server.compression)

    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logstash.logback.encoder)

    testImplementation(ktorLibs.server.testHost)
    testImplementation(ktorLibs.client.contentNegotiation)
    testImplementation(ktorLibs.client.websockets)
    testImplementation(ktorLibs.server.contentNegotiation)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.logback.classic)
}
