plugins {
    id("circaso.api")
}

// users' contract (ReachPolicy, Profiles, events). Arrives in M5.
dependencies {
    api(project(":modules:identity:api"))
}
