plugins {
    id("circaso.api")
}

// Asha's contract. Nothing depends on the assistant, so this holds only the events it publishes (Phase B: proposals).
dependencies {
    api(project(":modules:identity:api"))
    api(project(":modules:users:api"))
}
