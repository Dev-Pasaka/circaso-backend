plugins {
    id("circaso.kotlin")
    `java-library`
}

// The envelope, the Resource → response mapping, StatusPages and the request id: for presentation layers
// and the gateway. May depend on `shared` and Ktor.
dependencies {
    api(project(":shared"))
    api(ktorLibs.server.core)
    api(ktorLibs.server.callId)
    api(ktorLibs.server.statusPages)
    api(ktorLibs.server.contentNegotiation)
    api(ktorLibs.serialization.kotlinx.json)
    api(ktorLibs.server.routingOpenapi)
    api(ktorLibs.openapiSchema)
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.opentelemetry.api)

    testImplementation(ktorLibs.server.testHost)
    testImplementation(ktorLibs.client.contentNegotiation)
    testRuntimeOnly(libs.logback.classic)
}
