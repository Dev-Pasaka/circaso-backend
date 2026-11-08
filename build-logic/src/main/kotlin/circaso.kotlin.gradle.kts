import com.circaso.build.DependencyRules
import com.circaso.build.TestLimit

// Base for every Circaso Gradle module: Kotlin/JVM, serialization, JUnit 5, and the dependency table.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.diffplug.spotless")
}

// Several projects are called `api` and `impl`; their group carries the rest of the path so that
// `com.circaso.modules.identity:api` and `com.circaso.infrastructure:api` are different modules to Gradle.
group = (listOf("com.circaso") + path.split(':').filter { it.isNotEmpty() }.dropLast(1)).joinToString(".")
version = "1.0.0-SNAPSHOT"

// ...and so is the jar name, or the app distribution holds two `impl-1.0.0-SNAPSHOT.jar`.
base {
    archivesName = path.removePrefix(":").replace(':', '-')
}

val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(21)
}

// Layout is checked by `spotlessCheck` (part of `build`); the rules are in the root .editorconfig.
spotless {
    kotlin {
        target("src/**/*.kt")
        targetExclude("**/fixtures/**")
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
    }
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// One test suite at a time (see TestLimit); everything else in the build still runs in parallel.
val testLimit = gradle.sharedServices.registerIfAbsent("testLimit", TestLimit::class.java) { maxParallelUsages = 1 }

tasks.withType<Test>().configureEach {
    usesService(testLimit)
    useJUnitPlatform()
    // Test processes per suite: 1 by default (each starts its own PostgreSQL and Valkey). CI raises it with
    // -Pcircaso.testForks=N for a suite that has a runner to itself.
    maxParallelForks = providers.gradleProperty("circaso.testForks").map { it.toInt() }.getOrElse(1)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// A forbidden project or library dependency fails here, at configuration time.
afterEvaluate {
    DependencyRules.verify(project)
}
