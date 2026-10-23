package com.circaso.shared

/**
 * What an access token may reach (Architecture → Gateway → Token scopes). The wire name, in the token's `scp`
 * claim, is the lowercase [wire] form.
 */
enum class Scope(val wire: String) {
    ONBOARDING("onboarding"),
    APP("app"),
    AGENT("agent"),
    OPS("ops");

    companion object {
        fun fromWire(raw: String): Scope? = entries.firstOrNull { it.wire == raw }
    }
}
