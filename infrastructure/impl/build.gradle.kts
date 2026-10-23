plugins {
    id("circaso.impl")
}

// Plumbing, no business logic: Kandra and migrations, Redis, the event runtime, the scheduler, the shared HTTP
// client, config and secrets, logging. Hands each module a handle scoped to it.
dependencies {
    implementation(project(":observability:api"))

    implementation(platform(libs.kandra.bom))
    implementation(libs.kandra.ktor)
    implementation(libs.kandra.koin)
    implementation(libs.kandra.migrate)

    implementation(libs.grpc.netty.shaded)
    implementation(libs.lettuce.core)
    implementation(libs.aws.s3)           // the AWS SDK for Kotlin, pointed at Cloudflare R2 (S3 compatible)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.kotlinx.coroutines.slf4j)

    implementation(ktorLibs.server.core)
    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.client.contentNegotiation)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)

    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    testImplementation(ktorLibs.client.mock)
    testImplementation(ktorLibs.server.testHost)
    testImplementation(libs.kandra.test)
    testImplementation(libs.grpc.stub)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.scylladb)
    testImplementation(libs.opentelemetry.sdk.testing)
}
