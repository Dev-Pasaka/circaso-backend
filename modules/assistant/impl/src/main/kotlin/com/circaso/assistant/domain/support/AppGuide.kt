package com.circaso.assistant.domain.support

/**
 * How the Circaso app works, for Asha to explain and demonstrate (2026-11-09, Pasaka: "her memory is always updated
 * with the new features of the app"). The text is `resources/asha/app-guide.md`; update it whenever a user-facing
 * feature ships (see the README beside it). It goes into the fixed system prompt, which the provider caches.
 */
object AppGuide {
    const val RESOURCE = "/asha/app-guide.md"

    /** The guide's text; loading fails loudly rather than leaving Asha without it. */
    val text: String by lazy {
        val stream =
            AppGuide::class.java.getResourceAsStream(RESOURCE) ?: error("Asha's app guide is missing: $RESOURCE")
        stream
            .use { it.readBytes().decodeToString() }
            .trim()
            .also { require(it.isNotEmpty()) { "Asha's app guide is empty" } }
    }
}
