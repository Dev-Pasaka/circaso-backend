plugins {
    id("circaso.kotlin")
    `java-library`
}

// The gRPC edge, a sibling of `gateway`: server bootstrap, interceptors (auth, request id) and service
// registration. May depend on every `api`, never on an `impl`. What it serves is not designed yet.
dependencies {
    api(project(":shared"))
    api(project(":infrastructure:api"))
    api(project(":integrations:api"))
    api(project(":observability:api"))
    api(project(":modules:identity:api"))
    api(project(":modules:users:api"))
}
