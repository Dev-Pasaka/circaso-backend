package com.circaso.ramp.domain.support

/**
 * A merchant's own callback address (decision 2026-11-22): `<origin>/v1/hooks/payhero/<name>-<token>`. The name is
 * only there to be read; the 16 random characters are the shared secret, the best guard a provider that signs nothing
 * allows. The merchant pastes the address into their provider account, and the ramp sends the same one with every
 * prompt it makes for them.
 */
object Callbacks {
    private const val PATH = "/v1/hooks/payhero/"
    private const val SLUG_LENGTH = 24
    private const val FALLBACK = "merchant"

    /** The key for a merchant called [displayName] with the random [token]. */
    fun key(displayName: String, token: String): String = "${slug(displayName)}-$token"

    fun url(baseUrl: String, key: String): String = baseUrl.trimEnd('/') + PATH + key

    /** [displayName] as lower-case letters and digits joined by single hyphens ("Crynce Ltd." is `crynce-ltd`). */
    fun slug(displayName: String): String =
        displayName
            .lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .take(SLUG_LENGTH)
            .trimEnd('-')
            .ifEmpty { FALLBACK }
}
