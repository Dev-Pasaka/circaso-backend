plugins {
    id("circaso.api")
}

// Handles modules borrow: the PostgreSQL `Database` and its module spec (Exposed's DSL over R2DBC comes with it, for
// the data layers' `Table` objects and queries), the Kandra repositories and spec for the modules not moved yet, RedisScope,
// Scheduler, HttpClients, Config, Secrets, gRPC clients, health checks. No implementation lives here;
// `infrastructure:impl` provides them all.
dependencies {
    api(libs.exposed.core)
    api(libs.exposed.r2dbc)
    api(libs.exposed.java.time)
    api(platform(libs.kandra.bom))
    api(libs.kandra.runtime)
    api(libs.kandra.migrate)
    api(libs.ktor.client.core)
    api(libs.grpc.api)
}
