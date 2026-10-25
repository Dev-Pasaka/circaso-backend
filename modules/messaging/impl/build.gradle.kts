plugins {
    id("circaso.impl")
}

// Chats, membership, the key directory, delivery of end-to-end encrypted messages and media, receipts and history.
// The domain imports nothing from the libraries below.
dependencies {
    implementation(platform(libs.kandra.bom))
    implementation(libs.kandra.runtime)
    implementation(libs.kandra.migrate)

    implementation(libs.ktor.server.core)
    implementation(libs.koin.core)

    testImplementation(platform(libs.kandra.bom))
    testImplementation(libs.kandra.test)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.cassandra)
}
