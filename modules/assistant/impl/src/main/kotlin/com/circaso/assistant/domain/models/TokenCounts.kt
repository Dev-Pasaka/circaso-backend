package com.circaso.assistant.domain.models

/**
 * Tokens of one model, split the way a provider prices them (decision 2026-11-08): [input] is the input read fresh
 * (neither from nor into the prompt cache), [cacheRead] the input read from the cache, [cacheWrite] the input written
 * to it, and [output] what the model wrote. All of a call's input is `input + cacheRead + cacheWrite`.
 */
data class TokenCounts(
    val input: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val output: Long = 0,
) {
    operator fun plus(other: TokenCounts) =
        TokenCounts(
            input + other.input,
            cacheRead + other.cacheRead,
            cacheWrite + other.cacheWrite,
            output + other.output,
        )

    val isZero: Boolean get() = input == 0L && cacheRead == 0L && cacheWrite == 0L && output == 0L

    companion object {
        val ZERO = TokenCounts()

        /** The name a call's tokens are kept under when the provider didn't say which model answered. */
        const val UNKNOWN_MODEL = "unknown"
    }
}
