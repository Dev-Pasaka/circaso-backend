plugins {
    id("circaso.kotlin")
    `java-library`
}

// The staff edge (decision 2026-11-08, Pasaka: "separate endpoint, separate management, don't mix them together"):
// its own process (`app`'s OpsMain), host, port, CORS, sign-in (Cloudflare Access, or the sandbox dev login) and rate
// limits. It mounts only `OpsRouteContributor`s; the app gateway mounts none of them. Like the app gateway it depends
// on every `api` and no `impl`, and it borrows the app gateway's edge parts (idempotency, rate limiter) as a library.
dependencies {
    api(project(":shared"))
    api(project(":shared-web"))
    api(project(":infrastructure:api"))
    api(project(":integrations:api"))
    api(project(":observability:api"))
    api(project(":modules:ops:api"))
    implementation(project(":gateway"))
    api(libs.ktor.server.core)
    implementation(libs.ktor.client.core)
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.logback.classic)
}
