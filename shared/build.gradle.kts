plugins {
    id("circaso.kotlin")
    `java-library`
}

// The kernel carries only the Kotlin standard library and kotlinx-serialization (see DependencyRules).
dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
