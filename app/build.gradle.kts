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

// `./gradlew :app:runOps` serves the ops gateway (decision 2026-11-08): its own process and port (OPS_PORT, default 8081)
// from the same build, with the same `.env`.
tasks.register<JavaExec>("runOps") {
    group = "application"
    description = "Serves the ops gateway (the staff API) locally."
    mainClass = "com.circaso.app.OpsMain"
    classpath = project.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = rootDir
}

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
    implementation(project(":ops-gateway"))
    implementation(project(":grpc"))
    implementation(project(":modules:identity:api"))
    implementation(project(":modules:identity:impl"))
    implementation(project(":modules:users:api"))
    implementation(project(":modules:users:impl"))
    implementation(project(":modules:messaging:api"))
    implementation(project(":modules:messaging:impl"))
    implementation(project(":modules:wallet:api"))
    implementation(project(":modules:wallet:impl"))
    implementation(project(":modules:chama:api"))
    implementation(project(":modules:chama:impl"))
    implementation(project(":modules:calls:api"))
    implementation(project(":modules:calls:impl"))
    implementation(project(":modules:status:api"))
    implementation(project(":modules:status:impl"))
    implementation(project(":modules:share:api"))
    implementation(project(":modules:share:impl"))
    implementation(project(":modules:spaces:api"))
    implementation(project(":modules:spaces:impl"))
    implementation(project(":modules:ops:api"))
    implementation(project(":modules:ops:impl"))
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
    testImplementation(testFixtures(project(":modules:assistant:impl"))) // Asha's eval: cases, fake tools, grader
    testImplementation(libs.kotlinx.coroutines.core)
}

// Asha's eval (decision 2026-11-07) is not a test: it calls paid models, so `test` (and CI) leaves it out and
// `./gradlew :app:ashaEval` runs it on demand. It lives here because only `app` may build the real model providers.
// Settings are Gradle properties, passed on as system properties: -PashaEval.arms=oracle,workers-ai,haiku-low,...
// (see AshaEvalTest for the rest). The report lands in app/build/asha-eval/<time>/report.md.
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("eval") }
}

tasks.register<Test>("ashaEval") {
    group = "verification"
    description = "Runs Asha's eval against the models named in -PashaEval.arms (calls paid models)."
    val test = project.extensions.getByType<SourceSetContainer>()["test"]
    testClassesDirs = test.output.classesDirs
    classpath = test.runtimeClasspath
    useJUnitPlatform { includeTags("eval") }
    outputs.upToDateWhen { false }
    systemProperty("ashaEval.out", layout.buildDirectory.dir("asha-eval").get().asFile.absolutePath)
    providers.gradlePropertiesPrefixedBy("ashaEval.").get().forEach { (key, value) -> systemProperty(key, value) }
    testLogging {
        showStandardStreams = true
    }
}
