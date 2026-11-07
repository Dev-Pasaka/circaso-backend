pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "circaso"

include(":shared")
include(":shared-web")
include(":infrastructure:api", ":infrastructure:impl")
include(":integrations:api", ":integrations:impl")
include(":observability:api", ":observability:impl")
include(":gateway")
// The staff edge (decision 2026-11-08): its own process, host and auth; never mounted on the app gateway.
include(":ops-gateway")
include(":grpc")
include(":modules:identity:api", ":modules:identity:impl")
include(":modules:users:api", ":modules:users:impl")
include(":modules:messaging:api", ":modules:messaging:impl")
include(":modules:wallet:api", ":modules:wallet:impl")
include(":modules:chama:api", ":modules:chama:impl")
include(":modules:calls:api", ":modules:calls:impl")
include(":modules:status:api", ":modules:status:impl")
include(":modules:share:api", ":modules:share:impl")
include(":modules:spaces:api", ":modules:spaces:impl")
include(":modules:support:api", ":modules:support:impl")
include(":modules:ops:api", ":modules:ops:impl")
include(":modules:assistant:api", ":modules:assistant:impl")
include(":app")
include(":architecture-tests")
