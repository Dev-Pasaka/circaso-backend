package com.circaso.share.api

import java.security.SecureRandom

/**
 * The code that names a share link: the last segment of `<SHARE_BASE_URL>/s/<code>`. Twelve base62 characters from a
 * secure random source (about 71 bits), so a code can be handed out in public but never guessed or walked
 * (decision 2026-11-06). Never logged: whoever has it can open the page.
 */
@JvmInline
value class ShareCode(val value: String) {
    override fun toString(): String = "ShareCode(***)"

    companion object {
        const val LENGTH = 12
        private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private val random = SecureRandom()
        private val shape = Regex("[0-9A-Za-z]{$LENGTH}")

        fun new(): ShareCode = ShareCode(String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }))

        /** A code in the exact shape [new] makes, or null: anything else can't be a link. */
        fun parse(raw: String?): ShareCode? = raw?.takeIf { shape.matches(it) }?.let(::ShareCode)
    }
}
