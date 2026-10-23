plugins {
    id("circaso.impl")
}

// Profiles, handles, contacts, blocks and the reach rule. The domain imports nothing from the libraries below.
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
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.cassandra)
}
