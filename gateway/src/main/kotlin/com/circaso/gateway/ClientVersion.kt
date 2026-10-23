package com.circaso.gateway

/** The app's version as the `X-Client-Version` header sends it: `android/1.4.0`. */
data class ClientVersion(val parts: List<Int>) : Comparable<ClientVersion> {
    override fun compareTo(other: ClientVersion): Int {
        val size = maxOf(parts.size, other.parts.size)
        for (i in 0 until size) {
            val c = (parts.getOrElse(i) { 0 }).compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    companion object {
        const val HEADER = "X-Client-Version"

        /** `1.4.0` or `1.4.0-debug`; the suffix is ignored. */
        fun parseVersion(raw: String): ClientVersion? {
            val numbers = raw.trim().substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
            return numbers.takeIf { it.isNotEmpty() }?.let(::ClientVersion)
        }

        /** `(platform, version)` from `android/1.4.0`, or null when the header is absent or unreadable. */
        fun parseHeader(raw: String?): Pair<String, ClientVersion>? {
            val (platform, version) = (raw ?: return null).split('/', limit = 2).takeIf { it.size == 2 } ?: return null
            return parseVersion(version)?.let { platform.lowercase() to it }
        }
    }
}
