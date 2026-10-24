plugins {
    id("circaso.kotlin")
    `java-library`
}

// The envelope, the Resource → response mapping, StatusPages and the request id: for presentation layers
// and the gateway. May depend on `shared` and Ktor.
dependencies {
    api(project(":shared"))
    api(libs.ktor.server.core)
    api(libs.ktor.server.call.id)
    api(libs.ktor.server.status.pages)
    api(libs.ktor.server.content.negotiation)
    api(libs.ktor.serialization.kotlinx.json)
    api(libs.ktor.server.routing.openapi)
    api(libs.ktor.openapi.schema)
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.opentelemetry.api)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testRuntimeOnly(libs.logback.classic)
}
