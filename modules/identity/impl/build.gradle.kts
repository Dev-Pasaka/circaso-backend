plugins {
    id("circaso.impl")
}

// Accounts, providers, phone, devices, sessions, recovery. The domain imports nothing from the libraries below;
// presentation uses Ktor and `shared-web`, data uses Kandra and Redis through `infrastructure:api`.
dependencies {
    implementation(platform(libs.kandra.bom))
    implementation(libs.kandra.runtime)
    implementation(libs.kandra.migrate)

    implementation(ktorLibs.server.core)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(platform(libs.kandra.bom))
    testImplementation(libs.kandra.test)
    testImplementation(ktorLibs.server.testHost)
    testImplementation(ktorLibs.client.contentNegotiation)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.cassandra)
}
