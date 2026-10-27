plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.serializationPlugin)
    implementation(libs.spotless.gradlePlugin)
    implementation(libs.exposed.gradlePlugin)
    compileOnly(libs.exposed.gradlePluginCore) // VersionFormat, which the plugin only has at run time
}
