plugins {
    id("circaso.api")
}

// Asha's contract: the events it publishes (Phase B: proposals) and its realtime frames (the gateway pushes `status`).
dependencies {
    api(project(":modules:identity:api"))
    api(project(":modules:users:api"))
}
