import com.circaso.build.DependencyRules

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

// kandra-core 0.5.1 depends on slf4j-simple, a second SLF4J provider next to Logback. Logback is the one provider.
configurations.configureEach {
    exclude(group = "org.slf4j", module = "slf4j-simple")
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// A forbidden project or library dependency fails here, at configuration time.
afterEvaluate {
    DependencyRules.verify(project)
}
