plugins {
    id("circaso.kotlin")
}

// Konsist rules that fail the build on a boundary breach. Reads the sources of every module,
// so it depends on none of them.
dependencies {
    testImplementation(libs.konsist)
}

// The rules read the other modules' source files, which Gradle does not know about: without this the test is
// "up to date" after any change outside this module and checks nothing.
tasks.test {
    inputs
        .files(
            fileTree(rootDir) {
                include("**/src/main/**/*.kt", "**/build.gradle.kts")
                exclude("**/build/**", "architecture-tests/**")
            },
        ).withPropertyName("guardedSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
