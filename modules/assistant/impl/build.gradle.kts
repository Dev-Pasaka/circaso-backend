plugins {
    id("circaso.impl")
}

// Asha: the conversation, the agent loop, usage limits and the audit trail. Phase A (read) only.
dependencies {
    implementation(platform(libs.kandra.bom))
    implementation(libs.kandra.runtime)
    implementation(libs.kandra.migrate)

    implementation(ktorLibs.server.core)
    implementation(ktorLibs.client.core)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(platform(libs.kandra.bom))
    testImplementation(libs.kandra.test)
    testImplementation(ktorLibs.server.testHost)
    testImplementation(ktorLibs.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.cassandra)
}
