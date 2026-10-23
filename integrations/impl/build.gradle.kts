plugins {
    id("circaso.impl")
}

// One package per vendor once the real implementations arrive. For now: `dev` (log and fake) and `placeholder`
// (vendors whose credentials are still to come).
dependencies {
    implementation(libs.koin.core)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlinx.coroutines.test)
}
