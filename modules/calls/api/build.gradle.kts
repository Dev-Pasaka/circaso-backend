plugins {
    id("circaso.api")
}

// calls' contract: its frames (read typed by the gateway for push wording), its event and the one question other
// modules may ask. A call belongs to a chat (messaging's ChatId).
dependencies {
    api(project(":modules:messaging:api"))
}
