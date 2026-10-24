plugins {
    id("circaso.api")
}

// Handles modules borrow: Kandra repositories and the module spec, RedisScope, Scheduler, HttpClients, Config,
// Secrets, gRPC clients, health checks. No implementation lives here; `infrastructure:impl` provides them all.
dependencies {
    api(platform(libs.kandra.bom))
    api(libs.kandra.runtime)
    api(libs.kandra.migrate)
    api(libs.ktor.client.core)
    api(libs.grpc.api)
}
