plugins {
    id("circaso.impl")
}

// Plumbing, no business logic: PostgreSQL (Exposed over R2DBC pools; Flyway over JDBC for Migrate) and Kandra for
// the modules not moved yet, Redis, the event runtime, the scheduler, the shared HTTP client, config and secrets,
// logging. Hands each module a handle scoped to it.
dependencies {
    implementation(project(":observability:api"))

    implementation(libs.r2dbc.postgresql)
    implementation(libs.r2dbc.pool)
    // JDBC only for the Migrate step: Flyway and its PostgreSQL driver (Konsist keeps them in database.migrate)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.postgresql)

    implementation(platform(libs.kandra.bom))
    implementation(libs.kandra.ktor)
    implementation(libs.kandra.koin)
    implementation(libs.kandra.migrate)

    implementation(libs.grpc.netty.shaded)
    implementation(libs.lettuce.core)
    implementation(libs.aws.s3) // the AWS SDK for Kotlin, pointed at Cloudflare R2 (S3 compatible)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.kotlinx.coroutines.slf4j)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.websockets) // vendors reached over a live socket (`webSockets = true`)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)

    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.websockets)
    testImplementation(libs.kandra.test)
    testImplementation(libs.grpc.stub)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.scylladb)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.opentelemetry.sdk.testing)
}
