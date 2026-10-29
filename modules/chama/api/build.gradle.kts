plugins {
    id("circaso.api")
}

// chama's contract: its frames (read typed by the gateway for push wording), its events and the one question other
// modules may ask. A chama is a group chat (messaging's ChatId) with a shared goal.
dependencies {
    api(project(":modules:identity:api"))
    api(project(":modules:messaging:api"))
    api(project(":modules:wallet:api"))
}
