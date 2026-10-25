plugins {
    id("circaso.api")
}

// messaging's contract (ChatDirectory, events). Read by wallet (pay-in-chat) and calls later.
dependencies {
    api(project(":modules:identity:api"))
    api(project(":modules:users:api"))
}
