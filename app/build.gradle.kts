plugins {
    id("circaso.kotlin")
    application
}

// The only place that knows the implementations: `serve` wires modules and starts the server,
// `migrate` runs the migrations once per deploy (PostgreSQL's Flyway scripts).
application {
    mainClass = "com.circaso.app.MainKt"
    // Netty loads a native transport; newer JDKs warn unless native access is granted explicitly.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// `./gradlew :app:run` serves locally. Settings come from environment variables, and for local development from `.env`
// at the repository root (copy `.env.example`); nothing is passed here, so the log format, levels and colors are yours to set there.

// `./gradlew :app:migrate` runs the migrations once, with the same settings.
tasks.register<JavaExec>("migrate") {
    group = "application"
    description = "Runs every module's database migrations once."
    mainClass = "com.circaso.app.Migrate"
    classpath = project.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":shared-web"))
    implementation(project(":infrastructure:api"))
    implementation(project(":infrastructure:impl"))
    implementation(project(":integrations:api"))
    implementation(project(":integrations:impl"))
    implementation(project(":observability:api"))
    implementation(project(":observability:impl"))
    implementation(project(":gateway"))
    implementation(project(":grpc"))
    implementation(project(":modules:identity:api"))
    implementation(project(":modules:identity:impl"))
    implementation(project(":modules:users:api"))
    implementation(project(":modules:users:impl"))
    implementation(project(":modules:messaging:api"))
    implementation(project(":modules:messaging:impl"))
    implementation(project(":modules:wallet:api"))
    implementation(project(":modules:wallet:impl"))
    implementation(project(":modules:assistant:api"))
    implementation(project(":modules:assistant:impl"))

    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers)
    testImplementation(testFixtures(project(":infrastructure:impl")))
}
