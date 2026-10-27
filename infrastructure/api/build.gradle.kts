plugins {
    id("circaso.api")
}

// Handles modules borrow: the PostgreSQL `Database` and its module spec (Exposed's DSL over R2DBC comes with it, for
// the data layers' `Table` objects and queries), RedisScope, Scheduler, HttpClients, Config, Secrets, gRPC clients,
// health checks. No implementation lives here; `infrastructure:impl` provides them all.
dependencies {
    api(libs.exposed.core)
    api(libs.exposed.r2dbc)
    api(libs.exposed.java.time)
    api(libs.ktor.client.core)
    api(libs.grpc.api)
}
